package dev.dota.etl.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.dota.etl.metrics.MetricQueries.DEATH_INCIDENT_AFTER_SEC;
import static dev.dota.etl.metrics.MetricQueries.DEATH_INCIDENT_BEFORE_SEC;
import static dev.dota.etl.metrics.MetricQueries.DEATH_INCIDENT_NEARBY_RADIUS;
import static dev.dota.etl.metrics.MetricQueries.KILL_LOCATION_MAX_AGE_SEC;
import static dev.dota.etl.metrics.MetricQueries.sql;

/** Builds deterministic context windows around every hero death. */
final class DeathIncidentBuilder {

    private final Connection conn;

    DeathIncidentBuilder(Connection conn) {
        this.conn = conn;
    }

    void addTo(ObjectNode root) throws Exception {
        createTables();

        ObjectNode incidents = root.putObject("incidents");
        incidents.put("death_window_before_sec", DEATH_INCIDENT_BEFORE_SEC);
        incidents.put("death_window_after_sec", DEATH_INCIDENT_AFTER_SEC);
        incidents.put("nearby_radius", DEATH_INCIDENT_NEARBY_RADIUS);
        ArrayNode deaths = incidents.putArray("deaths");
        Map<Integer, ObjectNode> byId = new LinkedHashMap<>();
        Map<Integer, JsonNode> killById = new LinkedHashMap<>();
        for (JsonNode kill : root.path("kills")) {
            killById.put(kill.path("kill_id").asInt(), kill);
        }

        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("""
            SELECT d.kill_id, d.t, d.raw_t, d.attacker, d.attacker_key, d.attacker_team,
                   d.target, d.target_key, d.target_team, d.x, d.y, d.location_source,
                   d.location_age_sec, d.networth, v.first_hp, v.last_bkb_age_sec
            FROM death_incidents d
            LEFT JOIN incident_vitals v USING (kill_id)
            ORDER BY d.kill_id
            """)) {
            while (rs.next()) {
                int id = rs.getInt("kill_id");
                ObjectNode death = deaths.addObject();
                death.put("incident_id", "death-" + id);
                death.put("type", "hero_death");
                death.put("kill_id", id);
                death.put("t", round1(rs.getDouble("t")));
                death.put("raw_t", round1(rs.getDouble("raw_t")));
                putText(death, "killer", rs.getString("attacker"));
                putText(death, "killer_key", rs.getString("attacker_key"));
                death.put("killer_team", rs.getInt("attacker_team"));
                putText(death, "victim", rs.getString("target"));
                putText(death, "victim_key", rs.getString("target_key"));
                death.put("victim_team", rs.getInt("target_team"));
                double x = rs.getDouble("x");
                double y = rs.getDouble("y");
                if (!rs.wasNull()) {
                    death.putArray("location").add(round1(x)).add(round1(y));
                    putText(death, "location_source", rs.getString("location_source"));
                    putNumber(death, "location_age_sec", rs, "location_age_sec");
                }
                putLong(death, "victim_networth", rs, "networth");
                putLong(death, "first_observed_hp", rs, "first_hp");
                putNumber(death, "last_bkb_use_age_sec", rs, "last_bkb_age_sec");
                death.putArray("victim_actions");
                death.putArray("controls_received");
                death.putArray("damage_sources");
                death.putArray("health_timeline");
                death.putArray("nearby_heroes");
                death.putArray("nearby_wards");
                death.putArray("smoke_events");
                death.putArray("other_deaths");
                JsonNode kill = killById.get(id);
                if (kill != null && kill.has("conceded_objective")) {
                    death.set("followup_objective", kill.path("conceded_objective").deepCopy());
                }
                byId.put(id, death);
            }
        }

