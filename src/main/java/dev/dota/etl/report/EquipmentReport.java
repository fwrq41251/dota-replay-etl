package dev.dota.etl.report;

import com.fasterxml.jackson.databind.JsonNode;

/** Renders persisted evidence without turning missing observations into negative action claims. */
final class EquipmentReport {
    static String window(JsonNode metrics, String type, long id, String hero) {
        StringBuilder text = new StringBuilder();
        for (JsonNode w : metrics.path("equipment_windows")) {
            if (!type.equals(w.path("context_type").asText()) || id!=w.path("context_id").asLong()
                || !hero.equals(w.path("hero_key").asText())) continue;
            text.append(" equipment=").append(w.path("status").asText())
                .append(" sample_t=").append(number(w.path("sample_t")))
                .append(" age_sec=").append(number(w.path("age_sec")))
                .append(" source=").append(w.path("source").asText("unknown"))
                .append(" completeness=").append(w.path("completeness").asText("unknown"));
            for (JsonNode slot : w.path("slots")) {
                if (!slot.path("status").asText().equals("occupied")) continue;
                text.append(" [").append(slot.path("slot").asInt()).append(':')
                    .append(slot.path("region").asText()).append(' ')
                    .append(slot.path("item").asText());
                if (slot.path("cooldown_remaining_sec").isNumber() || slot.path("item_charges").isNumber()) {
                    text.append(" sampled_cd_sec=").append(number(slot.path("cooldown_remaining_sec")))
                        .append(" item_charges=").append(number(slot.path("item_charges")));
                }
                text.append(']');
            }
            text.append("; availability=unknown; recorded uses:");
            boolean found = false;
            for (JsonNode use : metrics.path("equipment_uses")) {
                if (!type.equals(use.path("context_type").asText()) || id!=use.path("context_id").asLong()
                    || !hero.equals(use.path("hero_key").asText())) continue;
                found = true;
                text.append(' ').append(use.path("item").asText("unknown"))
                    .append('@').append(ReportGenerator.gameTime(use.path("t").asDouble()))
                    .append("(event=").append(use.path("event_id").asLong()).append(')');
            }
            if (!found) text.append(" none recorded (not proof of non-use)");
        }
        if (text.isEmpty()) return " equipment=unknown (no verified body context)";
        return ReportGenerator.markdownCell(text.toString());
    }

    static void appendFirstObservations(StringBuilder sb, JsonNode metrics, String hero) {
        sb.append("### Equipment First Observations\n\n")
            .append("Sampled observations, not exact delivery times. inventory includes stash; carried excludes stash/unknown slots.\n\n")
            .append("| Item | Observation | First sample t (horn seconds) | Previous sample / uncertainty seconds |\n")
            .append("|---|---|---|---|\n");
        for (JsonNode f : metrics.path("equipment_first_observations")) {
            if (!hero.equals(f.path("hero_key").asText())) continue;
            sb.append('|').append(ReportGenerator.markdownCell(f.path("item").asText()))
                .append('|').append(f.path("observation").asText())
                .append('|').append(number(f.path("sample_t")))
                .append('|').append(number(f.path("previous_sample_t")))
                .append(" / ").append(f.path("uncertainty_sec").isNumber()
                    ? number(f.path("uncertainty_sec")) : "unbounded").append("|\n");
        }
        sb.append('\n');
    }

    private static String number(JsonNode value) {
        return value.isNumber() ? String.format(java.util.Locale.ROOT, "%.3f", value.asDouble()) : "unknown";
    }

    private EquipmentReport() { }
}
