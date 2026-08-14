package dev.dota.etl.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Computes match metrics from the ETL output (combatlog.ndjson + players.ndjson)
 * using an in-memory DuckDB. Emits:
 *  - metrics.json      JSON summary for downstream analysis / LLM reports
 *  - metrics.duckdb    the same tables persisted for ad-hoc SQL exploration
 *
 * Metrics produced:
 *  - match summary (game clock bounds, per-team kills, first blood, roshan)
 *  - roster (player index -> hero / name / team / final KDA + level)
 *  - kills (every death event with victim / killer / assists / location)
 *  - teamfights (episodes of elevated combat activity with participants and damage)
 *  - gold / xp curves (cumulative per hero, bucketed)
 *  - item timeline (first purchase time per hero/item)
 *
 * Hero names in the combat log are "npc_dota_hero_lone_druid" while the player
 * samples carry the canonical class name ("LoneDruid"); both are normalised to a
 * shared snake_case key for joining.
 */
public final class MetricsRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // teamfight detection knobs
    private static final int BUCKET_SEC = 5;
    private static final double WEIGHT_DEATH = 4.0;
    private static final double MIN_ACTIVE_SCORE = 8.0;

    private final Path combatLog;
    private final Path players;

    public MetricsRunner(Path combatLog, Path players) {
        this.combatLog = combatLog;
        this.players = players;
    }

    public ObjectNode run() throws Exception {
        ObjectNode metrics = MAPPER.createObjectNode();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            conn.createStatement().execute(
                "CREATE VIEW combatlog AS SELECT * FROM read_ndjson('" + escape(combatLog) + "')");
            conn.createStatement().execute(
                "CREATE VIEW players AS SELECT * FROM read_ndjson('" + escape(players) + "')");
            conn.createStatement().execute("""
                CREATE VIEW combatlog_v AS SELECT *,
                  replace(target, 'npc_dota_hero_', '') AS target_key,
                  replace(attacker, 'npc_dota_hero_', '') AS attacker_key
                FROM combatlog
                """);
            conn.createStatement().execute("""
                CREATE VIEW players_v AS SELECT *,
                  lower(regexp_replace(hero, '([a-z])([A-Z])', '\\1_\\2', 'g')) AS hero_key
                FROM players
                """);

            addSummary(conn, metrics);
            addRoster(conn, metrics);
            addKills(conn, metrics);
            addTeamfights(conn, metrics);
            addGoldCurves(conn, metrics);
            addXpCurves(conn, metrics);
            addItemTimeline(conn, metrics);
            persistTables(conn);
        }
        return metrics;
    }

    // ------------------------------------------------------------------
    // metric sections
    // ------------------------------------------------------------------

    private void addSummary(Connection conn, ObjectNode root) throws Exception {
        ObjectNode s = root.putObject("summary");
        queryOne(conn, "SELECT MIN(t) min_t, MAX(t) max_t FROM combatlog_v").ifPresent(row -> {
            double start = dbl(row.get("min_t"));
            double end = dbl(row.get("max_t"));
            s.put("game_start_sec", round1(start));
            s.put("game_end_sec", round1(end));
            s.put("duration_sec", round1(end - start));
        });
        ArrayNode teamKills = s.putArray("team_kills");
        queryMaps(conn, "SELECT target_team team, COUNT(*) kills FROM combatlog_v " +
            "WHERE type='DOTA_COMBATLOG_DEATH' AND target_hero AND target_team IS NOT NULL " +
            "GROUP BY target_team ORDER BY target_team")
            .forEach(row -> {
                ObjectNode t = teamKills.addObject();
                int team = intOf(row.get("team"));
                t.put("team", team);
                t.put("side", teamSide(team));
                t.put("kills", longOf(row.get("kills")));
            });
        queryOne(conn, "SELECT t, attacker, target FROM combatlog_v " +
            "WHERE type='DOTA_COMBATLOG_DEATH' AND target_hero ORDER BY t LIMIT 1")
            .ifPresent(row -> {
                ObjectNode fb = s.putObject("first_blood");
                fb.put("t", round1(dbl(row.get("t"))));
                putStr(fb, "killer", (String) row.get("attacker"));
                putStr(fb, "victim", (String) row.get("target"));
            });
        queryOne(conn, "SELECT COUNT(*) roshan FROM combatlog_v WHERE type='DOTA_COMBATLOG_DEATH' AND target LIKE 'npc_dota_roshan%'")
            .ifPresent(row -> s.put("roshan_kills", longOf(row.get("roshan"))));
    }

    private void addRoster(Connection conn, ObjectNode root) throws Exception {
        ArrayNode arr = root.putArray("roster");
        queryMaps(conn, """
            SELECT player, name, hero, hero_key, team, "level", kills, deaths, assists FROM (
              SELECT player, name, hero, hero_key, team, "level", kills, deaths, assists,
                     ROW_NUMBER() OVER (PARTITION BY player ORDER BY tick DESC) rn
              FROM players_v
            ) WHERE rn = 1 ORDER BY player
            """).forEach(row -> {
            ObjectNode p = arr.addObject();
            p.put("player", intOf(row.get("player")));
            putStr(p, "name", (String) row.get("name"));
            putStr(p, "hero", (String) row.get("hero"));
            putStr(p, "hero_key", (String) row.get("hero_key"));
            int team = intOf(row.get("team"));
            p.put("team", team);
            p.put("side", teamSide(team));
            p.put("level", longOf(row.get("level")));
            p.put("kills", longOf(row.get("kills")));
            p.put("deaths", longOf(row.get("deaths")));
            p.put("assists", longOf(row.get("assists")));
        });
    }

    private void addKills(Connection conn, ObjectNode root) throws Exception {
        ArrayNode arr = root.putArray("kills");
        queryMaps(conn, """
            SELECT t, attacker, target, attacker_key, target_key, attacker_team, target_team, x, y, networth, assists
            FROM combatlog_v WHERE type='DOTA_COMBATLOG_DEATH' AND target_hero
            ORDER BY t
            """).forEach(row -> {
            ObjectNode k = arr.addObject();
            k.put("t", round1(dbl(row.get("t"))));
            putStr(k, "killer", (String) row.get("attacker"));
            putStr(k, "killer_key", (String) row.get("attacker_key"));
            putStr(k, "victim", (String) row.get("target"));
            putStr(k, "victim_key", (String) row.get("target_key"));
            k.put("killer_team", row.get("attacker_team") == null ? 0 : intOf(row.get("attacker_team")));
            k.put("victim_team", intOf(row.get("target_team")));
            if (row.get("x") != null && row.get("y") != null) {
                ArrayNode loc = k.putArray("location");
                loc.add(round1(dbl(row.get("x"))));
                loc.add(round1(dbl(row.get("y"))));
            }
            if (row.get("networth") != null) {
                k.put("victim_networth", longOf(row.get("networth")));
            }
            if (row.get("assists") instanceof Object[] assistArr) {
                ArrayNode a = k.putArray("assist_players");
                for (Object o : assistArr) {
                    a.add(((Number) o).intValue());
                }
            }
        });
    }

    private void addTeamfights(Connection conn, ObjectNode root) throws Exception {
        String score = "dmg_events + " + WEIGHT_DEATH + " * deaths";
        List<double[]> episodes = new ArrayList<>();
        query(conn, """
            WITH act AS (
              SELECT FLOOR(t / %d) * %d AS b,
                     COUNT(*) FILTER (WHERE type='DOTA_COMBATLOG_DAMAGE' AND target_hero AND attacker LIKE 'npc_dota_hero_%%') AS dmg_events,
                     COUNT(*) FILTER (WHERE type='DOTA_COMBATLOG_DEATH' AND target_hero) AS deaths
              FROM combatlog_v
              WHERE t >= (SELECT MIN(t) FROM combatlog_v)
              GROUP BY 1
            ),
            scored AS (
              SELECT b, %s AS score,
                     (%s >= %f) AS active
              FROM act
            ),
            lagged AS (
              SELECT b, score, active, LAG(active) OVER (ORDER BY b) AS prev_active FROM scored
            ),
            islands AS (
              SELECT b, score, active,
                SUM(CASE WHEN active AND NOT COALESCE(prev_active, false) THEN 1 ELSE 0 END)
                  OVER (ORDER BY b) AS grp
              FROM lagged
            )
            SELECT grp, MIN(b) start_b, MAX(b) end_b, ROUND(SUM(score), 1) total_score,
                   SUM(CASE WHEN active THEN 1 ELSE 0 END) active_buckets
            FROM islands WHERE active
            GROUP BY grp ORDER BY start_b
            """.formatted(BUCKET_SEC, BUCKET_SEC, score, score, MIN_ACTIVE_SCORE))
            .forEach(row -> episodes.add(new double[]{
                dbl(row[1]), dbl(row[2]) + BUCKET_SEC
            }));

        ArrayNode arr = root.putArray("teamfights");
        int idx = 0;
        for (double[] ep : episodes) {
            ObjectNode tf = arr.addObject();
            tf.put("id", idx++);
            tf.put("start", round1(ep[0]));
            tf.put("end", round1(ep[1]));
            tf.put("duration", round1(ep[1] - ep[0]));

            List<Object[]> events = query(conn, """
                SELECT attacker_key, target_key, type, value
                FROM combatlog_v
                WHERE t >= %f AND t <= %f AND (
                      (type='DOTA_COMBATLOG_DAMAGE' AND target_hero AND attacker LIKE 'npc_dota_hero_%%') OR
                      (type='DOTA_COMBATLOG_DEATH' AND target_hero) OR
                      (type='DOTA_COMBATLOG_HEAL' AND target_hero AND attacker LIKE 'npc_dota_hero_%%'))
                """.formatted(ep[0], ep[1]));
            Set<String> participants = new LinkedHashSet<>();
            double heroDamage = 0;
            long deaths = 0;
            for (Object[] row : events) {
                String ak = (String) row[0];
                String tk = (String) row[1];
                String type = (String) row[2];
                double value = row[3] == null ? 0 : ((Number) row[3]).doubleValue();
                if (isHeroKey(ak)) {
                    participants.add(ak);
                }
                if (isHeroKey(tk)) {
                    participants.add(tk);
                }
                if ("DOTA_COMBATLOG_DAMAGE".equals(type)) {
                    heroDamage += value;
                } else if ("DOTA_COMBATLOG_DEATH".equals(type)) {
                    deaths++;
                }
            }
            tf.put("hero_damage", Math.round(heroDamage));
            tf.put("deaths", deaths);
            ArrayNode part = tf.putArray("participants");
            participants.stream().sorted().forEach(part::add);
        }
    }

    private void addGoldCurves(Connection conn, ObjectNode root) throws Exception {
        addCurves(conn, root, "gold_curves", "DOTA_COMBATLOG_GOLD", 30, "gold");
    }

    private void addXpCurves(Connection conn, ObjectNode root) throws Exception {
        addCurves(conn, root, "xp_curves", "DOTA_COMBATLOG_XP", 60, "xp");
    }

    private void addCurves(Connection conn, ObjectNode root, String key,
                           String type, int bucketSec, String valueKey) throws Exception {
        ArrayNode arr = root.putArray(key);
        List<Object[]> rows = query(conn, """
            WITH src AS (
              SELECT target_key AS hero, t, value FROM combatlog_v WHERE type='%s' AND value IS NOT NULL AND target_key IS NOT NULL
            ),
            cum AS (
              SELECT hero, t, SUM(value) OVER (PARTITION BY hero ORDER BY t) AS cumv FROM src
            )
            SELECT hero, FLOOR(t / %d) * %d AS b, MAX(cumv) AS v
            FROM cum GROUP BY hero, b ORDER BY hero, b
            """.formatted(type, bucketSec, bucketSec));
        String curHero = null;
        ArrayNode points = null;
        for (Object[] row : rows) {
            String hero = (String) row[0];
            if (!hero.equals(curHero)) {
                curHero = hero;
                ObjectNode cur = arr.addObject();
                putStr(cur, "hero", hero);
                points = cur.putArray("points");
            }
            ObjectNode pt = points.addObject();
            pt.put("t", round1(dbl(row[1]) + bucketSec / 2.0));
            pt.put(valueKey, longOf(row[2]));
        }
    }

    private void addItemTimeline(Connection conn, ObjectNode root) throws Exception {
        ArrayNode arr = root.putArray("item_timeline");
        List<Object[]> rows = query(conn, """
            SELECT target_key AS hero, value_name item, MIN(t) t
            FROM combatlog_v
            WHERE type='DOTA_COMBATLOG_PURCHASE' AND value_name IS NOT NULL AND target_key IS NOT NULL
            GROUP BY target_key, value_name ORDER BY target_key, t
            """);
        String curHero = null;
        ArrayNode items = null;
        for (Object[] row : rows) {
            String hero = (String) row[0];
            if (!hero.equals(curHero)) {
                curHero = hero;
                ObjectNode cur = arr.addObject();
                putStr(cur, "hero", hero);
                items = cur.putArray("items");
            }
            ObjectNode it = items.addObject();
            putStr(it, "item", (String) row[1]);
            it.put("t", round1(dbl(row[2])));
        }
    }

    private void persistTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("ATTACH '" + escape(dbFile()) + "' AS out (TYPE duckdb)");
            st.execute("CREATE OR REPLACE TABLE out.combatlog AS SELECT * FROM combatlog_v");
            st.execute("CREATE OR REPLACE TABLE out.players AS SELECT * FROM players_v");
            st.execute("CREATE OR REPLACE TABLE out.kills AS SELECT * FROM combatlog_v " +
                "WHERE type='DOTA_COMBATLOG_DEATH' AND target_hero");
            st.execute("CREATE OR REPLACE TABLE out.hero_damage AS SELECT * FROM combatlog_v " +
                "WHERE type='DOTA_COMBATLOG_DAMAGE' AND target_hero");
        }
    }

    // ------------------------------------------------------------------
    // query helpers
    // ------------------------------------------------------------------

    private Optional<Map<String, Object>> queryOne(Connection conn, String sql) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        List<String> cols = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                cols.add(md.getColumnLabel(i));
            }
            while (rs.next()) {
                Object[] row = new Object[cols.size()];
                for (int i = 0; i < cols.size(); i++) {
                    row[i] = unwrap(rs.getObject(i + 1));
                }
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < cols.size(); i++) {
            map.put(cols.get(i), rows.get(0)[i]);
        }
        return Optional.of(map);
    }

    private List<Map<String, Object>> queryMaps(Connection conn, String sql) throws Exception {
        List<Object[]> rows = query(conn, sql);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> cols = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                cols.add(md.getColumnLabel(i));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (int i = 0; i < cols.size(); i++) {
                map.put(cols.get(i), row[i]);
            }
            out.add(map);
        }
        return out;
    }

    private List<Object[]> query(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) {
                Object[] row = new Object[n];
                for (int i = 0; i < n; i++) {
                    row[i] = unwrap(rs.getObject(i + 1));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private static Object unwrap(Object v) {
        if (v instanceof java.sql.Array a) {
            try {
                return a.getArray();
            } catch (Exception e) {
                return null;
            }
        }
        return v;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    /** True when the stripped combat-log key refers to a player hero (not a creep/tower/bear/roshan). */
    private static boolean isHeroKey(String key) {
        return key != null && !key.isEmpty() && !key.contains("npc_dota_");
    }

    private static long longOf(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : -1;
    }

    private static String teamSide(int team) {
        return switch (team) {
            case 2 -> "radiant";
            case 3 -> "dire";
            default -> "unknown";
        };
    }

    private static void putStr(ObjectNode node, String field, String value) {
        if (value != null && !value.isEmpty()) {
            node.put(field, value);
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String escape(Path p) {
        return p.toString().replace("'", "''");
    }

    public Path metricsJson() {
        return combatLog.getParent().resolve("metrics.json");
    }

    public Path dbFile() {
        return combatLog.getParent().resolve("metrics.duckdb");
    }
}