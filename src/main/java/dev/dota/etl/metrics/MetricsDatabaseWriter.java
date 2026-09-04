package dev.dota.etl.metrics;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

/** Persists the in-memory metric views/tables into a standalone DuckDB file. */
final class MetricsDatabaseWriter {

    static void persist(Connection conn, Path output) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("ATTACH '" + escape(output) + "' AS out (TYPE duckdb)");
            st.execute("CREATE OR REPLACE TABLE out.combatlog AS SELECT * FROM combatlog_v");
            st.execute("CREATE OR REPLACE TABLE out.players AS SELECT * FROM players_v");
            st.execute("CREATE OR REPLACE TABLE out.wards AS SELECT * FROM wards_v");
            st.execute("CREATE OR REPLACE TABLE out.ward_lifetimes AS SELECT * FROM ward_lifetimes");
            st.execute("CREATE OR REPLACE TABLE out.dewards AS SELECT * FROM dewards");
            st.execute("CREATE OR REPLACE TABLE out.smoke_events AS SELECT * FROM smoke_events");
            st.execute("CREATE OR REPLACE TABLE out.kills AS SELECT * FROM hero_kills");
            st.execute("CREATE OR REPLACE TABLE out.hero_damage AS SELECT * FROM combatlog_v " +
                "WHERE type='DOTA_COMBATLOG_DAMAGE' AND target_hero");
            st.execute("CREATE OR REPLACE TABLE out.gold_curves AS " +
                MetricQueries.curveSql("DOTA_COMBATLOG_GOLD", 30));
            st.execute("CREATE OR REPLACE TABLE out.xp_curves AS " +
                MetricQueries.curveSql("DOTA_COMBATLOG_XP", 60));
            st.execute("CREATE OR REPLACE TABLE out.item_timeline AS " + MetricQueries.itemTimelineSql());
            st.execute("CREATE OR REPLACE TABLE out.damage_per_minute AS " + MetricQueries.damagePerMinuteSql());
            st.execute("CREATE OR REPLACE TABLE out.damage AS " + MetricQueries.damageTotalsSql());
            st.execute("CREATE OR REPLACE TABLE out.teamfights AS SELECT * FROM tf_episodes");
            st.execute("CREATE OR REPLACE TABLE out.teamfight_economy AS SELECT * FROM tf_economy");
            st.execute("CREATE OR REPLACE TABLE out.roshan_kills AS SELECT t, attacker, attacker_key, " +
                "attacker_team FROM combatlog_v WHERE type='DOTA_COMBATLOG_DEATH' AND target LIKE 'npc_dota_roshan%'");
            st.execute("CREATE OR REPLACE TABLE out.building_kills AS SELECT t, target, target_team, " +
                "attacker_team, (target_team IN (2, 3) AND target_team = attacker_team) AS denied " +
                "FROM combatlog_v WHERE type='DOTA_COMBATLOG_TEAM_BUILDING_KILL'");
            st.execute("CREATE OR REPLACE TABLE out.farm_curves AS " + MetricQueries.farmCurvesSql());
            st.execute("CREATE OR REPLACE TABLE out.roster AS " + MetricQueries.rosterSql());
            st.execute("CREATE OR REPLACE TABLE out.lanes AS " + MetricQueries.lanePersistSql());
            st.execute("CREATE OR REPLACE TABLE out.death_costs AS " + MetricQueries.deathCostBatchSql());
            st.execute("CREATE OR REPLACE TABLE out.conceded_objectives AS " +
                MetricQueries.concededObjectiveBatchSql());
            st.execute("CREATE OR REPLACE TABLE out.death_incidents AS SELECT * FROM death_incidents");
            st.execute("CREATE OR REPLACE TABLE out.incident_actions AS SELECT * FROM incident_actions");
            st.execute("CREATE OR REPLACE TABLE out.incident_controls AS SELECT * FROM incident_controls");
            st.execute("CREATE OR REPLACE TABLE out.incident_damage_sources AS SELECT * FROM incident_damage_sources");
            st.execute("CREATE OR REPLACE TABLE out.incident_health_timeline AS SELECT * FROM incident_health_timeline");
            st.execute("CREATE OR REPLACE TABLE out.incident_vitals AS SELECT * FROM incident_vitals");
            st.execute("CREATE OR REPLACE TABLE out.incident_nearby_heroes AS SELECT * FROM incident_nearby_heroes");
            st.execute("CREATE OR REPLACE TABLE out.incident_other_deaths AS SELECT * FROM incident_other_deaths");
            st.execute("CREATE OR REPLACE TABLE out.incident_vision AS SELECT * FROM incident_vision");
            st.execute("CREATE OR REPLACE TABLE out.incident_smoke_events AS SELECT * FROM incident_smoke_events");
        }
    }

    private static String escape(Path path) {
        return path.toString().replace("'", "''");
    }

    private MetricsDatabaseWriter() {
    }
}
