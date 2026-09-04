package dev.dota.etl.metrics;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/** Builds ward lifecycle, deward and smoke evidence from the extracted streams. */
final class VisionMetricsBuilder {

    private final Connection conn;

    VisionMetricsBuilder(Connection conn) {
        this.conn = conn;
    }

    void addTo(ObjectNode root) throws Exception {
        createTables();
        ObjectNode vision = root.putObject("vision");
        ArrayNode summary = vision.putArray("ward_summary");
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("""
                SELECT team, type, COUNT(*) placed,
                       COUNT(*) FILTER (WHERE removal_reason='destroyed') destroyed,
                       COUNT(*) FILTER (WHERE removal_reason='expired') expired,
                       AVG(lifetime_sec) FILTER (WHERE lifetime_sec IS NOT NULL) avg_lifetime_sec
                FROM ward_lifetimes GROUP BY team, type ORDER BY team, type
                """)) {
            while (rs.next()) {
                ObjectNode row = summary.addObject();
                row.put("team", rs.getInt("team"));
                row.put("type", rs.getString("type"));
                row.put("placed", rs.getLong("placed"));
                row.put("destroyed", rs.getLong("destroyed"));
                row.put("expired", rs.getLong("expired"));
                double avg = rs.getDouble("avg_lifetime_sec");
                if (!rs.wasNull()) row.put("avg_lifetime_sec", round1(avg));
            }
        }

        ArrayNode players = vision.putArray("players");
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("""
                WITH placed AS (
                  SELECT player, team,
                         COUNT(*) FILTER (WHERE type='observer') observer_wards,
                         COUNT(*) FILTER (WHERE type='sentry') sentry_wards
                  FROM ward_lifetimes WHERE player IS NOT NULL GROUP BY player, team
                ), removed AS (
                  SELECT ht.player, ht.team, COUNT(*) dewards
                  FROM dewards d JOIN hero_team ht ON ht.hero_key=d.destroyer_key
                  GROUP BY ht.player, ht.team
                )
                SELECT ht.player, ht.team,
                       COALESCE(p.observer_wards, 0) observer_wards,
                       COALESCE(p.sentry_wards, 0) sentry_wards,
                       COALESCE(r.dewards, 0) dewards
                FROM hero_team ht
                LEFT JOIN placed p USING (player)
                LEFT JOIN removed r USING (player)
                ORDER BY ht.player
                """)) {
            while (rs.next()) {
                ObjectNode row = players.addObject();
                row.put("player", rs.getInt("player"));
                row.put("team", rs.getInt("team"));
                row.put("observer_wards", rs.getLong("observer_wards"));
                row.put("sentry_wards", rs.getLong("sentry_wards"));
                row.put("dewards", rs.getLong("dewards"));
            }
        }

        ArrayNode dewards = vision.putArray("dewards");
        appendRows(dewards, "SELECT * FROM dewards ORDER BY t", true);
        ArrayNode smokes = vision.putArray("smoke_events");
        appendRows(smokes, "SELECT * FROM smoke_events ORDER BY t, event_type", false);
    }

    private void createTables() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TEMP TABLE ward_lifetimes AS SELECT * FROM wards_v");
            st.execute("""
                CREATE TEMP TABLE dewards AS
                SELECT ward_id, removed_t AS t, type, team, player, x, y,
                       destroyer, replace(destroyer, 'npc_dota_hero_', '') AS destroyer_key,
                       destroyer_team, lifetime_sec
                FROM ward_lifetimes WHERE removal_reason='destroyed'
                """);
            if (hasColumn("combatlog_v", "inflictor")) {
                st.execute("""
                    CREATE TEMP TABLE smoke_uses AS
                    WITH uses AS (
                      SELECT c.t, c.attacker, c.attacker_key,
                             COALESCE(c.attacker_team, ht.team) AS team
                      FROM combatlog_v c LEFT JOIN hero_team ht ON ht.hero_key=c.attacker_key
                      WHERE c.type='DOTA_COMBATLOG_ITEM' AND c.inflictor='item_smoke_of_deceit'
                    )
                    SELECT ROW_NUMBER() OVER (ORDER BY t, attacker) AS smoke_id,
                           t, attacker, attacker_key, team,
                           LEAD(t) OVER (PARTITION BY team ORDER BY t) AS next_team_smoke_t
                    FROM uses
                    """);
                st.execute("""
                    CREATE TEMP TABLE smoke_breaks AS
                    SELECT u.smoke_id, MIN(c.t) AS t, u.team,
                           ARG_MIN(c.target_key, c.t) AS hero_key, MIN(c.t)-u.t AS elapsed_sec
                    FROM smoke_uses u JOIN combatlog_v c
                      ON c.type='DOTA_COMBATLOG_MODIFIER_REMOVE'
                     AND c.inflictor='modifier_smoke_of_deceit'
                     AND c.target_team=u.team AND c.target_hero
                     AND c.t>u.t AND c.t<u.t+44.5
                     AND (u.next_team_smoke_t IS NULL OR c.t<u.next_team_smoke_t)
                    GROUP BY u.smoke_id, u.team, u.t
                    """);
            } else {
                st.execute("""
                    CREATE TEMP TABLE smoke_uses (
                      smoke_id BIGINT, t DOUBLE, attacker VARCHAR, attacker_key VARCHAR,
                      team INTEGER, next_team_smoke_t DOUBLE)
                    """);
                st.execute("""
                    CREATE TEMP TABLE smoke_breaks (
                      smoke_id BIGINT, t DOUBLE, team INTEGER, hero_key VARCHAR, elapsed_sec DOUBLE)
                    """);
            }
            st.execute("""
                CREATE TEMP TABLE smoke_events AS
                SELECT smoke_id, t, 'used' AS event_type, team, attacker_key AS hero_key,
                       NULL::DOUBLE AS elapsed_sec FROM smoke_uses
                UNION ALL
                SELECT smoke_id, t, 'broken_early' AS event_type, team, hero_key, elapsed_sec
                FROM smoke_breaks
                """);
        }
    }

    private boolean hasColumn(String table, String column) throws Exception {
        try (var ps = conn.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name=? AND column_name=?
                """)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private void appendRows(ArrayNode target, String sql, boolean ward) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ObjectNode row = target.addObject();
                row.put("t", round1(rs.getDouble("t")));
                row.put("team", rs.getInt("team"));
                if (ward) {
                    row.put("ward_id", rs.getString("ward_id"));
                    row.put("type", rs.getString("type"));
                    put(row, "destroyer", rs.getString("destroyer_key"));
                    putNumber(row, "x", rs, "x");
                    putNumber(row, "y", rs, "y");
                } else {
                    row.put("smoke_id", rs.getLong("smoke_id"));
                    row.put("event_type", rs.getString("event_type"));
                    put(row, "hero", rs.getString("hero_key"));
                    putNumber(row, "elapsed_sec", rs, "elapsed_sec");
                }
            }
        }
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null && !value.isEmpty()) node.put(field, value);
    }

    private static void putNumber(ObjectNode node, String field, ResultSet rs, String column) throws Exception {
        double value = rs.getDouble(column);
        if (!rs.wasNull()) node.put(field, round1(value));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
