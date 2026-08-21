package dev.dota.etl.metrics;

/** Central SQL catalog and metric knobs shared by JSON assembly and DuckDB persistence. */
final class MetricQueries {

    static final int BUCKET_SEC = 5;
    static final double WEIGHT_DEATH = 4.0;
    static final double MIN_ACTIVE_SCORE = 8.0;
    static final int LANE_WINDOW_SEC = 90;
    static final double FOUNTAIN_RADIUS = 5300;
    static final int MIN_LANE_SAMPLES = 10;
    static final double KILL_GOLD_WINDOW_SEC = 2.0;
    static final double KILL_FOLLOWUP_WINDOW_SEC = 20.0;

    /** Locale-independent template replacement for internal constants and generated SQL only. */
    static String sql(String template, Object... args) {
        String out = template;
        for (Object arg : args) {
            int placeholder = out.indexOf("{}");
            if (placeholder < 0) {
                throw new IllegalArgumentException("too many SQL template arguments");
            }
            out = out.substring(0, placeholder) + arg + out.substring(placeholder + 2);
        }
        if (out.contains("{}")) {
            throw new IllegalArgumentException("missing SQL template argument");
        }
        return out;
    }

    static String rosterSql() {
        return """
            SELECT player, name, hero, hero_key, team, "level", kills, deaths, assists FROM (
              SELECT player, name, hero, hero_key, team, "level", kills, deaths, assists,
                     ROW_NUMBER() OVER (PARTITION BY player ORDER BY tick DESC) rn
              FROM players_v
            ) WHERE rn = 1 ORDER BY player
            """;
    }

    private static String laneCountsSql() {
        return sql("""
            SELECT player, COUNT(*) AS n,
                   SUM(CASE WHEN x > 0 AND y < 0 THEN 1 ELSE 0 END) AS bottom_n,
                   SUM(CASE WHEN x < 0 AND y > 0 THEN 1 ELSE 0 END) AS top_n,
                   SUM(CASE WHEN (x > 0 AND y > 0) OR (x < 0 AND y < 0) THEN 1 ELSE 0 END) AS mid_n
            FROM players_v
            WHERE t >= 0 AND t <= {} AND team IN (2, 3)
              AND NOT (hp IS NOT NULL AND hp <= 0)
              AND x IS NOT NULL AND y IS NOT NULL AND x <> 0 AND y <> 0
              AND NOT ((team = 2 AND x < -{} AND y < -{}) OR (team = 3 AND x > {} AND y > {}))
            GROUP BY player
            """, LANE_WINDOW_SEC, (long) FOUNTAIN_RADIUS, (long) FOUNTAIN_RADIUS,
            (long) FOUNTAIN_RADIUS, (long) FOUNTAIN_RADIUS);
    }

    static String lanePersistSql() {
        return sql("""
            SELECT player, n,
                   CASE WHEN bottom_n >= top_n AND bottom_n >= mid_n THEN 'bottom'
                        WHEN top_n >= bottom_n AND top_n >= mid_n THEN 'top'
                        ELSE 'mid' END AS lane,
                   ROUND(100.0 * GREATEST(bottom_n, top_n, mid_n) / n) AS confidence
            FROM ({})
            WHERE n >= {}
            """, laneCountsSql(), MIN_LANE_SAMPLES);
    }

    static String deathCostBatchSql() {
        return sql("""
            SELECT k.kill_id, k.t AS kill_t, k.target_key AS victim_key,
                   COALESCE(SUM(CASE WHEN c.type='DOTA_COMBATLOG_GOLD' AND ht.team = k.attacker_team THEN c.value END), 0) AS gold,
                   COALESCE(SUM(CASE WHEN c.type='DOTA_COMBATLOG_XP' AND ht.team = k.attacker_team THEN c.value END), 0) AS xp
            FROM hero_kills k
            LEFT JOIN combatlog_v c
              ON c.t >= k.t AND c.t <= k.t + {} AND c.type IN ('DOTA_COMBATLOG_GOLD', 'DOTA_COMBATLOG_XP')
            LEFT JOIN hero_team ht ON ht.hero_key = c.target_key
            WHERE k.attacker_team IN (2, 3)
            GROUP BY k.kill_id, k.t, k.target_key
            """, KILL_GOLD_WINDOW_SEC);
    }

    static String concededObjectiveBatchSql() {
        return sql("""
            WITH objs AS (
              SELECT t, target, target_key, 'building' AS kind, attacker_team
              FROM combatlog_v WHERE type='DOTA_COMBATLOG_TEAM_BUILDING_KILL'
              UNION ALL
              SELECT t, 'npc_dota_roshan' AS target, 'roshan' AS target_key, 'roshan' AS kind, attacker_team
              FROM combatlog_v WHERE type='DOTA_COMBATLOG_DEATH' AND target LIKE 'npc_dota_roshan%'
            ),
            matched AS (
              SELECT k.kill_id, k.t AS kill_t, k.target_key AS victim_key,
                     o.t AS obj_t, o.target AS obj_target,
                     o.target_key AS obj_target_key, o.kind AS obj_kind,
                     ROW_NUMBER() OVER (PARTITION BY k.kill_id ORDER BY o.t) AS rn
              FROM hero_kills k
              JOIN objs o ON o.attacker_team = k.attacker_team AND o.t >= k.t AND o.t <= k.t + {}
              WHERE k.attacker_team IN (2, 3)
            )
            SELECT kill_id, kill_t, victim_key, obj_t, obj_target, obj_target_key, obj_kind
            FROM matched WHERE rn = 1
            """, KILL_FOLLOWUP_WINDOW_SEC);
    }

