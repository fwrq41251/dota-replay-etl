package dev.dota.etl.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerReviewGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private Path writeFixture() throws Exception {
        ObjectNode metrics = MAPPER.createObjectNode();

        ObjectNode summary = metrics.putObject("summary");
        summary.put("duration_sec", 300.0);
        summary.put("game_start_sec", 300.0);
        summary.put("game_end_sec", 600.0);
        ArrayNode teamKills = summary.putArray("team_kills");
        teamKills.addObject().put("team", 2).put("kills", 6);
        teamKills.addObject().put("team", 3).put("kills", 3);

        ArrayNode roster = metrics.putArray("roster");
        roster.addObject().put("player", 0).put("team", 2).put("name", "alice")
              .put("hero", "Pudge").put("hero_key", "pudge")
              .put("kills", 6).put("deaths", 1).put("assists", 5).put("level", 12);
        roster.addObject().put("player", 1).put("team", 3).put("name", "bob")
              .put("hero", "Axe").put("hero_key", "axe")
              .put("kills", 3).put("deaths", 6).put("assists", 2).put("level", 10);

        ArrayNode gold = metrics.putArray("gold_curves");
        ObjectNode gc = gold.addObject();
        gc.put("hero", "pudge");
        ArrayNode pts = gc.putArray("points");
        pts.addObject().put("t", 795.0).put("gold", 600);
        pts.addObject().put("t", 825.0).put("gold", 1200);
        pts.addObject().put("t", 855.0).put("gold", 2500);
        ObjectNode gc2 = gold.addObject();
        gc2.put("hero", "axe");
        ArrayNode pts2 = gc2.putArray("points");
        pts2.addObject().put("t", 795.0).put("gold", 600);
        pts2.addObject().put("t", 825.0).put("gold", 1000);
        pts2.addObject().put("t", 855.0).put("gold", 1800);

        ArrayNode kills = metrics.putArray("kills");
        ObjectNode k = kills.addObject();
        k.put("t", 120.5).put("killer", "npc_dota_hero_pudge").put("victim", "npc_dota_hero_axe")
         .put("killer_key", "pudge").put("victim_key", "axe")
         .put("killer_team", 2).put("victim_team", 3).put("victim_networth", 800);
        ArrayNode loc = k.putArray("location");
        loc.add(-2500.0).add(1000.0);
        ObjectNode k2 = kills.addObject();
        k2.put("t", 200.0).put("killer", "npc_dota_hero_axe").put("victim", "npc_dota_hero_pudge")
          .put("killer_key", "axe").put("victim_key", "pudge")
          .put("killer_team", 3).put("victim_team", 2).put("victim_networth", 1200);

        ArrayNode tfs = metrics.putArray("teamfights");
        ObjectNode tf = tfs.addObject();
        tf.put("id", 0).put("start", 115.0).put("end", 125.0).put("duration", 10.0)
           .put("hero_damage", 210).put("deaths", 1);
        ArrayNode part = tf.putArray("participants");
        part.add("axe").add("pudge");

        ObjectNode item = metrics.putArray("item_timeline").addObject();
        item.put("hero", "pudge");
        ArrayNode items = item.putArray("items");
        items.addObject().put("item", "item_blinkdagger").put("t", 700.0);
        items.addObject().put("item", "item_magic_stick").put("t", 200.0);

        ArrayNode damage = metrics.putArray("damage");
        ObjectNode dmg = damage.addObject();
        dmg.put("hero", "pudge").put("dealt_total", 1000).put("taken_total", 500);
        ArrayNode perMin = dmg.putArray("per_minute");
        perMin.addObject().put("min", 14).put("dealt", 600);
        perMin.addObject().put("min", 15).put("dealt", 400);
        ObjectNode dmg2 = damage.addObject();
        dmg2.put("hero", "axe").put("dealt_total", 700).put("taken_total", 900);
        dmg2.putArray("per_minute");

        Path metricsJson = dir.resolve("metrics.json");
        Files.writeString(metricsJson, MAPPER.writeValueAsString(metrics));
        Path matchJson = dir.resolve("match.json");
        Files.writeString(matchJson, "{\"match_id\": 12345}");
        Files.writeString(dir.resolve("players.ndjson"), String.join("\n",
            "{\"t\":350,\"player\":0,\"x\":-2000,\"y\":-2000}",
            "{\"t\":450,\"player\":0,\"x\":2000,\"y\":2000}",
            "{\"t\":550,\"player\":0,\"x\":3000,\"y\":3000}",
            "{\"t\":350,\"player\":1,\"x\":5000,\"y\":5000}") + "\n");
        Files.writeString(dir.resolve("combatlog.ndjson"), String.join("\n",
            "{\"t\":195,\"type\":\"DOTA_COMBATLOG_ABILITY\",\"attacker\":\"npc_dota_hero_pudge\",\"target\":\"dota_unknown\",\"inflictor\":\"pudge_meat_hook\"}",
            "{\"t\":198,\"type\":\"DOTA_COMBATLOG_MODIFIER_ADD\",\"attacker\":\"npc_dota_hero_axe\",\"target\":\"npc_dota_hero_pudge\",\"inflictor\":\"modifier_stunned\"}",
            "{\"t\":199,\"type\":\"DOTA_COMBATLOG_DAMAGE\",\"attacker\":\"npc_dota_hero_axe\",\"target\":\"npc_dota_hero_pudge\",\"inflictor\":\"axe_culling_blade\",\"value\":500,\"health\":0}",
            "{\"t\":200,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_axe\",\"target\":\"npc_dota_hero_pudge\"}") + "\n");
        return metricsJson;
    }

    @Test
    void promptContainsSinglePlayerFacts() throws Exception {
        writeFixture();
        PlayerReviewGenerator gen = new PlayerReviewGenerator(dir, "pudge");
        String prompt = gen.generatePrompt();

        assertTrue(prompt.contains("单选手复盘指令（pudge）"), "title");
        assertTrue(prompt.contains("alice"), "player name");
        assertTrue(prompt.contains("天辉"), "side");
        assertTrue(prompt.contains("6/1/5"), "kda");
        assertTrue(prompt.contains("团队结果：未知（不得由比分推断）"), "does not infer winner from score");
        assertTrue(prompt.contains("blinkdagger"), "item row");
        assertTrue(prompt.contains("击杀（1 个）"), "kill count");
        assertTrue(prompt.contains("阵亡（1 次）"), "death count");
        assertTrue(prompt.contains("阵亡前 15 秒事件证据"), "death evidence section");
        assertTrue(prompt.contains("pudge_meat_hook"), "pre-death cast evidence");
        assertTrue(prompt.contains("stunned（axe）"), "pre-death control evidence");
        assertTrue(prompt.contains("|axe|"), "kill/death victim/killer");
        assertTrue(prompt.contains("经济对比"), "economy section");
        assertTrue(prompt.contains("|15|2500|1800|"), "economy row value");
        assertTrue(prompt.contains("对英雄总伤害：1000"), "damage total");
        assertTrue(prompt.contains("有实质参与的交战窗口"), "teamfight section");
        assertTrue(prompt.contains("个人输出/承伤"), "personal fight evidence");
        assertTrue(prompt.contains("打钱/位置分析"), "position section");
        assertTrue(prompt.contains("100%"), "enemy half percentage");
        assertTrue(prompt.contains("出装决策"), "question 1");
        assertTrue(prompt.contains("改进优先级"), "question 5");
        assertTrue(Files.exists(dir.resolve("player-review-pudge.md")));
    }

    @Test
    void resolvesByPlayerIndex() throws Exception {
        writeFixture();
        PlayerReviewGenerator gen = new PlayerReviewGenerator(dir, "1");
        String prompt = gen.generatePrompt();
        assertTrue(prompt.contains("单选手复盘指令（axe）"), "selector by index");
    }

    @Test
    void unknownSelectorThrows() throws Exception {
        writeFixture();
        PlayerReviewGenerator gen = new PlayerReviewGenerator(dir, "nobody");
        boolean threw = false;
        try {
            gen.generatePrompt();
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw, "unknown selector must fail");
    }
}
