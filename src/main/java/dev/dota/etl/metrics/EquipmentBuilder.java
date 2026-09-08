package dev.dota.etl.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.util.List;

/** Sampled inventory evidence, deliberately separate from purchases and operational availability. */
final class EquipmentBuilder {
    static final List<String> TABLES = List.of("equipment_samples", "equipment_changes",
        "equipment_first_observations", "equipment_windows", "equipment_uses");
    private final Connection conn;

    EquipmentBuilder(Connection conn) { this.conn = conn; }

    void addTo(ObjectNode root, double timeOffset, int build) throws Exception {
        PlayerObservationClock.createView(conn, timeOffset);
        var parameters = root.withObject("/parameters").putObject("equipment")
            .put("max_sample_age_sec", 5).put("max_change_gap_sec", 5)
            .put("counter_source", "m_fCooldown:sampled_remaining_seconds;m_iCurrentCharges:item_count_not_cast_count")
            .put("counter_audited_build", 10836);
        parameters.put("position_clock", "equipment.observed_raw_t_minus_horn");
        if (build >= 0) parameters.put("replay_build", build); else parameters.putNull("replay_build");
        parameters.putArray("counter_audited_items").add("item_black_king_bar").add("item_magic_wand");
        root.withObject("/semantics").put("equipment",
            "purchase != observed_inventory != carried != main != usable; availability unknown; counters sampled, not extrapolated; "
                + "absence is not sale; first observations are sampled upper bounds; unrecorded use is not non-use");
        try (var st = conn.createStatement()) {
            // Legacy persisted extraction really exists. Do not promote its lossy items map to a complete sample.
            st.execute(MetricQueries.sql("""
                CREATE TEMP TABLE equipment_samples AS
                WITH raw AS (
                  SELECT p.*,h.hero_key roster_hero,h.team roster_team,
                         json_extract(to_json(p),'$.equipment') e
                  FROM players_v p JOIN hero_team h ON p.player=h.player
                ), samples AS (
                  SELECT player,roster_hero hero_key,roster_team team,tick,
                         CASE WHEN isfinite(try_cast(e->>'observed_raw_t' AS DOUBLE))
                              THEN try_cast(e->>'observed_raw_t' AS DOUBLE) ELSE raw_t END raw_sample_t,
                         raw_sample_t-{} sample_t,
                         CASE WHEN hero_key IS DISTINCT FROM roster_hero OR team IS DISTINCT FROM roster_team
                                THEN 'identity_unknown'
                              WHEN json_type(e)='OBJECT' AND NOT COALESCE(
                                isfinite(try_cast(e->>'observed_raw_t' AS DOUBLE)),false) THEN 'clock_unknown'
                              WHEN (e->>'status') IN ('complete','partial') AND
                                (e->>'source') IS DISTINCT FROM 'selected_hero.m_hItems/EntityNames' THEN 'source_unknown'
                              WHEN (e->>'status')='complete' AND NOT COALESCE(
                                isfinite(try_cast(e->>'observed_raw_t' AS DOUBLE))
                                AND try_cast(e->>'slot_count' AS INTEGER) BETWEEN 1 AND 128
                                AND json_array_length(e->'slots')=try_cast(e->>'slot_count' AS INTEGER)
                                AND (SELECT COUNT(DISTINCT try_cast(value->>'slot' AS INTEGER))
                                  FROM json_each(e->'slots') WHERE (value->>'status') IN ('empty','occupied')
                                    AND try_cast(value->>'slot' AS INTEGER)>=0
                                    AND try_cast(value->>'slot' AS INTEGER)<try_cast(e->>'slot_count' AS INTEGER)
                                    AND ((value->>'status')='empty' OR (
                                      (value->>'item') LIKE 'item_%' AND try_cast(value->>'entity_uid' AS BIGINT) IS NOT NULL)))
                                  =try_cast(e->>'slot_count' AS INTEGER),false) THEN 'partial'
                              ELSE COALESCE(e->>'status','legacy_unknown') END completeness,
                         e->>'source' AS source,e->>'clock_source' AS clock_source,
                         try_cast(e->>'slot_count' AS INTEGER) slot_count,
                         COALESCE((SELECT json_group_array(value) FROM json_each(e->'slots')
                                   WHERE value->>'status'<>'empty'),'[]'::JSON) slots
                  FROM raw
                ), numbered AS (
                  SELECT ROW_NUMBER() OVER (ORDER BY tick,player,sample_t,slots,completeness) sample_id,*
                  FROM samples WHERE isfinite(sample_t)
                )
                SELECT *,
                       LAG(sample_id) OVER (PARTITION BY player ORDER BY tick,sample_id) previous_sample_id
                FROM numbered
                """, timeOffset));
            st.execute("""
                CREATE TEMP TABLE equipment_slot_observations AS
                SELECT s.sample_id,s.player,s.hero_key,s.team,s.sample_t,
                       try_cast(j.value->>'entity_uid' AS BIGINT) entity_uid,
                       j.value->>'item' AS item,try_cast(j.value->>'slot' AS INTEGER) slot,
                       j.value->>'region' AS region
                FROM equipment_samples s,json_each(s.slots) j
                WHERE s.completeness IN ('complete','partial') AND (j.value->>'status')='occupied'
                  AND (j.value->>'item') LIKE 'item_%' AND entity_uid IS NOT NULL
                  AND slot>=0 AND slot<s.slot_count
                """);
            st.execute("""
                CREATE TEMP TABLE equipment_changes AS
                WITH candidates AS (
                  SELECT s.sample_id,o.entity_uid FROM equipment_samples s
                  JOIN equipment_slot_observations o ON o.sample_id=s.sample_id
                  UNION
                  SELECT s.sample_id,o.entity_uid FROM equipment_samples s
                  JOIN equipment_slot_observations o ON o.sample_id=s.previous_sample_id
                )
                SELECT s.sample_id,s.previous_sample_id,s.player,s.hero_key,s.sample_t,
                       p.sample_t previous_sample_t,s.sample_t-p.sample_t observation_gap_sec,
                       c.entity_uid,COALESCE(a.item,b.item) item,b.slot from_slot,a.slot to_slot,
                       b.region from_region,a.region to_region,
                       CASE WHEN s.completeness<>'complete' OR p.completeness IS DISTINCT FROM 'complete'
                                  OR s.sample_t-p.sample_t>5 THEN 'comparison_unknown'
                            WHEN a.entity_uid IS NULL THEN 'disappeared_observed'
                            WHEN b.entity_uid IS NULL THEN 'appeared_observed'
                            ELSE 'slot_moved_observed' END change_type,
                       'selected_hero.m_hItems/EntityNames' AS source
                FROM candidates c JOIN equipment_samples s USING(sample_id)
                LEFT JOIN equipment_samples p ON p.sample_id=s.previous_sample_id
                LEFT JOIN equipment_slot_observations a ON a.sample_id=s.sample_id AND a.entity_uid=c.entity_uid
                LEFT JOIN equipment_slot_observations b ON b.sample_id=s.previous_sample_id AND b.entity_uid=c.entity_uid
                WHERE a.slot IS DISTINCT FROM b.slot OR a.item IS DISTINCT FROM b.item
                """);
            st.execute("""
                CREATE TEMP TABLE equipment_first_observations AS
                WITH kinds AS (
                  SELECT *, 'inventory' observation FROM equipment_slot_observations
                  UNION ALL
                  SELECT *, 'carried' FROM equipment_slot_observations
                  WHERE region IN ('main','backpack','teleport','neutral','neutral_enhancement')
                  UNION ALL SELECT *, 'main' FROM equipment_slot_observations WHERE region='main'
                ), firsts AS (
                  SELECT * FROM kinds QUALIFY ROW_NUMBER() OVER
                    (PARTITION BY player,item,observation ORDER BY sample_t,sample_id,slot)=1
                )
                SELECT f.*,p.sample_t previous_sample_t,
                       CASE WHEN p.completeness='complete' AND s.sample_t-p.sample_t<=5
                            THEN p.sample_t END lower_bound_t,
                       CASE WHEN lower_bound_t IS NOT NULL THEN s.sample_t-lower_bound_t END uncertainty_sec,
                       'first_observed_not_purchase_or_exact_delivery' AS source
                FROM firsts f JOIN equipment_samples s USING(sample_id)
                LEFT JOIN equipment_samples p ON p.sample_id=s.previous_sample_id
                """);
            // Presence is required for local equipment. Credited-only illusion owners get no body inventory.
            st.execute("""
                CREATE TEMP TABLE equipment_contexts AS
                SELECT 'death' context_type,k.kill_id context_id,h.player,h.hero_key,h.team,k.t anchor_t,
                       k.t-15 window_start,k.t window_end
                FROM hero_kills k JOIN hero_team h ON h.hero_key=k.target_player_key AND h.team=k.target_team
                UNION ALL
                SELECT 'local_fight',f.id,h.player,h.hero_key,h.team,f.start,f.start,f."end"
                FROM local_fight_players p JOIN local_fights f ON f.id=p.fight_id
                JOIN hero_team h ON h.hero_key=p.hero_key WHERE p.presence_events>0
                """);
            st.execute("""
                CREATE TEMP TABLE equipment_windows AS
                SELECT c.*,s.sample_id,s.sample_t,c.anchor_t-s.sample_t age_sec,s.source,s.clock_source,
                       s.completeness,
                       CASE WHEN s.sample_id IS NULL THEN 'missing'
                            WHEN c.anchor_t-s.sample_t>5 THEN 'stale'
                            WHEN s.completeness<>'complete' THEN s.completeness
                            ELSE 'observed' END status,
                       CASE WHEN status='observed' THEN s.slots ELSE NULL END slots,
                       'unknown' availability
                FROM equipment_contexts c LEFT JOIN LATERAL (
                  SELECT * FROM equipment_samples s WHERE s.player=c.player AND s.hero_key=c.hero_key
                    AND s.team=c.team AND s.sample_t<=c.anchor_t
                  ORDER BY s.tick DESC,s.sample_id DESC LIMIT 1
                ) s ON true
                """);
            st.execute("""
                CREATE TEMP TABLE equipment_uses AS
                SELECT w.context_type,w.context_id,w.player,w.hero_key,c.event_id,c.t,c.raw_t,
                       c.t-w.anchor_t offset_sec,c.attacker attacker_unit,
                       json_extract_string(to_json(c),'$.inflictor') item,c.attribution_source,
                       'combatlog_ITEM' AS source,
                       CASE WHEN w.context_type='death' THEN 'victim_time_window'
                            ELSE 'member_body_position_near_member_event' END association_source,
                       e.event_id member_event_id,
                        CASE WHEN w.context_type='local_fight' THEN p.t END position_sample_t,
                        CASE WHEN w.context_type='local_fight' THEN p.tick END position_tick,
                        CASE WHEN w.context_type='local_fight' THEN 'equipment.observed_raw_t_minus_horn' END position_clock_source,
                       CASE WHEN w.context_type='local_fight' THEN c.t-p.t END position_age_sec,
                       CASE WHEN w.context_type='local_fight' THEN p.x END x,
                       CASE WHEN w.context_type='local_fight' THEN p.y END y
                FROM equipment_contexts w JOIN combatlog_v c
                  ON c.credited_attacker_key=w.hero_key AND c.attacker_hero
                 AND c.attribution_source IN ('roster_team_match','hero_action_identity')
                 AND c.t>=w.window_start AND c.t<=w.window_end
                LEFT JOIN LATERAL (
                   SELECT * FROM players_observed p WHERE p.player=w.player
                     AND p.asof_t<=c.t ORDER BY p.tick DESC,p.scheduled_t DESC LIMIT 1
                ) p ON true
                LEFT JOIN LATERAL (
                  SELECT e.event_id FROM local_fight_events e
                  WHERE w.context_type='local_fight' AND e.fight_id=w.context_id
                    AND (e.attacker_presence_key=w.hero_key OR e.target_presence_key=w.hero_key)
                    AND abs(e.t-c.t)<=5 AND sqrt(pow(e.x-p.x,2)+pow(e.y-p.y,2))<=2400
                  ORDER BY abs(e.t-c.t),e.event_id LIMIT 1
                ) e ON true
                WHERE c.type='DOTA_COMBATLOG_ITEM'
                  AND (w.context_type='death' OR (
                     p.hero_key=w.hero_key AND p.team=w.team AND p.t IS NOT NULL AND c.t-p.t BETWEEN 0 AND 5
                    AND p.hp>0 AND isfinite(p.x) AND isfinite(p.y) AND NOT(p.x=0 AND p.y=0)
                    AND e.event_id IS NOT NULL))
                """);
        }
        var mapper = new ObjectMapper();
        for (String table : TABLES) {
            var array = root.putArray(table);
            try (var st = conn.createStatement(); var rs = st.executeQuery(
                "SELECT to_json(r) FROM (SELECT * FROM " + table + " ORDER BY ALL) r")) {
                while (rs.next()) array.add(mapper.readTree(rs.getString(1)));
            }
        }
        for (var death : root.path("incidents").path("deaths")) {
            for (var window : root.path("equipment_windows")) {
                if (window.path("context_type").asText().equals("death")
                    && window.path("context_id").asLong()==death.path("kill_id").asLong()) {
                    ((ObjectNode) death).set("equipment", window.deepCopy());
                }
            }
        }
        org.slf4j.LoggerFactory.getLogger(EquipmentBuilder.class).info("Equipment: {} samples, {} context windows",
            root.path("equipment_samples").size(), root.path("equipment_windows").size());
    }
}
