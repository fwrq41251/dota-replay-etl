package dev.dota.etl.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles a Chinese-language review prompt ("事实表 + 指令") from an analyze+metrics
 * result directory. All numbers are pre-computed from metrics.json; the LLM is only
 * asked to interpret them, never to do arithmetic.
 *
 * Writes {@code prompt.md} (and returns the prompt string). No LLM is called.
 */
public final class ReportGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Economy tables/highlights start here (t = 13 min is roughly game start / horn). */
    private static final int GAME_START_MINUTE = 13;

    private static final String[] KEY_ITEMS = {
        "item_blinkdagger", "item_black_king_bar", "item_aghanims_scepter", "item_aghanims_shard",
        "item_refresher", "item_sheepstick", "item_skadi", "item_monkey_king_bar", "item_bloodthorn",
        "item_divine_rapier", "item_radiance", "item_battlefury", "item_satanic", "item_assault",
        "item_butterfly", "item_manta", "item_silver_edge", "item_heart", "item_ultimate_scepter",
        "item_ultimate_scepter_2", "item_ethereal_blade", "item_octarine_core", "item_abyssal_blade",
        "item_nullifier", "item_sphere", "item_aeon_disk", "item_wind_waker"
    };

    private final Path metricsJson;
    private final Path matchJson;
    private final Path outFile;

    public ReportGenerator(Path matchDir) {
        this(matchDir.resolve("metrics.json"), matchDir.resolve("match.json"), matchDir.resolve("prompt.md"));
    }

    public ReportGenerator(Path metricsJson, Path matchJson, Path outFile) {
        this.metricsJson = metricsJson;
        this.matchJson = matchJson;
        this.outFile = outFile;
    }

    public Path promptFile() {
        return outFile;
    }

    /** Builds the prompt, writes it to prompt.md, and returns it. */
    public String generatePrompt() throws Exception {
        JsonNode metrics = MAPPER.readTree(Files.readString(metricsJson));
        JsonNode match = Files.exists(matchJson) ? MAPPER.readTree(Files.readString(matchJson)) : null;
        StringBuilder sb = new StringBuilder();

        sb.append("# 比赛复盘指令\n\n");
        sb.append("你是资深 Dota 2 教练兼解说，请基于以下从录像中**确定性提取**的数据，")
           .append("写一份客观、有洞察的中文复盘报告（markdown，含清晰章节）。\n")
           .append("要求：\n")
           .append("1. 所有数字（比分、时间、经济、团战、装备）只能引用下面给出的数据，**不得自行推算或编造**；\n")
           .append("2. 时间为游戏内秒数（约 800 秒 = 比赛开始/出兵，相当于游戏时钟 0 分）。\n")
           .append("3. 结构建议：比赛概览 → 阵容与分路 → 经济走势 → 关键团战 → 选手表现 → 关键转折点与总结。\n\n");

        appendSummary(sb, metrics, match);
        appendRoster(sb, metrics);
        appendEconomy(sb, metrics);
        appendKills(sb, metrics);
        appendTeamfights(sb, metrics);
        appendKeyItems(sb, metrics);
        appendMvpQuestion(sb);

        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, sb.toString());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // sections
    // ------------------------------------------------------------------

    private void appendSummary(StringBuilder sb, JsonNode m, JsonNode match) {
        JsonNode s = m.path("summary");
        sb.append("## 比赛概览\n\n");
        sb.append("- 比赛 ID：").append(match == null ? "?" : match.path("match_id").asText("?")).append('\n');
        sb.append("- 时长：约 ").append(fmt(s.path("duration_sec").asDouble())).append(" 秒\n");
        sb.append("- 比分（只统计英雄击杀）：");
        for (JsonNode t : s.path("team_kills")) {
            sb.append(side(t.path("team").asInt())).append(' ').append(t.path("kills").asLong()).append("  |  ");
        }
        sb.setLength(sb.length() - 5);
        sb.append("\n- 一血：").append(s.path("first_blood").path("victim").asText("?"))
          .append(" 于 ").append(fmt(s.path("first_blood").path("t").asDouble())).append(" 秒被 ")
          .append(s.path("first_blood").path("killer").asText("?")).append(" 击杀\n");
        sb.append("- 肉山击杀：").append(s.path("roshan_kills").asLong()).append(" 次\n\n");
    }

    private void appendRoster(StringBuilder sb, JsonNode m) {
        sb.append("## 阵容\n\n");
        sb.append("| 队伍 | 选手 | 英雄 | 击杀/死亡/助攻 | 等级 |\n");
        sb.append("|---|---|---|---|---|\n");
        for (JsonNode p : m.path("roster")) {
            sb.append('|').append(side(p.path("team").asInt()))
              .append('|').append(p.path("name").asText("?"))
              .append('|').append(p.path("hero").asText("?"))
              .append('|').append(p.path("kills").asInt()).append('/')
              .append(p.path("deaths").asInt()).append('/')
              .append(p.path("assists").asInt())
              .append('|').append(p.path("level").asInt()).append("|\n");
        }
        sb.append('\n');
    }

    private void appendEconomy(StringBuilder sb, JsonNode m) {
        // per-hero sorted cumulative income points, then carry-forward to each minute
        Map<String, Integer> teamByHero = new LinkedHashMap<>();
        Map<String, List<double[]>> cumByHero = new LinkedHashMap<>();
        for (JsonNode p : m.path("roster")) {
            teamByHero.put(p.path("hero_key").asText(), p.path("team").asInt());
        }
        int maxMinute = 0;
        for (JsonNode g : m.path("gold_curves")) {
            String hero = g.path("hero").asText();
            if (!teamByHero.containsKey(hero)) {
                continue;
            }
            List<double[]> pts = new ArrayList<>();
            for (JsonNode pt : g.path("points")) {
                pts.add(new double[]{pt.path("t").asDouble(), pt.path("gold").asDouble()});
            }
            if (pts.isEmpty()) {
                continue;
            }
            cumByHero.put(hero, pts);
            maxMinute = Math.max(maxMinute, (int) (pts.get(pts.size() - 1)[0] / 60.0));
        }
        if (cumByHero.isEmpty()) {
            return;
        }
        sb.append("## 经济走势\n\n");
        sb.append("经济差（天辉 - 夜魇，累计收入差，含被动收入；用于观察经济走势，不是实时存款）：\n\n");
        sb.append("| 分钟 | 经济差 |\n|---|---|\n");
        long maxLead = Long.MIN_VALUE;
        long maxDeficit = Long.MAX_VALUE;
        int maxLeadMin = 0;
        int maxDeficitMin = 0;
        for (int min = 0; min <= maxMinute; min++) {
            double t = min * 60.0;
            long[] teamSum = new long[2];
            for (Map.Entry<String, List<double[]>> e : cumByHero.entrySet()) {
                int team = teamByHero.get(e.getKey());
                double cumv = lastAtOrBefore(e.getValue(), t);
                if (team == 2) {
                    teamSum[0] += cumv;
                } else if (team == 3) {
                    teamSum[1] += cumv;
                }
            }
            long lead = teamSum[0] - teamSum[1];
            if (min >= GAME_START_MINUTE) { // ignore pre-horn minutes that are all ~zero
                if (lead > maxLead) {
                    maxLead = lead;
                    maxLeadMin = min;
                }
                if (lead < maxDeficit) {
                    maxDeficit = lead;
                    maxDeficitMin = min;
                }
                sb.append('|').append(min).append('|')
                  .append(lead > 0 ? "+" + lead : String.valueOf(lead)).append("|\n");
            }
        }
        sb.append('\n');
        if (maxLead > 0) {
            sb.append("天辉经济最大领先 +").append(maxLead)
              .append("（约第 ").append(maxLeadMin).append(" 分钟）");
        }
        if (maxDeficit < 0) {
            if (maxLead > 0) {
                sb.append('；');
            }
            sb.append("天辉经济最大落后 ").append(maxDeficit)
              .append("（约第 ").append(maxDeficitMin).append(" 分钟）");
        }
        sb.append("。\n\n");
    }

    private static double lastAtOrBefore(List<double[]> pts, double t) {
        int lo = 0;
        int hi = pts.size() - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (pts.get(mid)[0] <= t) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans < 0 ? 0 : pts.get(ans)[1];
    }

    private void appendKills(StringBuilder sb, JsonNode m) {
        JsonNode kills = m.path("kills");
        if (!kills.isArray() || kills.isEmpty()) {
            return;
        }
        sb.append("## 击杀时间线（全部英雄击杀）\n\n");
        sb.append("| 时间(秒) | 击杀者 | 被击杀 | 助攻 |\n|---|---|---|---|\n");
        for (JsonNode k : kills) {
            sb.append('|').append(fmt(k.path("t").asDouble()))
              .append('|').append(heroShort(k.path("killer").asText("")))
              .append('|').append(heroShort(k.path("victim").asText("")))
              .append('|');
            JsonNode assist = k.path("assist_players");
            sb.append(assist.isArray() && !assist.isEmpty() ? assist.size() + " 人" : "-")
              .append("|\n");
        }
        sb.append('\n');
    }

    private void appendTeamfights(StringBuilder sb, JsonNode m) {
        JsonNode fights = m.path("teamfights");
        if (!fights.isArray() || fights.isEmpty()) {
            return;
        }
        // count deaths per team per fight from the kills list
        List<double[]> kills = new ArrayList<>();
        for (JsonNode k : m.path("kills")) {
            double t = k.path("t").asDouble();
            int victimTeam = k.path("victim_team").asInt();
            if (victimTeam == 2 || victimTeam == 3) {
                kills.add(new double[]{t, victimTeam});
            }
        }
        sb.append("## 团战时间线（共 ").append(fights.size()).append(" 场，★ 为高伤害关键团战）\n\n");
        sb.append("| 开始(秒) | 持续 | 英雄伤害 | 天辉阵亡 | 夜魇阵亡 | 结果 | 参战英雄 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        List<JsonNode> sorted = new ArrayList<>();
        fights.forEach(sorted::add);
        sorted.sort((a, b) -> Double.compare(b.path("hero_damage").asDouble(), a.path("hero_damage").asDouble()));
        Map<String, Boolean> highlight = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(8, sorted.size()); i++) {
            highlight.put(fmt(sorted.get(i).path("start").asDouble()), true);
        }
        List<JsonNode> byTime = new ArrayList<>();
        fights.forEach(byTime::add);
        byTime.sort((a, b) -> Double.compare(a.path("start").asDouble(), b.path("start").asDouble()));
        for (JsonNode f : byTime) {
            double start = f.path("start").asDouble();
            double end = f.path("end").asDouble();
            int radDeaths = 0;
            int direDeaths = 0;
            for (double[] k : kills) {
                if (k[0] >= start && k[0] <= end) {
                    if (k[1] == 2) {
                        radDeaths++;
                    } else {
                        direDeaths++;
                    }
                }
            }
            sb.append('|').append(highlight.containsKey(fmt(start)) ? "★" : "")
              .append(fmt(start))
              .append('|').append(fmt(end - start))
              .append('|').append(f.path("hero_damage").asLong())
              .append('|').append(radDeaths)
              .append('|').append(direDeaths)
              .append('|');
            if (radDeaths == direDeaths) {
                sb.append("均势");
            } else if (radDeaths > direDeaths) {
                sb.append("夜魇赚 (天辉 ").append(radDeaths).append(" 换 ").append(direDeaths).append(')');
            } else {
                sb.append("天辉赚 (天辉 ").append(radDeaths).append(" 换 ").append(direDeaths).append(')');
            }
            sb.append('|');
            StringBuilder part = new StringBuilder();
            for (JsonNode p : f.path("participants")) {
                part.append(heroShort(p.asText())).append(' ');
            }
            sb.append(part.toString().trim()).append("|\n");
        }
        sb.append('\n');
    }

    private void appendKeyItems(StringBuilder sb, JsonNode m) {
        sb.append("## 关键装备节点\n\n");
        for (JsonNode t : m.path("item_timeline")) {
            List<JsonNode> big = new ArrayList<>();
            for (JsonNode it : t.path("items")) {
                String item = it.path("item").asText("");
                for (String key : KEY_ITEMS) {
                    if (item.equals(key)) {
                        big.add(it);
                        break;
                    }
                }
            }
            if (!big.isEmpty()) {
                sb.append("- ").append(heroShort(t.path("hero").asText(""))).append("：");
                for (JsonNode it : big) {
                    sb.append(it.path("item").asText().replaceFirst("^item_", "")).append(' ').append(fmt(it.path("t").asDouble())).append("秒；");
                }
                sb.setLength(sb.length() - 1);
                sb.append('\n');
            }
        }
        sb.append('\n');
    }

    private void appendMvpQuestion(StringBuilder sb) {
        sb.append("## 额外要求（必答）\n\n");
        sb.append("请在报告末尾单独给出：\n");
        sb.append("1. **本场 MVP**：写出选手名与英雄，并给出客观理由（引用上面数据，例如 KDA、经济贡献、团战作用、关键装备与胜负团贡献）；\n");
        sb.append("2. **本场表现最差的选手**：写出选手名与英雄，同样给出基于数据的理由。\n");
        sb.append("只能从上文阵容表中的选手里选择，理由必须可被上面数据支撑，不要臆测场外因素。\n");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    static String side(int team) {
        return switch (team) {
            case 2 -> "天辉";
            case 3 -> "夜魇";
            default -> "?";
        };
    }

    static String heroShort(String raw) {
        if (raw == null) {
            return "-";
        }
        String s = raw.replaceFirst("^npc_dota_hero_", "");
        return s.isEmpty() ? "-" : s;
    }

    static String fmt(double v) {
        return String.format("%.1f", v).replace(".0", "");
    }
}