package dev.dota.etl.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles a single-player Chinese review prompt ("事实表 + 指令") for one hero in a
 * match, from metrics.json (roster / kills / teamfights / items / gold / damage) plus
 * the raw per-second player samples (players.ndjson) for position-based farming analysis.
 *
 * Writes {@code player-review-<hero>.md} (and returns the prompt string). No LLM is called.
 */
public final class PlayerReviewGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Economy tables start here (t = 13 min is roughly game start / horn). */
    private static final int GAME_START_MINUTE = 13;

    private final Path matchDir;
    private final Path metricsJson;
    private final Path matchJson;
    private final Path playersJson;
    private final String selector;
    private Path outFile;

    public PlayerReviewGenerator(Path matchDir, String selector) {
        this.matchDir = matchDir;
        this.metricsJson = matchDir.resolve("metrics.json");
        this.matchJson = matchDir.resolve("match.json");
        this.playersJson = matchDir.resolve("players.ndjson");
        this.selector = selector;
    }

    public Path promptFile() {
        return outFile;
    }

    /** Builds the prompt, writes it to player-review-<hero>.md, and returns it. */
    public String generatePrompt() throws Exception {
        JsonNode metrics = MAPPER.readTree(Files.readString(metricsJson));
        JsonNode match = Files.exists(matchJson) ? MAPPER.readTree(Files.readString(matchJson)) : null;
        JsonNode target = resolveTarget(metrics);
        if (target == null) {
            throw new IllegalArgumentException("no roster entry matches selector '" + selector + "'");
        }
        String heroKey = target.path("hero_key").asText();
        outFile = matchDir.resolve("player-review-" + heroKey + ".md");

        StringBuilder sb = new StringBuilder();
        sb.append("# 单选手复盘指令（").append(heroKey).append("）\n\n");
        sb.append("你是资深 Dota 2 教练，请基于以下从录像中**确定性提取**的数据，")
           .append("针对选手 **").append(target.path("name").asText("?")).append("**（")
           .append(target.path("hero").asText("?")).append("，")
           .append(ReportGenerator.side(target.path("team").asInt())).append("）")
           .append("做一份客观、有洞察的中文复盘（markdown，含清晰章节）。\n")
           .append("要求：\n")
           .append("1. 所有数字只能引用下面给出的数据，**不得自行推算或编造**；\n")
           .append("2. 时间为游戏内秒数（约 800 秒 = 比赛开始/出兵，相当于游戏时钟 0 分）；\n")
           .append("3. 重点分析四个维度：**出装决策、团战切入、打钱路线、关键决策**；\n")
           .append("4. 每个结论必须引用数据支撑，最后给出按收益排序的改进清单。\n\n");

        appendProfile(sb, metrics, match, target);
        appendItems(sb, metrics, heroKey);
        appendKillsDeaths(sb, metrics, heroKey);
        appendEconomy(sb, metrics, heroKey);
        appendDamage(sb, metrics, heroKey);
        appendTeamfights(sb, metrics, heroKey);
        appendPosition(sb, metrics, target);
        appendQuestions(sb);

        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, sb.toString());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // sections
    // ------------------------------------------------------------------

    private void appendProfile(StringBuilder sb, JsonNode m, JsonNode match, JsonNode p) {
        sb.append("## 选手档案\n\n");
        sb.append("- 比赛 ID：").append(match == null ? "?" : match.path("match_id").asText("?")).append('\n');
        sb.append("- 队伍：").append(ReportGenerator.side(p.path("team").asInt())).append('\n');
        sb.append("- 选手：").append(p.path("name").asText("?")).append("（").append(p.path("hero").asText("?")).append("）\n");
        sb.append("- 最终战绩：").append(p.path("kills").asInt()).append('/')
          .append(p.path("deaths").asInt()).append('/').append(p.path("assists").asInt())
          .append("，等级 ").append(p.path("level").asInt()).append('\n');
        long my = 0;
        long opp = 0;
        for (JsonNode t : m.path("summary").path("team_kills")) {
            if (t.path("team").asInt() == p.path("team").asInt()) {
                my = t.path("kills").asLong();
            } else {
                opp = t.path("kills").asLong();
            }
        }
        sb.append("- 团队结果：本队英雄击杀 ").append(my).append(" : ").append(opp)
          .append(my > opp ? "（胜）" : "（负）").append("\n\n");
    }

    private void appendItems(StringBuilder sb, JsonNode m, String heroKey) {
        sb.append("## 装备时间线\n\n");
        sb.append("（首次购买时间；装备名称为内部 ID）\n\n");
        sb.append("| 时间(秒) | 装备 |\n|---|---|\n");
        boolean any = false;
        for (JsonNode t : m.path("item_timeline")) {
            if (!t.path("hero").asText().equals(heroKey)) {
                continue;
            }
            List<JsonNode> items = new ArrayList<>();
            t.path("items").forEach(items::add);
            items.sort((a, b) -> Double.compare(a.path("t").asDouble(), b.path("t").asDouble()));
            for (JsonNode it : items) {
                any = true;
                sb.append('|').append(ReportGenerator.fmt(it.path("t").asDouble()))
                  .append('|').append(it.path("item").asText().replaceFirst("^item_", "")).append("|\n");
            }
        }
        if (!any) {
            sb.append("（无购买记录）\n");
        }
        sb.append('\n');
    }

    private void appendKillsDeaths(StringBuilder sb, JsonNode m, String heroKey) {
        List<JsonNode> myKills = new ArrayList<>();
        List<JsonNode> myDeaths = new ArrayList<>();
        for (JsonNode k : m.path("kills")) {
            if (k.path("killer_key").asText("").equals(heroKey)) {
                myKills.add(k);
            }
            if (k.path("victim_key").asText("").equals(heroKey)) {
                myDeaths.add(k);
            }
        }
        sb.append("## 击杀与阵亡\n\n");
        sb.append("### 击杀（").append(myKills.size()).append(" 个）\n\n");
        sb.append("| 时间(秒) | 击杀对象 | 位置 |\n|---|---|---|\n");
        for (JsonNode k : myKills) {
            appendKillRow(sb, k, k.path("victim"));
        }
        sb.append("\n### 阵亡（").append(myDeaths.size()).append(" 次）\n\n");
        sb.append("| 时间(秒) | 击杀者 | 位置 |\n|---|---|---|\n");
        for (JsonNode k : myDeaths) {
            appendKillRow(sb, k, k.path("killer"));
        }
        sb.append('\n');
        if (!myKills.isEmpty()) {
            double last = myKills.get(myKills.size() - 1).path("t").asDouble();
            sb.append("**事实提取**：最后一次击杀在 ").append(ReportGenerator.fmt(last)).append(" 秒。\n");
        }
        if (!myDeaths.isEmpty()) {
            double last = myDeaths.get(myDeaths.size() - 1).path("t").asDouble();
            sb.append("**事实提取**：最后一次阵亡在 ").append(ReportGenerator.fmt(last)).append(" 秒。\n");
        }
        sb.append('\n');
    }

    private static void appendKillRow(StringBuilder sb, JsonNode k, JsonNode hero) {
        sb.append('|').append(ReportGenerator.fmt(k.path("t").asDouble()))
          .append('|').append(ReportGenerator.heroShort(hero.asText(""))).append('|');
        JsonNode loc = k.path("location");
        if (loc.isArray() && loc.size() == 2) {
            sb.append('(').append(ReportGenerator.fmt(loc.get(0).asDouble()))
              .append(',').append(ReportGenerator.fmt(loc.get(1).asDouble())).append(')');
        } else {
            sb.append('-');
        }
        sb.append("|\n");
    }

    private void appendEconomy(StringBuilder sb, JsonNode m, String heroKey) {
        Map<String, Integer> teamByHero = new LinkedHashMap<>();
        Map<String, List<double[]>> cum = new LinkedHashMap<>();
        int team = -1;
        for (JsonNode p : m.path("roster")) {
            teamByHero.put(p.path("hero_key").asText(), p.path("team").asInt());
            if (p.path("hero_key").asText().equals(heroKey)) {
                team = p.path("team").asInt();
            }
        }
        for (JsonNode g : m.path("gold_curves")) {
            String hero = g.path("hero").asText();
            if (!teamByHero.containsKey(hero)) {
                continue;
            }
            List<double[]> pts = new ArrayList<>();
            for (JsonNode pt : g.path("points")) {
                pts.add(new double[]{pt.path("t").asDouble(), pt.path("gold").asDouble()});
            }
            if (!pts.isEmpty()) {
                cum.put(hero, pts);
            }
        }
        List<double[]> mine = cum.getOrDefault(heroKey, List.of());
        if (mine.isEmpty()) {
            return;
        }
        String enemyTop = enemyTopEarner(teamByHero, team, cum);
        sb.append("## 经济对比（累计收入，含被动收入；用于观察走势，不是实时存款）\n\n");
        sb.append("对照对象：对方队伍累计收入最高的英雄 ").append(enemyTop == null ? "（无）" : enemyTop).append("\n\n");
        sb.append("| 分钟 | ").append(heroKey).append(" | ").append(enemyTop == null ? "-" : enemyTop).append(" |\n|---|---|---|\n");
        double lastT = mine.get(mine.size() - 1)[0];
        Integer overtakeMin = null;
        for (int min = GAME_START_MINUTE; min * 60.0 <= lastT + 60; min++) {
            double t = min * 60.0;
            double v = lastAtOrBefore(mine, t);
            double ev = enemyTop == null ? 0 : lastAtOrBefore(cum.get(enemyTop), t);
            if (overtakeMin == null && enemyTop != null && ev > v) {
                overtakeMin = min;
            }
            sb.append('|').append(min).append('|').append((long) v).append('|').append((long) ev).append("|\n");
        }
        sb.append('\n');
        if (overtakeMin != null) {
            sb.append("**事实提取**：对方头号在约第 ").append(overtakeMin)
              .append(" 分钟起累计收入反超本选手。\n");
        }
        if (mine.size() >= 2) {
            double tMin = Math.max(GAME_START_MINUTE * 60.0, lastT - 3 * 60.0);
            double vStart = lastAtOrBefore(mine, tMin);
            double vEnd = lastAtOrBefore(mine, lastT);
            if (vEnd - vStart < 500) {
                sb.append("**事实提取**：最后约 3 分钟内累计收入仅增长 ").append((long) (vEnd - vStart))
                  .append("，近乎停滞。\n");
            }
        }
        sb.append('\n');
    }

    private void appendDamage(StringBuilder sb, JsonNode m, String heroKey) {
        JsonNode target = null;
        for (JsonNode d : m.path("damage")) {
            if (d.path("hero").asText().equals(heroKey)) {
                target = d;
                break;
            }
        }
        if (target == null) {
            return;
        }
        sb.append("## 英雄伤害\n\n");
        sb.append("- 对英雄总伤害：").append(target.path("dealt_total").asLong()).append('\n');
        sb.append("- 承受总伤害：").append(target.path("taken_total").asLong()).append('\n');
        List<JsonNode> perMin = new ArrayList<>();
        target.path("per_minute").forEach(perMin::add);
        if (!perMin.isEmpty()) {
            perMin.sort((a, b) -> Integer.compare(a.path("min").asInt(), b.path("min").asInt()));
            sb.append("- 每分钟对英雄伤害（分钟: 伤害）：\n");
            boolean first = true;
            for (JsonNode pm : perMin) {
                sb.append(first ? "  " : ", ")
                  .append(pm.path("min").asInt()).append("分:").append(pm.path("dealt").asLong());
                first = false;
            }
            sb.append('\n');
        }
        sb.append('\n');
    }

    private void appendTeamfights(StringBuilder sb, JsonNode m, String heroKey) {
        List<double[]> kills = new ArrayList<>();
        List<double[]> myKills = new ArrayList<>();
        List<double[]> myDeaths = new ArrayList<>();
        for (JsonNode k : m.path("kills")) {
            int vt = k.path("victim_team").asInt();
            if (vt == 2 || vt == 3) {
                kills.add(new double[]{k.path("t").asDouble(), vt});
            }
            if (k.path("killer_key").asText("").equals(heroKey)) {
                myKills.add(new double[]{k.path("t").asDouble(), 0});
            }
            if (k.path("victim_key").asText("").equals(heroKey)) {
                myDeaths.add(new double[]{k.path("t").asDouble(), 0});
            }
        }
        List<JsonNode> fights = new ArrayList<>();
        m.path("teamfights").forEach(fights::add);
        fights.sort((a, b) -> Double.compare(a.path("start").asDouble(), b.path("start").asDouble()));
        List<JsonNode> mine = new ArrayList<>();
        for (JsonNode f : fights) {
            for (JsonNode part : f.path("participants")) {
                if (part.asText().equals(heroKey)) {
                    mine.add(f);
                    break;
                }
            }
        }
        if (mine.isEmpty()) {
            return;
        }
        sb.append("## 本选手参与的团战\n\n");
        sb.append("（★ = 高伤害关键团战；结果按团战窗口内阵亡换算）\n\n");
        sb.append("| 开始(秒) | 英雄伤害 | 天辉阵亡 | 夜魇阵亡 | 结果 | 本选手 |\n|---|---|---|---|---|---|\n");
        List<JsonNode> byDmg = new ArrayList<>(mine);
        byDmg.sort((a, b) -> Double.compare(b.path("hero_damage").asDouble(), a.path("hero_damage").asDouble()));
        Map<String, Boolean> hl = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(6, byDmg.size()); i++) {
            hl.put(ReportGenerator.fmt(byDmg.get(i).path("start").asDouble()), true);
        }
        for (JsonNode f : mine) {
            double start = f.path("start").asDouble();
            double end = f.path("end").asDouble();
            int rad = 0;
            int dire = 0;
            boolean gotKill = false;
            boolean died = false;
            for (double[] k : kills) {
                if (k[0] >= start && k[0] <= end) {
                    if (k[1] == 2) {
                        rad++;
                    } else {
                        dire++;
                    }
                }
            }
            for (double[] k : myKills) {
                if (k[0] >= start && k[0] <= end) {
                    gotKill = true;
                }
            }
            for (double[] k : myDeaths) {
                if (k[0] >= start && k[0] <= end) {
                    died = true;
                }
            }
            sb.append('|').append(hl.containsKey(ReportGenerator.fmt(start)) ? "★" : "")
              .append(ReportGenerator.fmt(start))
              .append('|').append(f.path("hero_damage").asLong())
              .append('|').append(rad).append('|').append(dire).append('|');
            if (rad == dire) {
                sb.append("均势");
            } else if (rad > dire) {
                sb.append("夜魇赚");
            } else {
                sb.append("天辉赚");
            }
            sb.append('|');
            if (died) {
                sb.append("阵亡");
            }
            if (gotKill) {
                sb.append(died ? "+击杀" : "击杀");
            }
            if (!died && !gotKill) {
                sb.append("存活未击杀");
            }
            sb.append("|\n");
        }
        sb.append('\n');
    }

    private void appendPosition(StringBuilder sb, JsonNode metrics, JsonNode target) throws Exception {
        if (!Files.exists(playersJson)) {
            return;
        }
        int player = target.path("player").asInt();
        int team = target.path("team").asInt();
        double start = metrics.path("summary").path("game_start_sec").asDouble();
        double end = metrics.path("summary").path("game_end_sec").asDouble();
        double dur = end - start;
        if (dur <= 0) {
            return;
        }
        long[] total = new long[3];
        long[] enemyHalf = new long[3];
        long[] deep = new long[3];
        try (BufferedReader br = Files.newBufferedReader(playersJson)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode s;
                try {
                    s = MAPPER.readTree(line);
                } catch (Exception e) {
                    continue;
                }
                if (s.path("player").asInt() != player || !s.hasNonNull("x") || !s.hasNonNull("y")) {
                    continue;
                }
                double t = s.path("t").asDouble();
                if (t < start || t > end) {
                    continue;
                }
                double x = s.path("x").asDouble();
                double y = s.path("y").asDouble();
                if (x == 0 && y == 0) {
                    continue;
                }
                int phase = t < start + dur / 3 ? 0 : t < start + 2 * dur / 3 ? 1 : 2;
                total[phase]++;
                boolean enemy = team == 3 ? x + y < 0 : x + y > 0;
                if (enemy) {
                    enemyHalf[phase]++;
                    boolean deepEnemy = team == 3 ? (x < -2500 && y < -2500) : (x > 2500 && y > 2500);
                    if (deepEnemy) {
                        deep[phase]++;
                    }
                }
            }
        }
        if (total[0] + total[1] + total[2] == 0) {
            return;
        }
        sb.append("## 打钱/位置分析\n\n");
        sb.append("坐标说明：正坐标 = 夜魇半场（右上），负坐标 = 天辉半场（左下）；")
           .append("对方半场按河道对角线（x+y=0）划分，深处 = 对方基地方向约 2500 单位以内。\n\n");
        sb.append("| 阶段 | 时间范围(秒) | 样本数 | 对方半场占比 | 对方深处占比 |\n|---|---|---|---|---|\n");
        String[] labels = {"前期", "中期", "后期"};
        for (int i = 0; i < 3; i++) {
            if (total[i] == 0) {
                continue;
            }
            double b = start + dur * i / 3.0;
            double e = start + dur * (i + 1) / 3.0;
            sb.append('|').append(labels[i])
              .append('|').append((long) b).append('-').append((long) e)
              .append('|').append(total[i])
              .append('|').append(Math.round(100.0 * enemyHalf[i] / total[i])).append('%')
              .append('|').append(Math.round(100.0 * deep[i] / total[i])).append('%')
              .append("|\n");
        }
        sb.append('\n');
    }

    private void appendQuestions(StringBuilder sb) {
        sb.append("## 待回答问题（必答）\n\n");
        sb.append("1. **出装决策**：装备节奏是否合理？每件装备的时机与选择（对照装备时间线与经济对比），是否有明显拖延或错误选择？\n");
        sb.append("2. **团战切入**：结合本选手参与的团战表与每分钟伤害，评价切入时机（先手/收割/观望/白给），关键团是否发挥作用；\n");
        sb.append("3. **打钱路线**：结合经济对比（是否停滞、何时被反超）与打钱/位置分析（各阶段在对方半场的比例），评价刷钱与地图控制；\n");
        sb.append("4. **关键决策**：击杀/阵亡时间线与位置揭示了哪些决策模式（单抓、推进、带线、回防）？这些决策是否转化为推塔/肉山/终结？\n");
        sb.append("5. **改进优先级**：给出一条按收益排序的可执行改进清单（每条必须引用上面数据）。\n");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private JsonNode resolveTarget(JsonNode metrics) {
        List<JsonNode> roster = new ArrayList<>();
        metrics.path("roster").forEach(roster::add);
        if (isDigits(selector)) {
            int idx = Integer.parseInt(selector);
            for (JsonNode p : roster) {
                if (p.path("player").asInt() == idx) {
                    return p;
                }
            }
            return null;
        }
        String sel = selector.trim().toLowerCase();
        JsonNode exact = null;
        JsonNode heroName = null;
        JsonNode nameSub = null;
        for (JsonNode p : roster) {
            if (p.path("hero_key").asText("").equalsIgnoreCase(sel)) {
                exact = p;
            }
            if (heroName == null && p.path("hero").asText("").toLowerCase().equals(sel)) {
                heroName = p;
            }
            if (nameSub == null && p.path("name").asText("").toLowerCase().contains(sel)) {
                nameSub = p;
            }
        }
        if (exact != null) {
            return exact;
        }
        if (heroName != null) {
            return heroName;
        }
        return nameSub;
    }

    private static String enemyTopEarner(Map<String, Integer> teamByHero, int team,
                                         Map<String, List<double[]>> cum) {
        String best = null;
        double bestV = -1;
        for (Map.Entry<String, Integer> e : teamByHero.entrySet()) {
            if (e.getValue() == team) {
                continue;
            }
            List<double[]> pts = cum.get(e.getKey());
            if (pts == null || pts.isEmpty()) {
                continue;
            }
            double last = pts.get(pts.size() - 1)[1];
            if (last > bestV) {
                bestV = last;
                best = e.getKey();
            }
        }
        return best;
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

    private static boolean isDigits(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }
}