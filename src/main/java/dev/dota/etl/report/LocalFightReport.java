package dev.dota.etl.report;

import com.fasterxml.jackson.databind.JsonNode;

/** Renders only persisted local membership statistics, with no global time joins. */
final class LocalFightReport {
    static void append(StringBuilder sb, JsonNode metrics, String hero) {
        sb.append("## 局部交战（时间、空间与真实交互约束）\n\n")
            .append("类型为到场人数启发式推断，不代表战术意图；输出归属不等于本体到场。")
            .append("阵亡列分为已验证/未知死亡，盾复活单列。经济因果归属未知，不展示全队同时收入为局部收益。")
            .append("死亡后目标仍仅时间关联，不自动建立因果。中心/范围来自目标 as-of 采样，并非所有参战者位置。\n\n");
        sb.append("装备为开战前最新采样（最大年龄 5 秒），不是购买时间或可操作证明；缺失不回退旧快照。")
            .append("局部位置与使用位置均按实际 tick-end 观察时钟关联，不用计划采样标签；缺失时钟保持未知。")
            .append("仅关联有本体到场证据的成员；使用记录还需同地、同窗口证据，未记录不等于没开。\n\n");
        JsonNode parameters = metrics.path("parameters").path("local_fights");
        sb.append("参数：最大交互间隔 ").append(parameters.path("max_gap_sec").asText("未知"))
            .append(" 秒，最大时长 ").append(parameters.path("max_duration_sec").asText("未知"))
            .append(" 秒，事件位置最大两两距离 ").append(parameters.path("max_pair_distance").asText("未知"))
            .append("，采样最大年龄 ").append(parameters.path("max_sample_age_sec").asText("未知")).append(" 秒。\n\n");
        long unknown = 0;
        for (JsonNode event : metrics.path("local_fight_events")) {
            if (event.path("fight_id").isNull()) unknown++;
        }
        sb.append("全场未定位交互事件：").append(unknown).append("，保留在 local_fight_events，不强行归场。\n\n");
        sb.append("| ID / 时间 | 类型（推断） | 中心 / x,y 范围 | 伤害 | 阵亡 已验证/未知 / 盾 | 本体到场证据 | 输出归属与个人成员统计 |\n")
            .append("|---|---|---|---|---|---|---|\n");
        for (JsonNode fight : metrics.path("local_fights")) {
            StringBuilder presence = new StringBuilder();
            StringBuilder stats = new StringBuilder();
            boolean relevant = hero == null;
            for (JsonNode player : metrics.path("local_fight_players")) {
                if (player.path("fight_id").asInt() != fight.path("id").asInt()) continue;
                String key = player.path("hero_key").asText();
                if (key.equals(hero)) relevant = true;
                if (player.path("presence_events").asInt() > 0) presence.append(ReportGenerator.markdownCell(key)).append(' ');
                if (hero == null || key.equals(hero)) {
                    stats.append(ReportGenerator.markdownCell(key)).append(" 输出/承伤 ").append(player.path("damage_dealt").asLong())
                        .append('/').append(player.path("damage_taken").asLong())
                        .append(" 击杀/死亡 ").append(player.path("kills").asLong()).append('/')
                        .append(player.path("deaths").asLong()).append("；");
                    stats.append(EquipmentReport.window(metrics, "local_fight", fight.path("id").asLong(), key));
                }
            }
            if (!relevant) continue;
            sb.append('|').append(fight.path("id").asInt()).append(" / ")
                .append(ReportGenerator.gameTime(fight.path("start").asDouble())).append('-')
                .append(ReportGenerator.gameTime(fight.path("end").asDouble()))
                .append('|').append(fight.path("kind").asText()).append('|')
                .append(Math.round(fight.path("center_x").asDouble())).append(',')
                .append(Math.round(fight.path("center_y").asDouble())).append(" / ")
                .append(Math.round(fight.path("min_x").asDouble())).append("..")
                .append(Math.round(fight.path("max_x").asDouble())).append(", ")
                .append(Math.round(fight.path("min_y").asDouble())).append("..")
                .append(Math.round(fight.path("max_y").asDouble())).append('|')
                .append(fight.path("hero_damage").asLong()).append('|')
                .append(fight.path("scored_deaths").asLong()).append('/')
                .append(fight.path("unknown_deaths").asLong()).append(" / ")
                .append(fight.path("aegis_respawns").asLong()).append('|')
                .append(presence).append('|').append(stats).append("|\n");
        }
        sb.append('\n');
    }

    private LocalFightReport() { }
}