    static String teamfightEpisodesSql() {
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
            FROM episodes e JOIN combatlog_v c ON c.t >= e.start_b AND c.t < e.end_b
            GROUP BY e.grp, start_b, end_b ORDER BY start_b
            """.replace("{BUCKET}", String.valueOf(BUCKET_SEC))
            .replace("{MIN_ACTIVE}", String.valueOf(MIN_ACTIVE_SCORE))
            .replace("{SCORE}", score);
    }

    static String teamfightEventsBatchSql() {
        return """
            SELECT e.id, c.t, c.attacker_key, c.target_key, c.type, c.value
            FROM tf_episodes e
            JOIN combatlog_v c ON c.t >= e.start AND c.t < e."end"
            WHERE (c.type='DOTA_COMBATLOG_DAMAGE' AND c.target_hero AND c.attacker LIKE 'npc_dota_hero_%') OR
                  (c.type='DOTA_COMBATLOG_DEATH' AND c.target_hero) OR
                  (c.type='DOTA_COMBATLOG_HEAL' AND c.target_hero AND c.attacker LIKE 'npc_dota_hero_%')
            """;
    }

    static String teamfightEconomyBatchSql() {
        return """
            SELECT e.id, ht.team,
                   COALESCE(SUM(CASE WHEN c.type = 'DOTA_COMBATLOG_GOLD' THEN c.value END), 0) AS gold,
                   COALESCE(SUM(CASE WHEN c.type = 'DOTA_COMBATLOG_XP' THEN c.value END), 0) AS xp
            FROM tf_episodes e
            JOIN combatlog_v c ON c.t >= e.start AND c.t < e."end"
              AND c.type IN ('DOTA_COMBATLOG_GOLD', 'DOTA_COMBATLOG_XP')
            JOIN hero_team ht ON ht.hero_key = c.target_key
            GROUP BY e.id, ht.team ORDER BY e.id, ht.team
            """;
    }

    static String farmCurvesSql() {
        return """
            SELECT hero_key AS hero, bucket,
                   MAX(total_earned_gold) AS gold, MAX(last_hits) AS lh, MAX(denies) AS denies
            FROM (
              SELECT hero_key, t, total_earned_gold, last_hits, denies, FLOOR(t / 60.0) AS bucket
              FROM players_v
              WHERE hero_key IS NOT NULL AND team IN (2, 3)
                AND total_earned_gold IS NOT NULL AND last_hits IS NOT NULL AND denies IS NOT NULL
            )
            GROUP BY hero, bucket ORDER BY hero, bucket
            """;
    }

    static String curveSql(String type, int bucketSec) {
        return sql("""
            WITH per_time AS (
              SELECT target_key AS hero, t, SUM(value) AS delta
              FROM combatlog_v
              WHERE type='{}' AND value IS NOT NULL AND target_key IS NOT NULL
              GROUP BY target_key, t
            ),
            cum AS (
              SELECT hero, t,
                     SUM(delta) OVER (
                       PARTITION BY hero ORDER BY t
                       ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                     ) AS cumv
              FROM per_time
            )
            SELECT hero, FLOOR(t / {}) * {} AS t_bucket, ARG_MAX(cumv, t) AS value
            FROM cum GROUP BY hero, t_bucket ORDER BY hero, t_bucket
            """, type, bucketSec, bucketSec);
    }

    static String itemTimelineSql() {
        return """
            SELECT target_key AS hero, value_name AS item, t
            FROM combatlog_v
            WHERE type='DOTA_COMBATLOG_PURCHASE' AND value_name IS NOT NULL AND target_key IS NOT NULL
            ORDER BY target_key, t
            """;
    }

    static String damagePerMinuteSql() {
        return """
            SELECT attacker_key AS hero, FLOOR(t / 60) AS minute, SUM(value) AS dealt
            FROM combatlog_v
            WHERE type='DOTA_COMBATLOG_DAMAGE' AND target_hero
              AND attacker LIKE 'npc_dota_hero_%' AND attacker_key IS NOT NULL
            GROUP BY attacker_key, minute ORDER BY attacker_key, minute
            """;
    }

    static String damageTakenSql() {
        return """
            SELECT target_key AS hero, SUM(value) AS taken_total
            FROM combatlog_v
            WHERE type='DOTA_COMBATLOG_DAMAGE' AND target_hero AND target_key IS NOT NULL
            GROUP BY target_key
            """;
    }

    static String damageTotalsSql() {
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

    private MetricQueries() {
    }
}