        appendActions(byId);
        appendControls(byId);
        appendDamage(byId);
        appendHealth(byId);
        appendNearby(byId);
        appendVision(byId);
        appendSmoke(byId);
        appendOtherDeaths(byId);
    }

    private void createTables() throws Exception {
        boolean hasInflictor = hasColumn("combatlog_v", "inflictor");
        boolean hasHealth = hasColumn("combatlog_v", "health");
        String inflictor = hasInflictor ? "c.inflictor" : "NULL::VARCHAR";
        String health = hasHealth ? "c.health" : "NULL::DOUBLE";

        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TEMP TABLE death_incidents AS SELECT * FROM hero_kills");
            st.execute(sql("""
                CREATE TEMP TABLE incident_actions AS
                SELECT k.kill_id, c.t - k.t AS offset_sec,
                       CASE c.type WHEN 'DOTA_COMBATLOG_ABILITY' THEN 'ability' ELSE 'item' END AS action_type,
                       {} AS name
                FROM hero_kills k JOIN combatlog_v c
                  ON c.attacker_key = k.target_key
                 AND c.t >= k.t - {} AND c.t <= k.t
                WHERE c.type IN ('DOTA_COMBATLOG_ABILITY', 'DOTA_COMBATLOG_ITEM')
                  AND COALESCE({}, '') NOT LIKE '%power_treads%'
                ORDER BY k.kill_id, c.t
                """, inflictor, DEATH_INCIDENT_BEFORE_SEC, inflictor));
            st.execute(sql("""
                CREATE TEMP TABLE incident_controls AS
                SELECT k.kill_id, c.t - k.t AS offset_sec, c.attacker_key AS source,
                       {} AS modifier
                FROM hero_kills k JOIN combatlog_v c
                  ON c.target_key = k.target_key
                 AND c.t >= k.t - {} AND c.t <= k.t
                WHERE c.type = 'DOTA_COMBATLOG_MODIFIER_ADD'
                  AND (lower(COALESCE({}, '')) LIKE '%stun%'
                    OR lower(COALESCE({}, '')) LIKE '%silence%'
                    OR lower(COALESCE({}, '')) LIKE '%root%'
                    OR lower(COALESCE({}, '')) LIKE '%ensnare%'
                    OR lower(COALESCE({}, '')) LIKE '%orchid%'
                    OR lower(COALESCE({}, '')) LIKE '%harpoon%'
                    OR lower(COALESCE({}, '')) LIKE '%slow%'
                    OR lower(COALESCE({}, '')) LIKE '%hex%'
                    OR lower(COALESCE({}, '')) LIKE '%bash%')
                ORDER BY k.kill_id, c.t
                """, inflictor, DEATH_INCIDENT_BEFORE_SEC,
                inflictor, inflictor, inflictor, inflictor, inflictor,
                inflictor, inflictor, inflictor, inflictor));
            st.execute(sql("""
                CREATE TEMP TABLE incident_damage_sources AS
                SELECT k.kill_id, COALESCE(c.attacker_key, c.attacker, 'unknown') AS source,
                       SUM(COALESCE(c.value, 0)) AS damage, COUNT(*) AS events
                FROM hero_kills k JOIN combatlog_v c
                  ON c.target_key = k.target_key
                 AND c.t >= k.t - {} AND c.t <= k.t
                WHERE c.type = 'DOTA_COMBATLOG_DAMAGE'
                GROUP BY k.kill_id, source
                ORDER BY k.kill_id, damage DESC
                """, DEATH_INCIDENT_BEFORE_SEC));
            st.execute(sql("""
                CREATE TEMP TABLE incident_health_timeline AS
                SELECT k.kill_id, c.t - k.t AS offset_sec,
                       {} AS hp_after, COALESCE(c.value, 0) AS damage,
                       COALESCE(c.attacker_key, c.attacker, 'unknown') AS source
                FROM hero_kills k JOIN combatlog_v c
                  ON c.target_key = k.target_key
                 AND c.t >= k.t - {} AND c.t <= k.t
                WHERE c.type = 'DOTA_COMBATLOG_DAMAGE' AND {} IS NOT NULL
                ORDER BY k.kill_id, c.t
                """, health, DEATH_INCIDENT_BEFORE_SEC, health));
            st.execute(sql("""
                CREATE TEMP TABLE incident_vitals AS
                SELECT k.kill_id,
                       ARG_MIN({} + COALESCE(c.value, 0), c.t)
                         FILTER (WHERE c.type='DOTA_COMBATLOG_DAMAGE' AND {} IS NOT NULL) AS first_hp,
                       k.t - MAX(c.t) FILTER (
                         WHERE c.type='DOTA_COMBATLOG_ITEM'
                           AND lower(COALESCE({}, '')) LIKE '%black_king_bar%') AS last_bkb_age_sec
                FROM hero_kills k
                LEFT JOIN combatlog_v c ON c.t <= k.t AND (
                  (c.target_key = k.target_key AND c.type='DOTA_COMBATLOG_DAMAGE' AND c.t >= k.t - {})
                  OR (c.attacker_key = k.target_key AND c.type='DOTA_COMBATLOG_ITEM'
                    AND lower(COALESCE({}, '')) LIKE '%black_king_bar%'))
                GROUP BY k.kill_id, k.t
                """, health, health, inflictor, DEATH_INCIDENT_BEFORE_SEC, inflictor));
            st.execute(sql("""
                CREATE TEMP TABLE incident_nearby_heroes AS
                WITH candidates AS (
                  SELECT k.kill_id, k.target_key AS victim_key, k.target_team AS victim_team,
                         k.t AS death_t, k.x AS death_x, k.y AS death_y,
                         p.player, p.hero_key, p.team, p.t, p.x, p.y, p.hp,
                         ROW_NUMBER() OVER (PARTITION BY k.kill_id, p.player ORDER BY p.t DESC) AS rn
                  FROM hero_kills k JOIN players_v p
                    ON p.hero_key <> k.target_key AND p.t <= k.t
                   AND k.t - p.t <= {} AND p.x IS NOT NULL AND p.y IS NOT NULL
                ), latest AS (
                  SELECT *, SQRT(POWER(x - death_x, 2) + POWER(y - death_y, 2)) AS distance
                  FROM candidates WHERE rn = 1 AND (hp IS NULL OR hp > 0)
                )
                SELECT kill_id, player, hero_key, team,
                       CASE WHEN team = victim_team THEN 'ally' ELSE 'enemy' END AS relation,
                       distance, death_t - t AS sample_age_sec, hp
                FROM latest WHERE distance <= {}
                ORDER BY kill_id, distance
                """, KILL_LOCATION_MAX_AGE_SEC, DEATH_INCIDENT_NEARBY_RADIUS));
            st.execute(sql("""
                CREATE TEMP TABLE incident_other_deaths AS
                SELECT k.kill_id, o.kill_id AS other_kill_id, o.t - k.t AS offset_sec,
                       o.target_key AS victim_key, o.target_team AS victim_team
                FROM hero_kills k JOIN hero_kills o ON o.kill_id <> k.kill_id
                 AND o.t >= k.t - {} AND o.t <= k.t + {}
                ORDER BY k.kill_id, o.t
                """, DEATH_INCIDENT_BEFORE_SEC, DEATH_INCIDENT_AFTER_SEC));
            st.execute(sql("""
                CREATE TEMP TABLE incident_vision AS
                SELECT k.kill_id, w.ward_id, w.type, w.team, w.player,
                       CASE WHEN w.team=k.target_team THEN 'ally' ELSE 'enemy' END relation,
                       SQRT(POWER(w.x-k.x, 2)+POWER(w.y-k.y, 2)) distance,
                       k.t-w.placed_t ward_age_sec, w.x, w.y
                FROM hero_kills k JOIN ward_lifetimes w
                  ON w.placed_t<=k.t AND (w.removed_t IS NULL OR w.removed_t>=k.t)
                 AND w.x IS NOT NULL AND w.y IS NOT NULL
                WHERE k.x IS NOT NULL AND k.y IS NOT NULL
                  AND SQRT(POWER(w.x-k.x, 2)+POWER(w.y-k.y, 2))<={}
                ORDER BY k.kill_id, distance
                """, DEATH_INCIDENT_NEARBY_RADIUS));
            st.execute(sql("""
                CREATE TEMP TABLE incident_smoke_events AS
                SELECT k.kill_id, s.smoke_id, s.t-k.t offset_sec,
                       s.event_type, s.team, s.hero_key, s.elapsed_sec
                FROM hero_kills k JOIN smoke_events s
                  ON s.t>=k.t-{} AND s.t<=k.t+{}
                ORDER BY k.kill_id, s.t
                """, DEATH_INCIDENT_BEFORE_SEC, DEATH_INCIDENT_AFTER_SEC));
        }
    }

    private void appendActions(Map<Integer, ObjectNode> byId) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT * FROM incident_actions ORDER BY kill_id, offset_sec")) {
            while (rs.next()) {
                ObjectNode row = add(byId, rs, "victim_actions");
                if (row == null) continue;
                row.put("offset_sec", round1(rs.getDouble("offset_sec")));
                putText(row, "action_type", rs.getString("action_type"));
                putText(row, "name", rs.getString("name"));
            }
        }
    }

    private void appendControls(Map<Integer, ObjectNode> byId) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT * FROM incident_controls ORDER BY kill_id, offset_sec")) {
            while (rs.next()) {
                ObjectNode row = add(byId, rs, "controls_received");
                if (row == null) continue;
                row.put("offset_sec", round1(rs.getDouble("offset_sec")));
                putText(row, "source", rs.getString("source"));
                putText(row, "modifier", rs.getString("modifier"));
            }
        }
    }

    private void appendDamage(Map<Integer, ObjectNode> byId) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT * FROM incident_damage_sources ORDER BY kill_id, damage DESC")) {
            while (rs.next()) {
                ObjectNode row = add(byId, rs, "damage_sources");
                if (row == null) continue;
                putText(row, "source", rs.getString("source"));
                row.put("damage", rs.getLong("damage"));
                row.put("events", rs.getLong("events"));
            }
        }
    }

    private void appendHealth(Map<Integer, ObjectNode> byId) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT * FROM incident_health_timeline ORDER BY kill_id, offset_sec")) {
            while (rs.next()) {
                ObjectNode row = add(byId, rs, "health_timeline");
                if (row == null) continue;
                row.put("offset_sec", round1(rs.getDouble("offset_sec")));
                row.put("hp_after", Math.round(rs.getDouble("hp_after")));
                row.put("damage", rs.getLong("damage"));
                putText(row, "source", rs.getString("source"));
            }
        }
    }

    private void appendNearby(Map<Integer, ObjectNode> byId) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT * FROM incident_nearby_heroes ORDER BY kill_id, distance")) {
            while (rs.next()) {
                ObjectNode row = add(byId, rs, "nearby_heroes");
                if (row == null) continue;
                row.put("player", rs.getInt("player"));
                putText(row, "hero", rs.getString("hero_key"));
                row.put("team", rs.getInt("team"));
                putText(row, "relation", rs.getString("relation"));
                row.put("distance", Math.round(rs.getDouble("distance")));
                row.put("sample_age_sec", round1(rs.getDouble("sample_age_sec")));
                putLong(row, "hp", rs, "hp");
            }
        }
    }

    private void appendOtherDeaths(Map<Integer, ObjectNode> byId) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT * FROM incident_other_deaths ORDER BY kill_id, offset_sec")) {
            while (rs.next()) {
                ObjectNode row = add(byId, rs, "other_deaths");
                if (row == null) continue;
                row.put("kill_id", rs.getInt("other_kill_id"));
                row.put("offset_sec", round1(rs.getDouble("offset_sec")));
                putText(row, "victim_key", rs.getString("victim_key"));
                row.put("victim_team", rs.getInt("victim_team"));
            }
        }
    }

    private void appendVision(Map<Integer, ObjectNode> byId) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT * FROM incident_vision ORDER BY kill_id, distance")) {
            while (rs.next()) {
                ObjectNode row = add(byId, rs, "nearby_wards");
                if (row == null) continue;
                putText(row, "ward_id", rs.getString("ward_id"));
                putText(row, "type", rs.getString("type"));
                row.put("team", rs.getInt("team"));
                putText(row, "relation", rs.getString("relation"));
                putLong(row, "player", rs, "player");
                row.put("distance", Math.round(rs.getDouble("distance")));
                row.put("ward_age_sec", round1(rs.getDouble("ward_age_sec")));
            }
        }
    }

    private void appendSmoke(Map<Integer, ObjectNode> byId) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT * FROM incident_smoke_events ORDER BY kill_id, offset_sec")) {
            while (rs.next()) {
                ObjectNode row = add(byId, rs, "smoke_events");
                if (row == null) continue;
                row.put("smoke_id", rs.getLong("smoke_id"));
                row.put("offset_sec", round1(rs.getDouble("offset_sec")));
                putText(row, "event_type", rs.getString("event_type"));
                row.put("team", rs.getInt("team"));
                putText(row, "hero", rs.getString("hero_key"));
                putNumber(row, "elapsed_sec", rs, "elapsed_sec");
            }
        }
    }

    private ObjectNode add(Map<Integer, ObjectNode> byId, ResultSet rs, String array) throws Exception {
        ObjectNode incident = byId.get(rs.getInt("kill_id"));
        return incident == null ? null : incident.withArray(array).addObject();
    }

    private boolean hasColumn(String table, String column) throws Exception {
        try (var ps = conn.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = ? AND column_name = ?
                """)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null && !value.isEmpty()) node.put(field, value);
    }

    private static void putNumber(ObjectNode node, String field, ResultSet rs, String column) throws Exception {
        double value = rs.getDouble(column);
        if (!rs.wasNull()) node.put(field, round1(value));
    }

    private static void putLong(ObjectNode node, String field, ResultSet rs, String column) throws Exception {
        long value = rs.getLong(column);
        if (!rs.wasNull()) node.put(field, value);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
