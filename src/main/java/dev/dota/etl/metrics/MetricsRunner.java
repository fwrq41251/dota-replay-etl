package dev.dota.etl.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dota.etl.util.AtomicFiles;
import dev.dota.etl.util.BuildInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * Columns the metrics layer relies on for correct results. A missing column would silently
     * turn into NULL (skipped filters / zero sums), so the NDJSON inputs are validated up front
     * and rejected with a clear error instead of producing wrong-looking metrics.
     */
    private static final List<String> REQUIRED_COMBATLOG_COLUMNS = List.of(
        "t", "type", "attacker", "target", "attacker_hero", "target_hero",
        "attacker_team", "target_team", "value");
    private static final List<String> REQUIRED_PLAYERS_COLUMNS = List.of(
        "t", "tick", "player", "team", "hero", "name", "level", "kills", "deaths", "assists");

    private final Path combatLog;
    private final Path players;

    public MetricsRunner(Path combatLog, Path players) {
        this.combatLog = combatLog;
        this.players = players;
    }

    public ObjectNode run() throws Exception {
        ObjectNode metrics = MAPPER.createObjectNode();
        metrics.put("schema_version", BuildInfo.METRICS_SCHEMA_VERSION);
        metrics.put("etl_version", BuildInfo.version());
        metrics.put("generated_at", Instant.now().toString());
        ObjectNode parameters = metrics.putObject("parameters");
        parameters.put("teamfight_bucket_sec", BUCKET_SEC);
        parameters.put("teamfight_death_weight", WEIGHT_DEATH);
        parameters.put("teamfight_min_active_score", MIN_ACTIVE_SCORE);
        JsonNode match = readMatchJson();
        if (match.hasNonNull("source_replay_sha256")) {
            metrics.put("source_replay_sha256", match.path("source_replay_sha256").asText());
        }
        double timeOffset = match.path("game_start_time_raw").asDouble(0);
        Path dbTemp = AtomicFiles.createTempSibling(dbFile());
        Files.deleteIfExists(dbTemp);
        try {
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
                conn.createStatement().execute(
                    "CREATE VIEW combatlog AS SELECT * FROM read_ndjson('" + escape(combatLog) + "')");
                conn.createStatement().execute(
                    "CREATE VIEW players AS SELECT * FROM read_ndjson('" + escape(players) + "')");
                conn.createStatement().execute("""
                    CREATE TEMP TABLE hero_key_map AS
                    SELECT DISTINCT
                      lower(replace(replace(name, 'npc_dota_hero_', ''), '_', '')) AS norm,
                      replace(name, 'npc_dota_hero_', '') AS hero_key
                    FROM (
                      SELECT attacker AS name FROM combatlog WHERE attacker LIKE 'npc_dota_hero_%'
                      UNION
                      SELECT target AS name FROM combatlog WHERE target LIKE 'npc_dota_hero_%'
                    )
                    """);
                conn.createStatement().execute(("""
                    CREATE VIEW players_v AS SELECT * EXCLUDE(t), t AS raw_t, t - %f AS t,
                      COALESCE((SELECT hero_key FROM hero_key_map
                                WHERE norm = lower(replace(hero, '_', ''))),
                               lower(regexp_replace(hero, '([a-z])([A-Z])', '\\1_\\2', 'g'))) AS hero_key
                    FROM players
                    """).formatted(timeOffset));
                conn.createStatement().execute(("""
                    CREATE VIEW combatlog_v AS SELECT * EXCLUDE(t), t AS raw_t, t - %f AS t,
                      replace(target, 'npc_dota_hero_', '') AS target_key,
                      replace(attacker, 'npc_dota_hero_', '') AS attacker_key
                    FROM combatlog
                    """).formatted(timeOffset));

                validateInputSchema(conn);

                addSummary(conn, metrics, match, timeOffset);
                addRoster(conn, metrics);
                addKills(conn, metrics);
                addTeamfights(conn, metrics);
                addGoldCurves(conn, metrics);
                addXpCurves(conn, metrics);
                addItemTimeline(conn, metrics);
                addDamage(conn, metrics);
                persistTables(conn, dbTemp);
            }
            AtomicFiles.replace(dbTemp, dbFile());
        } finally {
            Files.deleteIfExists(dbTemp);
        }
        return metrics;
    }

    // ------------------------------------------------------------------
    // metric sections
    // ------------------------------------------------------------------

    private void addSummary(Connection conn, ObjectNode root, JsonNode match, double timeOffset) throws Exception {
        ObjectNode s = root.putObject("summary");
        double duration = match.path("game_duration_sec").asDouble(0);
        if (duration <= 0) {
            Optional<Map<String, Object>> bounds = queryOne(conn, "SELECT MIN(t) min_t, MAX(t) max_t FROM combatlog_v");
            if (bounds.isPresent()) {
                duration = dbl(bounds.get().get("max_t")) - dbl(bounds.get().get("min_t"));
            }
        }
        s.put("game_start_sec", 0);
        s.put("game_end_sec", round1(duration));
        s.put("duration_sec", round1(duration));
        s.put("raw_time_offset_sec", round1(timeOffset));
        int winner = match.path("winner_team").asInt(0);
        if (winner == 2 || winner == 3) {
            s.put("winner_team", winner);
            s.put("winner_side", teamSide(winner));
        }
        ArrayNode teamKills = s.putArray("team_kills");
        if (match.has("radiant_score") && match.has("dire_score")) {
            addTeamScore(teamKills, 2, match.path("radiant_score").asLong());
            addTeamScore(teamKills, 3, match.path("dire_score").asLong());
        } else {
            queryMaps(conn, """
                WITH final AS (
                  SELECT player, team, deaths,
                         ROW_NUMBER() OVER (PARTITION BY player ORDER BY tick DESC) rn
                  FROM players_v WHERE team IN (2, 3)
                )
                SELECT CASE team WHEN 2 THEN 3 ELSE 2 END team, SUM(deaths) kills
                FROM final WHERE rn=1 GROUP BY team ORDER BY 1
                """)
                .forEach(row -> {
                ObjectNode t = teamKills.addObject();
                int team = intOf(row.get("team"));
                t.put("team", team);
                t.put("side", teamSide(team));
                t.put("kills", longOf(row.get("kills")));
            });
        }
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

    private static void addTeamScore(ArrayNode scores, int team, long kills) {
        ObjectNode score = scores.addObject();
        score.put("team", team);
        score.put("side", teamSide(team));
        score.put("kills", kills);
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
            SELECT t, raw_t, attacker, target, attacker_key, target_key, attacker_team, target_team, x, y, networth, assists
            FROM combatlog_v WHERE type='DOTA_COMBATLOG_DEATH' AND target_hero
            ORDER BY t
            """).forEach(row -> {
            ObjectNode k = arr.addObject();
            k.put("t", round1(dbl(row.get("t"))));
            k.put("raw_t", round1(dbl(row.get("raw_t"))));
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
        ArrayNode arr = root.putArray("teamfights");
        for (Object[] row : query(conn, teamfightEpisodesSql())) {
            int id = intOf(row[0]);
            double start = dbl(row[1]);
            double end = dbl(row[2]);
            ObjectNode tf = arr.addObject();
            tf.put("id", id);
            tf.put("start", round1(start));
            tf.put("end", round1(end));
            tf.put("duration", round1(end - start));
            tf.put("hero_damage", Math.round(dbl(row[3])));
            tf.put("deaths", longOf(row[4]));

            List<Object[]> events = query(conn, teamfightEventsSql().formatted(start, end));
            Set<String> participants = new LinkedHashSet<>();
            Map<String, double[]> playerStats = new LinkedHashMap<>();
            for (Object[] event : events) {
                String ak = (String) event[0];
                String tk = (String) event[1];
                String type = (String) event[2];
                double value = event[3] == null ? 0 : ((Number) event[3]).doubleValue();
                if (isHeroKey(ak)) {
                    participants.add(ak);
                }
                if (isHeroKey(tk)) {
                    participants.add(tk);
                }
                if ("DOTA_COMBATLOG_DAMAGE".equals(type)) {
                    if (isHeroKey(ak)) {
                        playerStats.computeIfAbsent(ak, key -> new double[4])[0] += value;
                    }
                    if (isHeroKey(tk)) {
                        playerStats.computeIfAbsent(tk, key -> new double[4])[1] += value;
                    }
                } else if ("DOTA_COMBATLOG_DEATH".equals(type)) {
                    if (isHeroKey(ak)) {
                        playerStats.computeIfAbsent(ak, key -> new double[4])[2]++;
                    }
                    if (isHeroKey(tk)) {
                        playerStats.computeIfAbsent(tk, key -> new double[4])[3]++;
                    }
                }
            }
            ArrayNode part = tf.putArray("participants");
            participants.stream().sorted().forEach(part::add);
            ObjectNode stats = tf.putObject("player_stats");
            playerStats.forEach((hero, values) -> {
                ObjectNode p = stats.putObject(hero);
                p.put("damage_dealt", Math.round(values[0]));
                p.put("damage_taken", Math.round(values[1]));
                p.put("kills", (long) values[2]);
                p.put("deaths", (long) values[3]);
            });
        }
    }

    /** Episodes of elevated combat activity; the same SQL is used to populate metrics.duckdb. */
    private static String teamfightEpisodesSql() {
        String score = "dmg_events + " + WEIGHT_DEATH + " * deaths";
        return """
            WITH act AS (
              SELECT FLOOR(t / {BUCKET}) * {BUCKET} AS b,
                     COUNT(*) FILTER (WHERE type='DOTA_COMBATLOG_DAMAGE' AND target_hero AND attacker LIKE 'npc_dota_hero_%') AS dmg_events,
                     COUNT(*) FILTER (WHERE type='DOTA_COMBATLOG_DEATH' AND target_hero) AS deaths
              FROM combatlog_v
              WHERE t >= (SELECT MIN(t) FROM combatlog_v)
              GROUP BY 1
            ),
            scored AS (
              SELECT b, {SCORE} AS score, ({SCORE} >= {MIN_ACTIVE}) AS active
              FROM act
            ),
            lagged AS (
              SELECT b, score, active,
                     LAG(b) OVER (ORDER BY b) AS prev_b,
                     LAG(active) OVER (ORDER BY b) AS prev_active
              FROM scored
            ),
            islands AS (
              SELECT b, score, active,
                SUM(CASE WHEN active AND (NOT COALESCE(prev_active, false) OR b - prev_b > {BUCKET}) THEN 1 ELSE 0 END)
                  OVER (ORDER BY b) AS grp
              FROM lagged
            ),
            episodes AS (
              SELECT grp, MIN(b) AS start_b, MAX(b) + {BUCKET} AS end_b
              FROM islands WHERE active GROUP BY grp
            )
            SELECT ROW_NUMBER() OVER (ORDER BY start_b) - 1 AS id,
                   start_b AS start, end_b AS end,
                   COALESCE(SUM(CASE WHEN c.type='DOTA_COMBATLOG_DAMAGE' AND c.target_hero AND c.attacker LIKE 'npc_dota_hero_%' THEN c.value END), 0) AS hero_damage,
                   COUNT(*) FILTER (WHERE c.type='DOTA_COMBATLOG_DEATH' AND c.target_hero) AS deaths
            FROM episodes e JOIN combatlog_v c ON c.t >= e.start_b AND c.t <= e.end_b
            GROUP BY e.grp, start_b, end_b ORDER BY start_b
            """.replace("{BUCKET}", String.valueOf(BUCKET_SEC))
            .replace("{MIN_ACTIVE}", String.valueOf(MIN_ACTIVE_SCORE))
            .replace("{SCORE}", score);
    }

    /** Per-episode event rows used to assemble participants / per-player damage and kills. */
    private static String teamfightEventsSql() {
        return """
            SELECT attacker_key, target_key, type, value
            FROM combatlog_v
            WHERE t >= %f AND t <= %f AND (
                  (type='DOTA_COMBATLOG_DAMAGE' AND target_hero AND attacker LIKE 'npc_dota_hero_%%') OR
                  (type='DOTA_COMBATLOG_DEATH' AND target_hero) OR
                  (type='DOTA_COMBATLOG_HEAL' AND target_hero AND attacker LIKE 'npc_dota_hero_%%'))
            """;
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
        List<Object[]> rows = query(conn, curveSql(type, bucketSec));
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

    /** Cumulative GOLD/XP per hero bucketed every {@code bucketSec} seconds. */
    private static String curveSql(String type, int bucketSec) {
        return """
            WITH src AS (
              SELECT target_key AS hero, t, value FROM combatlog_v WHERE type='%s' AND value IS NOT NULL AND target_key IS NOT NULL
            ),
            cum AS (
              SELECT hero, t, SUM(value) OVER (PARTITION BY hero ORDER BY t) AS cumv FROM src
            )
            SELECT hero, FLOOR(t / %d) * %d AS t_bucket, MAX(cumv) AS value
            FROM cum GROUP BY hero, t_bucket ORDER BY hero, t_bucket
            """.formatted(type, bucketSec, bucketSec);
    }

    private void addItemTimeline(Connection conn, ObjectNode root) throws Exception {
        ArrayNode arr = root.putArray("item_timeline");
        List<Object[]> rows = query(conn, itemTimelineSql());
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

    private static String itemTimelineSql() {
        return """
            SELECT target_key AS hero, value_name AS item, t
            FROM combatlog_v
            WHERE type='DOTA_COMBATLOG_PURCHASE' AND value_name IS NOT NULL AND target_key IS NOT NULL
            ORDER BY target_key, t
            """;
    }

    private void addDamage(Connection conn, ObjectNode root) throws Exception {
        ArrayNode arr = root.putArray("damage");
        Map<String, ObjectNode> byHero = new LinkedHashMap<>();
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (Object[] row : query(conn, damagePerMinuteSql())) {
            String hero = (String) row[0];
            ObjectNode node = byHero.get(hero);
            if (node == null) {
                node = arr.addObject();
                putStr(node, "hero", hero);
                node.put("dealt_total", 0L);
                node.putArray("per_minute");
                byHero.put(hero, node);
                totals.put(hero, new long[]{0L});
            }
            long v = longOf(row[2]);
            totals.get(hero)[0] += v;
            ObjectNode pt = ((ArrayNode) node.get("per_minute")).addObject();
            pt.put("min", longOf(row[1]));
            pt.put("dealt", v);
        }
        for (Object[] row : query(conn, damageTakenSql())) {
            String hero = (String) row[0];
            ObjectNode node = byHero.get(hero);
            if (node == null) {
                node = arr.addObject();
                putStr(node, "hero", hero);
                node.put("dealt_total", 0L);
                node.putArray("per_minute");
                byHero.put(hero, node);
                totals.put(hero, new long[]{0L});
            }
            node.put("taken_total", longOf(row[1]));
        }
        byHero.forEach((hero, node) -> node.put("dealt_total", totals.get(hero)[0]));
    }

    private static String damagePerMinuteSql() {
        return """
            SELECT attacker_key AS hero, FLOOR(t / 60) AS minute, SUM(value) AS dealt
            FROM combatlog_v
            WHERE type='DOTA_COMBATLOG_DAMAGE' AND target_hero
              AND attacker LIKE 'npc_dota_hero_%' AND attacker_key IS NOT NULL
            GROUP BY attacker_key, minute ORDER BY attacker_key, minute
            """;
    }

    private static String damageTakenSql() {
        return """
            SELECT target_key AS hero, SUM(value) AS taken_total
            FROM combatlog_v
            WHERE type='DOTA_COMBATLOG_DAMAGE' AND target_hero AND target_key IS NOT NULL
            GROUP BY target_key
            """;
    }

    /** Per-hero dealt/taken totals, merged over both directions (mirrors the damage[] JSON section). */
    private static String damageTotalsSql() {
        return """
            SELECT COALESCE(d.hero, t.hero) AS hero,
                   COALESCE(d.dealt_total, 0) AS dealt_total,
                   COALESCE(t.taken_total, 0) AS taken_total
            FROM (SELECT attacker_key AS hero, SUM(value) AS dealt_total
                  FROM combatlog_v
                  WHERE type='DOTA_COMBATLOG_DAMAGE' AND target_hero
                    AND attacker LIKE 'npc_dota_hero_%' AND attacker_key IS NOT NULL
                  GROUP BY attacker_key) d
            FULL OUTER JOIN (SELECT target_key AS hero, SUM(value) AS taken_total
                  FROM combatlog_v
                  WHERE type='DOTA_COMBATLOG_DAMAGE' AND target_hero AND target_key IS NOT NULL
                  GROUP BY target_key) t ON d.hero = t.hero
            ORDER BY hero
            """;
    }

    private void persistTables(Connection conn, Path output) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("ATTACH '" + escape(output) + "' AS out (TYPE duckdb)");
            st.execute("CREATE OR REPLACE TABLE out.combatlog AS SELECT * FROM combatlog_v");
            st.execute("CREATE OR REPLACE TABLE out.players AS SELECT * FROM players_v");
            st.execute("CREATE OR REPLACE TABLE out.kills AS SELECT * FROM combatlog_v " +
                "WHERE type='DOTA_COMBATLOG_DEATH' AND target_hero");
            st.execute("CREATE OR REPLACE TABLE out.hero_damage AS SELECT * FROM combatlog_v " +
                "WHERE type='DOTA_COMBATLOG_DAMAGE' AND target_hero");
            // the same SQL that drives the metrics.json sections, so ad-hoc SQL sees the real metrics
            st.execute("CREATE OR REPLACE TABLE out.gold_curves AS " + curveSql("DOTA_COMBATLOG_GOLD", 30));
            st.execute("CREATE OR REPLACE TABLE out.xp_curves AS " + curveSql("DOTA_COMBATLOG_XP", 60));
            st.execute("CREATE OR REPLACE TABLE out.item_timeline AS " + itemTimelineSql());
            st.execute("CREATE OR REPLACE TABLE out.damage_per_minute AS " + damagePerMinuteSql());
            st.execute("CREATE OR REPLACE TABLE out.damage AS " + damageTotalsSql());
            st.execute("CREATE OR REPLACE TABLE out.teamfights AS " + teamfightEpisodesSql());
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
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> cols = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                cols.add(md.getColumnLabel(i));
            }
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> map = new java.util.LinkedHashMap<>();
                for (int i = 0; i < cols.size(); i++) {
                    map.put(cols.get(i), unwrap(rs.getObject(i + 1)));
                }
                out.add(map);
            }
            return out;
        }
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

    private void validateInputSchema(Connection conn) throws Exception {
        validateColumns("combatlog.ndjson", columnsOf(conn, "combatlog"), REQUIRED_COMBATLOG_COLUMNS);
        validateColumns("players.ndjson", columnsOf(conn, "players"), REQUIRED_PLAYERS_COLUMNS);
    }

    /** Union of field names across the whole NDJSON file, as materialised by read_ndjson. */
    private static Set<String> columnsOf(Connection conn, String table) throws Exception {
        Set<String> cols = new LinkedHashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                cols.add(md.getColumnLabel(i));
            }
        }
        return cols;
    }

    private static void validateColumns(String source, Set<String> present, List<String> required) {
        List<String> missing = new ArrayList<>();
        for (String column : required) {
            if (!present.contains(column)) {
                missing.add(column);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(source + " is missing required column(s): "
                + String.join(", ", missing)
                + " — the file may come from an older ETL schema_version; re-run `analyze` to regenerate it");
        }
    }

    public Path metricsJson() {
        return combatLog.getParent().resolve("metrics.json");
    }

    public Path dbFile() {
        return combatLog.getParent().resolve("metrics.duckdb");
    }

    private JsonNode readMatchJson() throws Exception {
        Path match = combatLog.getParent().resolve("match.json");
        return Files.exists(match) ? MAPPER.readTree(Files.readString(match)) : MAPPER.createObjectNode();
    }
}
