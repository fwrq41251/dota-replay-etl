package dev.dota.etl.metrics;

import java.sql.Connection;

/** Shared tick-end clock for local positions and equipment; scheduled labels only locate unknown blockers. */
final class PlayerObservationClock {
    static void createView(Connection conn, double offset) throws Exception {
        try (var st = conn.createStatement()) {
            st.execute(MetricQueries.sql("""
                CREATE OR REPLACE TEMP VIEW players_observed AS
                WITH observed AS (
                  SELECT p.*,try_cast(json_extract_string(to_json(p),'$.equipment.observed_raw_t') AS DOUBLE) observed_raw_t
                  FROM players_v p
                )
                SELECT * EXCLUDE(t),t scheduled_t,
                       CASE WHEN isfinite(observed_raw_t) THEN observed_raw_t-{} END t,
                       CASE WHEN isfinite(observed_raw_t) THEN observed_raw_t-{} ELSE t END asof_t
                FROM observed
                """, offset, offset));
        }
    }

    private PlayerObservationClock() { }
}
