package dev.dota.etl.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/** Bounded, deterministic clustering of located interactions, never of global time windows. */
final class LocalFightBuilder {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LocalFightBuilder.class);
    static final double MAX_GAP = 5;
    static final double MAX_DURATION = 30;
    static final double MAX_DISTANCE = 2400;
    static final double MAX_SAMPLE_AGE = 5;
    private final Connection conn;

    LocalFightBuilder(Connection conn) {
        this.conn = conn;
    }

    void addTo(ObjectNode root) throws Exception {
        ObjectNode parameters = root.withObject("/parameters").putObject("local_fights");
        parameters.put("max_gap_sec", MAX_GAP).put("max_duration_sec", MAX_DURATION)
            .put("max_pair_distance", MAX_DISTANCE).put("max_sample_age_sec", MAX_SAMPLE_AGE);
        root.withObject("/semantics").put("local_fights",
            "bounded_target_location_interactions; presence_requires_real_identity_and_local_position; economy_unknown");
        // Combat-log location fields have no general target-position contract. Use only real-target
        // as-of samples; never substitute an illusion owner's coordinates for the acting unit.
        // Validate after LIMIT: a newer dead or invalid sample must block fallback to older evidence.
        conn.createStatement().execute(MetricQueries.sql("""
            CREATE TEMP TABLE local_fight_events AS
            SELECT c.event_id, NULL::INTEGER fight_id, c.t, c.raw_t, c.type, c.value,
                   c.attacker AS attacker_unit, c.target AS target_unit,
                   c.credited_attacker_key, c.attribution_source, c.target_player_key,
                   c.attacker_team, c.target_team, d.kill_id, d.death_class,
                   p.x, p.y, p.t sample_t, c.t-p.t location_age_sec,
                   CASE WHEN p.t IS NULL THEN 'unknown' ELSE 'target_player_asof' END location_source,
                   CASE WHEN p.t IS NULL THEN 'missing_or_stale_real_target_position'
                        ELSE 'located' END location_status,
                   CASE WHEN p.t IS NOT NULL THEN c.target_player_key END target_presence_key,
                   CASE WHEN c.attribution_source='roster_team_match' AND p.t IS NOT NULL
                          AND a.t IS NOT NULL AND sqrt(pow(a.x-p.x,2)+pow(a.y-p.y,2))<={}
                        THEN c.credited_attacker_key END attacker_presence_key,
                   a.t attacker_sample_t, c.t-a.t attacker_location_age_sec,
                   a.x attacker_x, a.y attacker_y
            FROM combatlog_v c
            LEFT JOIN hero_death_events d ON d.event_id=c.event_id
            LEFT JOIN LATERAL (
              SELECT * FROM (
                SELECT t,x,y,hp FROM players_v p
                WHERE p.hero_key=c.target_player_key AND p.team=c.target_team
                  AND p.t<=c.t AND c.t-p.t<={}
                ORDER BY p.t DESC,p.tick DESC,p.player,p.x,p.y LIMIT 1
              ) latest
              WHERE isfinite(x) AND isfinite(y) AND NOT (x=0 AND y=0)
                AND (hp>0 OR c.type='DOTA_COMBATLOG_DEATH')
            ) p ON true
            LEFT JOIN LATERAL (
              SELECT * FROM (
                SELECT t,x,y,hp FROM players_v a
                WHERE c.attribution_source='roster_team_match'
                  AND a.hero_key=c.credited_attacker_key AND a.team=c.attacker_team
                  AND a.t<=c.t AND c.t-a.t<={}
                ORDER BY a.t DESC,a.tick DESC,a.player,a.x,a.y LIMIT 1
              ) latest
              WHERE hp>0 AND isfinite(x) AND isfinite(y) AND NOT (x=0 AND y=0)
            ) a ON true
            WHERE (c.type='DOTA_COMBATLOG_DAMAGE' AND c.attacker_hero AND c.target_player_key IS NOT NULL
                     AND c.value>0 AND c.attacker_team<>c.target_team)
               OR (c.type='DOTA_COMBATLOG_DEATH' AND d.death_class<>'illusion_death')
            """, MAX_DISTANCE, MAX_SAMPLE_AGE, MAX_SAMPLE_AGE));

        List<List<Event>> fights = new ArrayList<>();
        try (var st = conn.createStatement(); var rs = st.executeQuery("""
                SELECT event_id,t,x,y,attacker_presence_key,target_presence_key
                FROM local_fight_events WHERE x IS NOT NULL AND y IS NOT NULL ORDER BY t,event_id
                """)) {
            while (rs.next()) {
                Event event = new Event(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                    rs.getString(5), rs.getString(6));
                List<Event> selected = null;
                // First compatible cluster wins; clusters are never unioned through a bridging hero.
                for (List<Event> fight : fights) {
                    if (event.t - fight.get(0).t > MAX_DURATION) continue;
                    boolean connected = false;
                    boolean bounded = true;
                    for (Event member : fight) {
                        if (Math.hypot(event.x-member.x, event.y-member.y) > MAX_DISTANCE) {
                            bounded = false;
                            break;
                        }
                        if (event.t-member.t <= MAX_GAP && event.sharesBody(member)) connected = true;
                    }
                    if (bounded && connected) { selected = fight; break; }
                }
                if (selected == null) {
                    selected = new ArrayList<>();
                    fights.add(selected);
                }
                selected.add(event);
            }
        }
        try (var update = conn.prepareStatement("UPDATE local_fight_events SET fight_id=? WHERE event_id=?")) {
            for (int id = 0; id < fights.size(); id++) {
                for (Event event : fights.get(id)) {
                    update.setInt(1, id);
                    update.setLong(2, event.id);
                    update.addBatch();
                }
            }
            update.executeBatch();
        }
        conn.createStatement().execute("""
            CREATE TEMP TABLE local_fight_players AS
            WITH roles AS (
              SELECT fight_id,credited_attacker_key hero_key,
                     CASE WHEN type='DOTA_COMBATLOG_DAMAGE' THEN value ELSE 0 END damage_dealt,
                     0 damage_taken, CASE WHEN death_class IN ('scored_death','unknown')
                       AND attacker_team IN (2,3) AND target_team IN (2,3)
                       AND attacker_team<>target_team THEN 1 ELSE 0 END kills,
                     0 deaths,0 scored_deaths,0 unknown_deaths,0 presence_events
              FROM local_fight_events WHERE fight_id IS NOT NULL AND credited_attacker_key IS NOT NULL
              UNION ALL
              SELECT fight_id,target_player_key,0,
                     CASE WHEN type='DOTA_COMBATLOG_DAMAGE' THEN value ELSE 0 END,
                     0,CASE WHEN death_class IN ('scored_death','unknown') THEN 1 ELSE 0 END,
                     CASE WHEN death_class='scored_death' THEN 1 ELSE 0 END,
                     CASE WHEN death_class='unknown' THEN 1 ELSE 0 END,0
              FROM local_fight_events WHERE fight_id IS NOT NULL AND target_player_key IS NOT NULL
              UNION ALL
              SELECT fight_id,attacker_presence_key,0,0,0,0,0,0,1
              FROM local_fight_events WHERE fight_id IS NOT NULL AND attacker_presence_key IS NOT NULL
              UNION ALL
              SELECT fight_id,target_presence_key,0,0,0,0,0,0,1
              FROM local_fight_events WHERE fight_id IS NOT NULL AND target_presence_key IS NOT NULL
            )
            SELECT fight_id,hero_key,SUM(damage_dealt) damage_dealt,SUM(damage_taken) damage_taken,
                   SUM(kills) kills,SUM(deaths) deaths,SUM(scored_deaths) scored_deaths,
                   SUM(unknown_deaths) unknown_deaths,SUM(presence_events) presence_events
            FROM roles GROUP BY fight_id,hero_key
            """);
        conn.createStatement().execute("""
            CREATE TEMP TABLE local_fights AS
            WITH aggregates AS (
              SELECT fight_id id,MIN(t) AS start,MAX(t) AS "end",MAX(t)-MIN(t) duration,
                     COUNT(*) event_count,AVG(x) center_x,AVG(y) center_y,
                     MIN(x) min_x,MAX(x) max_x,MIN(y) min_y,MAX(y) max_y,
                     SUM(CASE WHEN type='DOTA_COMBATLOG_DAMAGE' THEN value ELSE 0 END) hero_damage,
                     COUNT(*) FILTER (WHERE death_class='scored_death') scored_deaths,
                     COUNT(*) FILTER (WHERE death_class='unknown') unknown_deaths,
                     COUNT(*) FILTER (WHERE death_class='aegis_respawn') aegis_respawns
              FROM local_fight_events WHERE fight_id IS NOT NULL GROUP BY fight_id
            ), presence AS (
              SELECT p.fight_id,COUNT(*) FILTER (WHERE h.team=2) radiant_present,
                     COUNT(*) FILTER (WHERE h.team=3) dire_present
              FROM local_fight_players p JOIN hero_team h USING(hero_key)
              WHERE p.presence_events>0 GROUP BY p.fight_id
            )
            SELECT a.*,a.scored_deaths+a.unknown_deaths deaths,
                   COALESCE(p.radiant_present,0) radiant_present,COALESCE(p.dire_present,0) dire_present,
                   CASE WHEN p.radiant_present>=3 AND p.dire_present>=3 THEN 'teamfight'
                        WHEN p.radiant_present>=1 AND p.dire_present>=1 AND a.scored_deaths>0
                          AND LEAST(p.radiant_present,p.dire_present)=1
                          AND GREATEST(p.radiant_present,p.dire_present)>=2 THEN 'pickoff'
                        WHEN p.radiant_present+p.dire_present>=3
                          AND p.radiant_present>=1 AND p.dire_present>=1 THEN 'skirmish'
                        WHEN p.radiant_present>=1 AND p.dire_present>=1 AND a.scored_deaths=0
                          AND a.unknown_deaths=0 THEN 'harassment'
                        ELSE 'unknown' END kind,
                   'presence_count_heuristic_not_tactical_intent' kind_source,
                   'unknown_no_spatial_causal_attribution' economy_status
            FROM aggregates a LEFT JOIN presence p ON p.fight_id=a.id
            """);
        export(root, "local_fights", "SELECT * FROM local_fights ORDER BY id");
        export(root, "local_fight_events", "SELECT * FROM local_fight_events ORDER BY t,event_id");
        export(root, "local_fight_players", "SELECT * FROM local_fight_players ORDER BY fight_id,hero_key");
        LOG.info("Local fights: {} bounded clusters, {} candidate interactions (including unlocated)",
            fights.size(), root.path("local_fight_events").size());
    }

    private void export(ObjectNode root, String name, String query) throws Exception {
        var array = root.putArray(name);
        var mapper = new ObjectMapper();
        try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT to_json(r) FROM (" + query + ") r")) {
            while (rs.next()) array.add(mapper.readTree(rs.getString(1)));
        }
    }

    private record Event(long id, double t, double x, double y, String attacker, String target) {
        boolean sharesBody(Event other) {
            return attacker != null && (attacker.equals(other.attacker) || attacker.equals(other.target))
                || target != null && (target.equals(other.attacker) || target.equals(other.target));
        }
    }
}
