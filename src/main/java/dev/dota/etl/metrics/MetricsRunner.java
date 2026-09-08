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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static dev.dota.etl.metrics.MetricQueries.*;

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

    /**
     * Columns the metrics layer relies on for correct results. A missing column would silently
     * turn into NULL (skipped filters / zero sums), so the NDJSON inputs are validated up front
     * and rejected with a clear error instead of producing wrong-looking metrics.
     */
    private static final List<String> REQUIRED_COMBATLOG_COLUMNS = List.of(
        "t", "type", "attacker", "target", "attacker_hero", "target_hero",
        "attacker_team", "target_team", "value");
    private static final List<String> REQUIRED_PLAYERS_COLUMNS = List.of(
        "t", "tick", "player", "team", "hero", "name", "level", "kills", "deaths", "assists",
         "total_earned_gold", "x", "y");

    private final Path combatLog;
    private final Path players;
    private final Path wards;

    public MetricsRunner(Path combatLog, Path players) {
        this.combatLog = combatLog;
        this.players = players;
        this.wards = combatLog.getParent().resolve("wards.ndjson");
    }

    public ObjectNode run() throws Exception {
        ObjectNode metrics = MAPPER.createObjectNode();
        metrics.put("schema_version", BuildInfo.METRICS_SCHEMA_VERSION);
        metrics.put("etl_version", BuildInfo.version());
        metrics.put("generated_at", Instant.now().toString());
        ObjectNode semantics = metrics.putObject("semantics");
        semantics.put("gold_curves", "signed_combatlog_gold_sum_not_income_or_networth");
        semantics.put("income", "players.total_earned_gold; farm_curves uses 60-second bucket maxima");
        semantics.put("teamfights", "global_activity_windows_not_spatial_fights");
        semantics.put("window_economy", "signed_events_in_window_including_objectives_and_spending_not_causal_bounty");
        semantics.put("items", "combatlog_PURCHASE_not_delivery_or_availability");
        semantics.put("kills", "scored_death_and_unknown; excludes verified aegis_respawn and illusion_death");
        semantics.put("attribution", "roster_team_match or verified illusion source; unknown excluded from personal dealt damage");
        ObjectNode parameters = metrics.putObject("parameters");
        parameters.put("teamfight_bucket_sec", BUCKET_SEC);
        parameters.put("teamfight_death_weight", WEIGHT_DEATH);
        parameters.put("teamfight_min_active_score", MIN_ACTIVE_SCORE);
        parameters.put("lane_window_sec", LANE_WINDOW_SEC);
        parameters.put("lane_min_samples", MIN_LANE_SAMPLES);
        parameters.put("kill_gold_window_sec", KILL_GOLD_WINDOW_SEC);
        parameters.put("kill_followup_window_sec", KILL_FOLLOWUP_WINDOW_SEC);
        parameters.put("kill_location_max_age_sec", KILL_LOCATION_MAX_AGE_SEC);
        parameters.put("death_sample_before_after_sec", 5);
        parameters.put("aegis_respawn_max_sec", 10);
        parameters.put("death_incident_before_sec", DEATH_INCIDENT_BEFORE_SEC);
        parameters.put("death_incident_after_sec", DEATH_INCIDENT_AFTER_SEC);
        parameters.put("death_incident_nearby_radius", DEATH_INCIDENT_NEARBY_RADIUS);
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
                createWardsView(conn);
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
                String optionalFarmColumns = "";
                Set<String> playerColumns = columnsOf(conn, "players");
                for (String column : List.of("last_hits", "denies")) {
                    if (!playerColumns.contains(column)) optionalFarmColumns += ", NULL::BIGINT AS " + column;
                }
                conn.createStatement().execute(sql("""
                    CREATE VIEW players_v AS SELECT * EXCLUDE(t), t AS raw_t, t - {} AS t,
                      COALESCE((SELECT hero_key FROM hero_key_map
                                WHERE norm = lower(replace(hero, '_', ''))),
                               lower(regexp_replace(hero, '([a-z])([A-Z])', '\\1_\\2', 'g'))) AS hero_key
                    {} FROM players
                    """, timeOffset, optionalFarmColumns));
                conn.createStatement().execute(sql("""
                    CREATE VIEW combatlog_units AS SELECT * EXCLUDE(t), t AS raw_t, t - {} AS t,
                      replace(target, 'npc_dota_hero_', '') AS target_key,
                      replace(attacker, 'npc_dota_hero_', '') AS attacker_key
                    FROM combatlog
                    """, timeOffset));
                conn.createStatement().execute(sql("""
                    CREATE VIEW wards_v AS SELECT * EXCLUDE(placed_t, removed_t),
                      placed_t AS raw_placed_t, placed_t - {} AS placed_t,
                      removed_t AS raw_removed_t, removed_t - {} AS removed_t
                    FROM wards
                    """, timeOffset, timeOffset));

                validateInputSchema(conn);

                createHeroTeam(conn);
                createAttribution(conn);
                createHeroKills(conn);
                addSummary(conn, metrics, match, timeOffset);
                addRoster(conn, metrics);
                new VisionMetricsBuilder(conn).addTo(metrics);
                addKills(conn, metrics);
                new DeathIncidentBuilder(conn).addTo(metrics);
                addTeamfights(conn, metrics);
                new LocalFightBuilder(conn).addTo(metrics, timeOffset);
                addObjectives(conn, metrics);
                addFarmCurves(conn, metrics);
                addGoldCurves(conn, metrics);
                addXpCurves(conn, metrics);
                addItemTimeline(conn, metrics);
                addDamage(conn, metrics);
                new EquipmentBuilder(conn).addTo(metrics, timeOffset, match.path("build_num").asInt(-1));
                MetricsDatabaseWriter.persist(conn, dbTemp);
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
        s.put("team_kills_source", match.hasNonNull("radiant_score") && match.hasNonNull("dire_score")
            ? "official_team_counters" : "final_player_deaths_fallback");
        if (match.hasNonNull("radiant_score") && match.hasNonNull("dire_score")) {
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
        s.set("player_kills_by_team", MAPPER.valueToTree(queryMaps(conn,
            "SELECT team, SUM(kills) kills FROM (" + rosterSql() + ") GROUP BY team ORDER BY team")));
        s.set("death_event_counts", MAPPER.valueToTree(queryMaps(conn,
            "SELECT death_class, COUNT(*) events FROM hero_death_events GROUP BY death_class ORDER BY death_class")));
        s.set("non_player_last_hits", MAPPER.valueToTree(queryMaps(conn, """
            SELECT attacker AS unit, attacker_team AS team, COUNT(*) deaths
            FROM hero_kills WHERE NOT COALESCE(attacker_hero, false)
            GROUP BY attacker, attacker_team ORDER BY attacker
            """)));
        s.set("damage_attribution", MAPPER.valueToTree(queryMaps(conn, """
            SELECT attribution_source, COUNT(*) events, SUM(value) damage
            FROM combatlog_v WHERE type='DOTA_COMBATLOG_DAMAGE' AND target_player_key IS NOT NULL
            GROUP BY attribution_source ORDER BY attribution_source
            """)));
        queryOne(conn, "SELECT t, attacker, target FROM hero_kills ORDER BY t LIMIT 1")
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
        Map<Integer, String[]> lanes = computeLanes(conn);
        ArrayNode arr = root.putArray("roster");
        queryMaps(conn, rosterSql()).forEach(row -> {
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
            String[] lane = lanes.get(intOf(row.get("player")));
            if (lane != null) {
                p.put("lane", lane[0]);
                p.put("lane_confidence", Integer.parseInt(lane[1]));
            }
        });
    }

    /**
     * Lane inference from the first 90 seconds after the horn: each player's
     * samples are assigned to the top (x &lt; 0, y &gt; 0), bottom (x &gt; 0, y &lt; 0) or mid (x,y same sign)
     * map region; the region holding the majority of samples wins. Fountain trips are excluded and the
     * majority share is reported as a confidence percentage. Purely inferential (labelled as such in the
     * reports) — the map layout is validated empirically (radiant fountain &asymp; (-6700, -6700)).
     */
    private Map<Integer, String[]> computeLanes(Connection conn) throws Exception {
        Map<Integer, String[]> lanes = new LinkedHashMap<>();
        for (Map<String, Object> row : queryMaps(conn, lanePersistSql())) {
            lanes.put(intOf(row.get("player")),
                new String[]{(String) row.get("lane"), String.valueOf(longOf(row.get("confidence")))});
        }
        return lanes;
    }

    private void addKills(Connection conn, ObjectNode root) throws Exception {
        root.set("hero_death_events", MAPPER.valueToTree(queryMaps(conn, """
            SELECT kill_id, t, raw_t, attacker AS attacker_unit, credited_attacker_key, attribution_source,
                   target_key AS victim_key, death_class, before_t, after_t, respawn_t,
                   deaths_before, deaths_after, deaths_respawn, before_hp, after_hp, respawn_hp,
                   before_has_aegis, after_has_aegis, respawn_has_aegis
            FROM hero_death_events ORDER BY kill_id
            """)));
        ArrayNode arr = root.putArray("kills");
        // batch-computed death costs: one query for every kill instead of two per kill
        Map<Integer, ObjectNode> costByKill = new HashMap<>();
        for (Map<String, Object> cost : queryMaps(conn, deathCostBatchSql())) {
            ObjectNode c = MAPPER.createObjectNode();
            c.put("killer_team_gold", longOf(cost.get("gold")));
            c.put("killer_team_xp", longOf(cost.get("xp")));
            costByKill.put(intOf(cost.get("kill_id")), c);
        }
        Map<Integer, ObjectNode> followByKill = new HashMap<>();
        for (Map<String, Object> obj : queryMaps(conn, concededObjectiveBatchSql())) {
            ObjectNode co = MAPPER.createObjectNode();
            co.put("t", round1(dbl(obj.get("obj_t"))));
            putStr(co, "target", (String) obj.get("obj_target"));
            putStr(co, "target_key", (String) obj.get("obj_target_key"));
            putStr(co, "kind", (String) obj.get("obj_kind"));
            followByKill.put(intOf(obj.get("kill_id")), co);
        }
        for (Map<String, Object> row : queryMaps(conn, """
            SELECT kill_id, t, raw_t, attacker, target, attacker_key, target_key,
                   attacker_team, target_team, x, y, location_source, location_age_sec,
                   networth, assists, credited_attacker_key, attribution_source, death_class
            FROM hero_kills ORDER BY kill_id
            """)) {
            ObjectNode k = arr.addObject();
            int killId = intOf(row.get("kill_id"));
            double t = dbl(row.get("t"));
            String victimKey = (String) row.get("target_key");
            k.put("kill_id", killId);
            k.put("t", round1(t));
            k.put("raw_t", round1(dbl(row.get("raw_t"))));
            String credited = (String) row.get("credited_attacker_key");
            putStr(k, "attacker_unit", (String) row.get("attacker"));
            putStr(k, "killer", credited == null ? "unknown (unit: " + row.get("attacker") + ")" : credited);
            putStr(k, "killer_key", credited);
            putStr(k, "attribution_source", (String) row.get("attribution_source"));
            putStr(k, "death_class", (String) row.get("death_class"));
            putStr(k, "victim", (String) row.get("target"));
            putStr(k, "victim_key", victimKey);
            int killerTeam = row.get("attacker_team") == null ? 0 : intOf(row.get("attacker_team"));
            k.put("killer_team", killerTeam);
            k.put("victim_team", intOf(row.get("target_team")));
            if (row.get("x") != null && row.get("y") != null) {
                ArrayNode loc = k.putArray("location");
                loc.add(round1(dbl(row.get("x"))));
                loc.add(round1(dbl(row.get("y"))));
                putStr(k, "location_source", (String) row.get("location_source"));
                if (row.get("location_age_sec") != null) {
                    k.put("location_age_sec", round1(dbl(row.get("location_age_sec"))));
                }
            }
            if (row.get("networth") != null) {
                k.put("raw_networth", longOf(row.get("networth")));
            }
            if (row.get("assists") instanceof Object[] assistArr) {
                ArrayNode a = k.putArray("assist_players");
                for (Object o : assistArr) {
                    a.add(((Number) o).intValue());
                }
            }
            if (killerTeam == 2 || killerTeam == 3) {
                ObjectNode cost = costByKill.get(killId);
                if (cost != null) {
                    k.put("killer_team_gold", cost.path("killer_team_gold").asLong());
                    k.put("killer_team_xp", cost.path("killer_team_xp").asLong());
                }
                ObjectNode follow = followByKill.get(killId);
                if (follow != null) {
                    k.set("conceded_objective", follow);
                }
            }
        }
    }

    /** Stable deterministic id for every hero death, shared by JSON and persisted derived tables. */
    private void createHeroKills(Connection conn) throws Exception {
        // Only positive evidence excludes a death. Sparse/contradictory samples remain unknown.
        conn.createStatement().execute("""
            CREATE TEMP TABLE hero_death_events AS
            WITH deaths AS (
              SELECT ROW_NUMBER() OVER (ORDER BY t, target_key, attacker_key, event_id) - 1 kill_id, *
              FROM combatlog_v WHERE type='DOTA_COMBATLOG_DEATH' AND target_hero
            )
            SELECT d.*, b.t before_t, a.t after_t, r.t respawn_t,
                   b.deaths deaths_before, a.deaths deaths_after, r.deaths deaths_respawn,
                   b.hp before_hp, a.hp after_hp, r.hp respawn_hp,
                   b.has_aegis before_has_aegis, a.has_aegis after_has_aegis, r.has_aegis respawn_has_aegis,
                   CASE WHEN target_is_illusion THEN 'illusion_death'
                        WHEN a.deaths = b.deaths + 1
                          AND (SELECT COUNT(*) FROM deaths x WHERE x.target_key=d.target_key
                               AND x.target_team IS NOT DISTINCT FROM d.target_team AND x.target_is_illusion IS NOT TRUE
                               AND x.t>b.t AND x.t<=a.t)=1 THEN 'scored_death'
                        WHEN b.hp > 0 AND b.has_aegis AND a.hp = 0 AND NOT a.has_aegis
                          AND a.deaths = b.deaths AND r.deaths = b.deaths AND NOT r.has_aegis
                          AND (SELECT COUNT(*) FROM deaths x WHERE x.target_key=d.target_key
                               AND x.target_team IS NOT DISTINCT FROM d.target_team AND x.target_is_illusion IS NOT TRUE
                               AND x.t>b.t AND x.t<=r.t)=1
                          THEN 'aegis_respawn'
                        ELSE 'unknown' END death_class
            FROM deaths d
            LEFT JOIN LATERAL (
              SELECT * FROM death_samples p WHERE p.hero_key=d.target_key AND p.team=d.target_team
                AND p.t < d.t AND p.t >= d.t-5 AND p.hp>0 ORDER BY p.t DESC LIMIT 1
            ) b ON true
            LEFT JOIN LATERAL (
              SELECT * FROM death_samples p WHERE p.hero_key=d.target_key AND p.team=d.target_team
                AND p.t >= d.t AND p.t <= d.t+5 ORDER BY p.t LIMIT 1
            ) a ON true
            LEFT JOIN LATERAL (
              SELECT * FROM death_samples p WHERE p.hero_key=d.target_key AND p.team=d.target_team
                AND p.t > a.t AND p.t <= d.t+10 AND p.hp>0 ORDER BY p.t LIMIT 1
            ) r ON true
            """);
        conn.createStatement().execute("""
            CREATE VIEW activity_v AS SELECT c.* FROM combatlog_v c
            WHERE NOT EXISTS (SELECT 1 FROM hero_death_events d WHERE d.event_id=c.event_id
                              AND d.death_class IN ('aegis_respawn', 'illusion_death'))
            """);
        conn.createStatement().execute(sql("""
            CREATE TEMP TABLE hero_kills AS
            WITH deaths AS (
              SELECT * FROM hero_death_events
              WHERE death_class IN ('scored_death', 'unknown')
            )
            SELECT d.* EXCLUDE (x, y),
                   COALESCE(d.x, p.x) AS x,
                   COALESCE(d.y, p.y) AS y,
                   CASE WHEN d.x IS NOT NULL AND d.y IS NOT NULL THEN 'combatlog'
                        WHEN p.x IS NOT NULL AND p.y IS NOT NULL THEN 'player_sample'
                   END AS location_source,
                   CASE WHEN d.x IS NULL OR d.y IS NULL THEN d.t - p.t END AS location_age_sec
            FROM deaths d
            LEFT JOIN LATERAL (
              SELECT ps.t, ps.x, ps.y
              FROM players_v ps
              WHERE ps.hero_key = d.target_key
                AND ps.x IS NOT NULL AND ps.y IS NOT NULL
                AND ps.x <> 0 AND ps.y <> 0
                AND ps.t <= d.t AND d.t - ps.t <= {}
              ORDER BY ps.t DESC LIMIT 1
            ) p ON true
            """, KILL_LOCATION_MAX_AGE_SEC));
    }

    /** Attribution is deliberately narrower than damage_source: only verified Dark Seer copies. */
    private void createAttribution(Connection conn) throws Exception {
        Set<String> columns = columnsOf(conn, "combatlog_units");
        String illusion = columns.contains("attacker_illusion") ? "c.attacker_illusion" : "NULL::BOOLEAN";
        String targetIllusion = columns.contains("target_illusion") ? "c.target_illusion" : "NULL::BOOLEAN";
        String source = columns.contains("damage_source") ? "c.damage_source" : "NULL::VARCHAR";
        conn.createStatement().execute(sql("""
            CREATE TEMP TABLE combatlog_v AS
            SELECT ROW_NUMBER() OVER (ORDER BY c.t,c.target,c.attacker,c.type,to_json(c)) event_id, c.*, {} AS target_is_illusion,
                   CASE WHEN {} AND c.attacker_hero AND h.team <> c.attacker_team
                          AND {} = 'npc_dota_hero_dark_seer' AND ds.team=c.attacker_team
                          THEN 'dark_seer'
                        WHEN {} IS FALSE AND c.attacker_hero AND h.team=c.attacker_team THEN h.hero_key
                        WHEN {} IS FALSE AND c.attacker_team IS NULL
                          AND c.type IN ('DOTA_COMBATLOG_ABILITY', 'DOTA_COMBATLOG_ITEM') THEN h.hero_key
                        WHEN {} AND c.attacker_hero AND h.team=c.attacker_team AND {}=c.attacker THEN h.hero_key
                   END AS credited_attacker_key,
                   CASE WHEN credited_attacker_key IS NULL THEN 'unknown'
                        WHEN {} THEN 'verified_illusion_source'
                        WHEN c.attacker_team IS NULL THEN 'hero_action_identity'
                        ELSE 'roster_team_match' END attribution_source,
                   CASE WHEN {} IS FALSE AND c.target_hero AND v.team=c.target_team THEN v.hero_key END target_player_key
            FROM combatlog_units c
            LEFT JOIN hero_team h ON h.hero_key=c.attacker_key
            LEFT JOIN hero_team v ON v.hero_key=c.target_key
            LEFT JOIN hero_team ds ON ds.hero_key='dark_seer'
            """, targetIllusion, illusion, source, illusion, illusion, illusion, source, illusion, targetIllusion));
        conn.createStatement().execute("""
            CREATE TEMP TABLE death_samples AS
            SELECT *, contains(CAST(json_extract(to_json(p), '$.items') AS VARCHAR), '"item_aegis"') has_aegis
            FROM players_v p
            """);
    }

    /** Latest team per hero key (teams 2/3 only); shared by economy and death-cost attribution. */
    private void createHeroTeam(Connection conn) throws Exception {
        conn.createStatement().execute("""
            CREATE TEMP TABLE hero_team AS
            SELECT hero_key, team, player FROM (
              SELECT hero_key, team, player,
                     ROW_NUMBER() OVER (PARTITION BY hero_key ORDER BY tick DESC) rn
              FROM players_v WHERE hero_key IS NOT NULL AND team IN (2, 3)
            ) WHERE rn = 1
            """);
    }

    private void addTeamfights(Connection conn, ObjectNode root) throws Exception {
        // shared with persistTables so the persisted tables mirror the JSON exactly
        conn.createStatement().execute("CREATE TEMP TABLE tf_episodes AS " + teamfightEpisodesSql());
        conn.createStatement().execute("CREATE TEMP TABLE tf_events AS " + teamfightEventsBatchSql());
        conn.createStatement().execute("CREATE TEMP TABLE tf_economy AS " + teamfightEconomyBatchSql());
        ArrayNode arr = root.putArray("teamfights");
        Map<Integer, FightAccumulator> byId = new LinkedHashMap<>();
        for (Object[] row : query(conn, "SELECT id, start, \"end\", hero_damage, deaths FROM tf_episodes ORDER BY start")) {
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
            byId.put(id, new FightAccumulator(tf));
        }
        for (Object[] row : query(conn,
                "SELECT id, attacker_key, target_key, type, value FROM tf_events ORDER BY id, t")) {
            FightAccumulator fight = byId.get(intOf(row[0]));
            if (fight != null) {
                fight.addEvent((String) row[1], (String) row[2], (String) row[3],
                    row[4] == null ? 0 : dbl(row[4]));
            }
        }
        for (Object[] row : query(conn, "SELECT id, team, gold, xp FROM tf_economy ORDER BY id, team")) {
            FightAccumulator fight = byId.get(intOf(row[0]));
            if (fight != null) {
                fight.addEconomy(intOf(row[1]), longOf(row[2]), longOf(row[3]));
            }
        }
        byId.values().forEach(FightAccumulator::finish);
    }

    private static final class FightAccumulator {
        private final ObjectNode node;
        private final Set<String> participants = new LinkedHashSet<>();
        private final Map<String, double[]> playerStats = new LinkedHashMap<>();
        private long radiantGold;
        private long radiantXp;
        private long direGold;
        private long direXp;

        private FightAccumulator(ObjectNode node) {
            this.node = node;
        }

        private void addEvent(String attacker, String target, String type, double value) {
            if (isHeroKey(attacker)) {
                participants.add(attacker);
            }
            if (isHeroKey(target)) {
                participants.add(target);
            }
            if ("DOTA_COMBATLOG_DAMAGE".equals(type)) {
                if (isHeroKey(attacker)) {
                    playerStats.computeIfAbsent(attacker, key -> new double[4])[0] += value;
                }
                if (isHeroKey(target)) {
                    playerStats.computeIfAbsent(target, key -> new double[4])[1] += value;
                }
            } else if ("DOTA_COMBATLOG_DEATH".equals(type)) {
                if (isHeroKey(attacker)) {
                    playerStats.computeIfAbsent(attacker, key -> new double[4])[2]++;
                }
                if (isHeroKey(target)) {
                    playerStats.computeIfAbsent(target, key -> new double[4])[3]++;
                }
            }
        }

        private void addEconomy(int team, long gold, long xp) {
            if (team == 2) {
                radiantGold = gold;
                radiantXp = xp;
            } else if (team == 3) {
                direGold = gold;
                direXp = xp;
            }
        }

        private void finish() {
            ArrayNode part = node.putArray("participants");
            participants.stream().sorted().forEach(part::add);
            ObjectNode stats = node.putObject("player_stats");
            playerStats.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                double[] values = entry.getValue();
                ObjectNode player = stats.putObject(entry.getKey());
                player.put("damage_dealt", Math.round(values[0]));
                player.put("damage_taken", Math.round(values[1]));
                player.put("kills", (long) values[2]);
                player.put("deaths", (long) values[3]);
            });
            ObjectNode economy = node.putObject("economy");
            economy.putObject("radiant").put("gold", radiantGold).put("xp", radiantXp);
            economy.putObject("dire").put("gold", direGold).put("xp", direXp);
            economy.put("gold_delta", radiantGold - direGold);
            economy.put("xp_delta", radiantXp - direXp);
        }
    }

    /**
     * Objective timeline: roshan kills (who got the last hit, when) and building kills (towers / rax /
     * ancient / base towers, who destroyed whose). Every building kill is reported by the engine as
     * {@code DOTA_COMBATLOG_TEAM_BUILDING_KILL} with the destroying team in {@code attacker_team} and the
     * owner in {@code target_team}. Equal attacker/owner teams denote a deny rather than an enemy
     * objective; the fort (ancient) death ends the game.
     */
    private void addObjectives(Connection conn, ObjectNode root) throws Exception {
        ObjectNode obj = root.putObject("objectives");
        ArrayNode rosh = obj.putArray("roshan_kills");
        queryMaps(conn, """
            SELECT t, attacker, attacker_key, attacker_team
            FROM combatlog_v
            WHERE type = 'DOTA_COMBATLOG_DEATH' AND target LIKE 'npc_dota_roshan%'
            ORDER BY t
            """).forEach(row -> {
            ObjectNode r = rosh.addObject();
            r.put("t", round1(dbl(row.get("t"))));
            putStr(r, "killer", (String) row.get("attacker"));
            putStr(r, "killer_key", (String) row.get("attacker_key"));
            int team = intOf(row.get("attacker_team"));
            r.put("team", team);
            r.put("side", teamSide(team));
        });
        ArrayNode bld = obj.putArray("building_kills");
        queryMaps(conn, """
            SELECT t, target, target_team, attacker_team
            FROM combatlog_v
            WHERE type = 'DOTA_COMBATLOG_TEAM_BUILDING_KILL'
            ORDER BY t
            """).forEach(row -> {
            ObjectNode b = bld.addObject();
            b.put("t", round1(dbl(row.get("t"))));
            String target = (String) row.get("target");
            putStr(b, "building", target);
            if (target != null) {
                putStr(b, "building_key", target.replace("npc_dota_", ""));
                putStr(b, "kind", buildingKind(target));
            }
            int owner = intOf(row.get("target_team"));
            b.put("owner_team", owner);
            b.put("owner_side", teamSide(owner));
            int destroyer = intOf(row.get("attacker_team"));
            b.put("destroyer_team", destroyer);
            b.put("destroyer_side", teamSide(destroyer));
            b.put("denied", (owner == 2 || owner == 3) && owner == destroyer);
        });
    }

    private static String buildingKind(String target) {
        String key = target.replace("npc_dota_", "");
        if (key.contains("tower")) {
            return "tower";
        }
        if (key.contains("rax")) {
            return "rax";
        }
        if (key.contains("fort")) {
            return "ancient";
        }
        if (key.contains("fillers")) {
            return "base_tower";
        }
        return "other";
    }

    /**
     * Farm curves straight from the authoritative player resource (CDOTA_PlayerResource): cumulative
     * earned gold, last hits and denies per hero, bucketed every 60 s. Unlike the combat-log-derived
     * {@code gold_curves} (a signed sum of GOLD events), these values are the engine's
     * own counters, so the reports can quote them as facts rather than labelled trends.
     */
    private void addFarmCurves(Connection conn, ObjectNode root) throws Exception {
        ArrayNode arr = root.putArray("farm_curves");
        String curHero = null;
        ArrayNode points = null;
        for (Object[] row : query(conn, farmCurvesSql())) {
            String hero = (String) row[0];
            if (!hero.equals(curHero)) {
                if (points != null && !points.isEmpty()) {
                    ObjectNode entry = arr.addObject();
                    entry.put("hero", curHero);
                    entry.set("points", points);
                }
                curHero = hero;
                points = arr.arrayNode();
            }
            ObjectNode pt = points.addObject();
            pt.put("t", Math.round((dbl(row[1]) + 0.5) * 60));
            pt.put("total_earned_gold", longOf(row[2]));
            if (row[3] != null) pt.put("last_hits", longOf(row[3]));
            if (row[4] != null) pt.put("denies", longOf(row[4]));
        }
        if (points != null && !points.isEmpty()) {
            ObjectNode entry = arr.addObject();
            entry.put("hero", curHero);
            entry.set("points", points);
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

    // ------------------------------------------------------------------
    // query helpers
    // ------------------------------------------------------------------

    private Optional<Map<String, Object>> queryOne(Connection conn, String sql) throws Exception {
        List<Map<String, Object>> rows = queryMaps(conn, sql);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
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

    private void createWardsView(Connection conn) throws Exception {
        if (Files.exists(wards) && Files.size(wards) > 0) {
            conn.createStatement().execute(
                "CREATE VIEW wards AS SELECT * FROM read_ndjson('" + escape(wards) + "')");
            return;
        }
        conn.createStatement().execute("""
            CREATE TEMP TABLE wards (
              ward_id VARCHAR, type VARCHAR, placed_t DOUBLE, removed_t DOUBLE,
              lifetime_sec DOUBLE, team INTEGER, player_owner_id INTEGER, player INTEGER,
              x DOUBLE, y DOUBLE, z DOUBLE, removal_reason VARCHAR,
              destroyer VARCHAR, destroyer_team INTEGER
            )
            """);
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
