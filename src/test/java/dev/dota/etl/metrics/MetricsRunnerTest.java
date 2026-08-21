package dev.dota.etl.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private ObjectNode runMetrics() throws Exception {
        Path combat = dir.resolve("combatlog.ndjson");
        Path players = dir.resolve("players.ndjson");

        List<String> combatRows = List.of(
            // two heroes trade damage in a 5s bucket -> one teamfight episode
            "{\"t\":100.0,\"type\":\"DOTA_COMBATLOG_DAMAGE\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"value\":120,\"attacker_team\":2,\"target_team\":3,\"x\":1.0,\"y\":2.0}",
            "{\"t\":101.0,\"type\":\"DOTA_COMBATLOG_DAMAGE\",\"attacker\":\"npc_dota_hero_axe\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_pudge\",\"target_hero\":true,\"value\":90,\"attacker_team\":3,\"target_team\":2}",
            "{\"t\":102.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"value\":300,\"attacker_team\":2,\"target_team\":3,\"networth\":1200,\"assists\":[0]}",
            "{\"t\":104.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_axe\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_pudge\",\"target_hero\":true,\"value\":300,\"attacker_team\":3,\"target_team\":2,\"networth\":1300,\"assists\":[1]}",
            "{\"t\":103.0,\"type\":\"DOTA_COMBATLOG_GOLD\",\"attacker\":\"dota_unknown\",\"target\":\"npc_dota_hero_pudge\",\"value\":250}",
            "{\"t\":103.5,\"type\":\"DOTA_COMBATLOG_XP\",\"attacker\":\"dota_unknown\",\"target\":\"npc_dota_hero_axe\",\"value\":180}",
            // exactly at the first episode end ([100,105)): must not leak into that fight
            "{\"t\":105.0,\"type\":\"DOTA_COMBATLOG_XP\",\"attacker\":\"dota_unknown\",\"target\":\"npc_dota_hero_pudge\",\"value\":999}",
            "{\"t\":105.0,\"type\":\"DOTA_COMBATLOG_DAMAGE\",\"attacker\":\"npc_dota_hero_lion\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_pudge\",\"target_hero\":true,\"value\":1,\"attacker_team\":3,\"target_team\":2}",
            "{\"t\":105.0,\"type\":\"DOTA_COMBATLOG_PURCHASE\",\"target\":\"npc_dota_hero_pudge\",\"value_name\":\"item_blinkdagger\"}",
            "{\"t\":106.0,\"type\":\"DOTA_COMBATLOG_PURCHASE\",\"target\":\"npc_dota_hero_pudge\",\"value_name\":\"item_blinkdagger\"}",
            // isolated activity later -> no episode merge across the gap
            "{\"t\":200.0,\"type\":\"DOTA_COMBATLOG_DAMAGE\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"value\":60,\"attacker_team\":2,\"target_team\":3}",
            "{\"t\":200.0,\"type\":\"DOTA_COMBATLOG_DAMAGE\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"value\":60,\"attacker_team\":2,\"target_team\":3}",
            "{\"t\":200.0,\"type\":\"DOTA_COMBATLOG_DAMAGE\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"value\":60,\"attacker_team\":2,\"target_team\":3}",
            "{\"t\":200.0,\"type\":\"DOTA_COMBATLOG_DAMAGE\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"value\":60,\"attacker_team\":2,\"target_team\":3}",
            "{\"t\":200.0,\"type\":\"DOTA_COMBATLOG_DAMAGE\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"value\":60,\"attacker_team\":2,\"target_team\":3}",
            "{\"t\":200.0,\"type\":\"DOTA_COMBATLOG_DAMAGE\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"value\":60,\"attacker_team\":2,\"target_team\":3}",
            "{\"t\":200.0,\"type\":\"DOTA_COMBATLOG_DAMAGE\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"value\":60,\"attacker_team\":2,\"target_team\":3}",
            "{\"t\":200.0,\"type\":\"DOTA_COMBATLOG_DAMAGE\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"value\":60,\"attacker_team\":2,\"target_team\":3}",
            "{\"t\":200.0,\"type\":\"DOTA_COMBATLOG_DAMAGE\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"value\":60,\"attacker_team\":2,\"target_team\":3}"
        );
        Files.write(combat, combatRows);

        List<String> playerRows = List.of(
            "{\"t\":0.0,\"tick\":0,\"player\":0}",
            "{\"t\":0.0,\"tick\":0,\"player\":1}",
            "{\"t\":100.0,\"tick\":3000,\"player\":0,\"team\":2,\"name\":\"alice\",\"hero\":\"Pudge\",\"level\":5,\"kills\":2,\"deaths\":1,\"assists\":3,\"x\":100.0,\"y\":100.0,\"z\":64.0,\"hp\":1000.0,\"max_hp\":1000.0,\"total_earned_gold\":1200,\"last_hits\":15,\"denies\":2}",
            "{\"t\":100.0,\"tick\":3000,\"player\":1,\"team\":3,\"name\":\"bob\",\"hero\":\"Axe\",\"level\":4,\"kills\":1,\"deaths\":2,\"assists\":1,\"x\":-100.0,\"y\":-100.0,\"z\":64.0,\"hp\":900.0,\"max_hp\":900.0,\"total_earned_gold\":1100,\"last_hits\":12,\"denies\":1}"
        );
        Files.write(players, playerRows);

        return new MetricsRunner(combat, players).run();
    }

    @Test
    void computesSummary() throws Exception {
        ObjectNode m = runMetrics();
        assertEquals(9, m.path("schema_version").asInt());
        assertEquals(5, m.path("parameters").path("teamfight_bucket_sec").asInt());
        com.fasterxml.jackson.databind.JsonNode s = m.path("summary");
        assertEquals(2, s.path("team_kills").size());
        assertEquals(2, s.path("team_kills").get(0).path("kills").asLong());   // final Axe deaths
        assertEquals(1, s.path("team_kills").get(1).path("kills").asLong());   // dire killed pudge
        assertEquals(0, s.path("roshan_kills").asLong());
        assertEquals(0.0, s.path("game_start_sec").asDouble(), 1e-6);
    }

    @Test
    void sqlTemplatesIgnoreDefaultLocale() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            ObjectNode m = runMetrics();
            assertEquals(2, m.path("kills").size());
            assertEquals(250, m.path("kills").get(0).path("killer_team_gold").asInt());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void computesRoster() throws Exception {
        ObjectNode m = runMetrics();
        assertEquals(2, m.path("roster").size());
        var p0 = m.path("roster").get(0);
        assertEquals("Pudge", p0.path("hero").asText());
        assertEquals("pudge", p0.path("hero_key").asText());
        assertEquals(5, p0.path("level").asInt());
        assertEquals(2, p0.path("kills").asInt());
        assertEquals(1, p0.path("deaths").asInt());
    }

    @Test
    void computesKills() throws Exception {
        ObjectNode m = runMetrics();
        assertEquals(2, m.path("kills").size());
        var first = m.path("kills").get(0);
        assertEquals("axe", first.path("victim_key").asText());
        assertEquals("pudge", first.path("killer_key").asText());
        assertEquals(1, first.path("assist_players").size());
    }

    @Test
    void infersLanesFromEarlyPositions() throws Exception {
        Path combat = dir.resolve("combatlog.ndjson");
        Path players = dir.resolve("players.ndjson");
        Files.write(combat, List.of(
            "{\"t\":100.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"attacker_team\":2,\"target_team\":3,\"value\":300,\"value_name\":\"none\",\"x\":1.0,\"y\":2.0,\"networth\":1200,\"assists\":[]}"
        ));
        // p0 bottom (x>0,y<0), p1 top (x<0,y>0), p2 mid (x,y same sign, near centre), p3 bottom (dire)
        double[][] pos = {{4000, -4000}, {-4000, 4000}, {-200, -300}, {4000, -4000}};
        int[] team = {2, 2, 2, 3};
        String[] hero = {"Pudge", "Axe", "Crystal Maiden", "Lion"};
        List<String> rows = new ArrayList<>();
        for (int p = 0; p < 4; p++) {
            for (int t = 10; t <= 90; t += 5) {
                rows.add("{\"t\":" + t + ".0,\"tick\":" + (t * 30) + ",\"player\":" + p
                    + ",\"team\":" + team[p] + ",\"name\":\"p" + p + "\",\"hero\":\"" + hero[p]
                    + "\",\"level\":1,\"kills\":0,\"deaths\":0,\"assists\":0"
                    + ",\"x\":" + pos[p][0] + ",\"y\":" + pos[p][1] + ",\"hp\":100.0"
                    + ",\"total_earned_gold\":" + (500 + p * 100) + ",\"last_hits\":" + (p + 3) + ",\"denies\":1}");
            }
        }
        Files.write(players, rows);

        ObjectNode m = new MetricsRunner(combat, players).run();
        assertEquals("bottom", m.path("roster").get(0).path("lane").asText());
        assertEquals("top", m.path("roster").get(1).path("lane").asText());
        assertEquals("mid", m.path("roster").get(2).path("lane").asText());
        assertEquals("bottom", m.path("roster").get(3).path("lane").asText());
        assertEquals(100, m.path("roster").get(0).path("lane_confidence").asInt());
    }

    @Test
    void computesTeamfights() throws Exception {
        ObjectNode m = runMetrics();
        assertEquals(2, m.path("teamfights").size(), "empty time buckets must split episodes");
        var tf = m.path("teamfights").get(0);
        assertEquals(100.0, tf.path("start").asDouble(), 1e-6);
        assertEquals(2, tf.path("deaths").asLong());
        assertTrue(tf.path("hero_damage").asLong() >= 210);
        assertEquals(2, tf.path("participants").size());
        // GOLD at t=103 (pudge, team 2) and XP at t=103.5 (axe, team 3) fall inside episode [100,105]
        var eco = tf.path("economy");
        assertEquals(250, eco.path("radiant").path("gold").asLong());
        assertEquals(0, eco.path("radiant").path("xp").asLong());
        assertEquals(0, eco.path("dire").path("gold").asLong());
        assertEquals(180, eco.path("dire").path("xp").asLong());
        assertEquals(250, eco.path("gold_delta").asLong());
        assertEquals(-180, eco.path("xp_delta").asLong());
    }

    @Test
    void computesObjectives() throws Exception {
        Path combat = dir.resolve("combatlog.ndjson");
        Path players = dir.resolve("players.ndjson");
        Files.write(combat, List.of(
            // roshan death (radiant last hit) + two building kills (radiant destroys dire top tower, then fort)
            "{\"t\":100.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_roshan\",\"target_hero\":false,\"attacker_team\":2,\"target_team\":0,\"value\":165,\"x\":-1000.0,\"y\":-1000.0,\"networth\":1000,\"assists\":[]}",
            "{\"t\":200.0,\"type\":\"DOTA_COMBATLOG_TEAM_BUILDING_KILL\",\"attacker\":\"dota_unknown\",\"attacker_hero\":false,\"target\":\"npc_dota_badguys_tower1_top\",\"target_hero\":false,\"attacker_team\":2,\"target_team\":3,\"value\":1}",
            "{\"t\":300.0,\"type\":\"DOTA_COMBATLOG_TEAM_BUILDING_KILL\",\"attacker\":\"dota_unknown\",\"attacker_hero\":false,\"target\":\"npc_dota_badguys_fort\",\"target_hero\":false,\"attacker_team\":2,\"target_team\":3,\"value\":3}",
            "{\"t\":301.0,\"type\":\"DOTA_COMBATLOG_PURCHASE\",\"target\":\"npc_dota_hero_pudge\",\"target_key\":\"pudge\",\"value_name\":\"item_blinkdagger\"}"
        ));
        Files.write(players, List.of(
            "{\"t\":100.0,\"tick\":3000,\"player\":0,\"team\":2,\"name\":\"alice\",\"hero\":\"Pudge\",\"level\":5,\"kills\":0,\"deaths\":0,\"assists\":0,\"x\":100.0,\"y\":100.0,\"z\":64.0,\"hp\":1000.0,\"max_hp\":1000.0,\"total_earned_gold\":600,\"last_hits\":0,\"denies\":0}"
        ));

        ObjectNode m = new MetricsRunner(combat, players).run();
        assertEquals(1, m.path("summary").path("roshan_kills").asLong());
        var obj = m.path("objectives");
        var rosh = obj.path("roshan_kills");
        assertEquals(1, rosh.size());
        assertEquals(100.0, rosh.get(0).path("t").asDouble(), 1e-6);
        assertEquals("pudge", rosh.get(0).path("killer_key").asText());
        assertEquals("radiant", rosh.get(0).path("side").asText());
        var bld = obj.path("building_kills");
        assertEquals(2, bld.size());
        assertEquals("badguys_tower1_top", bld.get(0).path("building_key").asText());
        assertEquals("tower", bld.get(0).path("kind").asText());
        assertEquals("dire", bld.get(0).path("owner_side").asText());
        assertEquals("radiant", bld.get(0).path("destroyer_side").asText());
        assertEquals("badguys_fort", bld.get(1).path("building_key").asText());
        assertEquals("ancient", bld.get(1).path("kind").asText());
    }

    @Test
    void computesFarmCurves() throws Exception {
        Path combat = dir.resolve("combatlog.ndjson");
        Path players = dir.resolve("players.ndjson");
        Files.write(combat, List.of(
            "{\"t\":100.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"attacker_team\":2,\"target_team\":3,\"value\":300,\"value_name\":\"none\",\"x\":1.0,\"y\":2.0,\"networth\":1200,\"assists\":[]}"
        ));
        // two samples per hero: values must be monotonic across buckets (MAX picks the latest)
        Files.write(players, List.of(
            "{\"t\":10.0,\"tick\":1,\"player\":0,\"team\":2,\"name\":\"alice\",\"hero\":\"Pudge\",\"level\":1,\"kills\":0,\"deaths\":0,\"assists\":0,\"x\":100.0,\"y\":100.0,\"hp\":100.0,\"total_earned_gold\":600,\"last_hits\":0,\"denies\":0}",
            "{\"t\":80.0,\"tick\":2,\"player\":0,\"team\":2,\"name\":\"alice\",\"hero\":\"Pudge\",\"level\":1,\"kills\":0,\"deaths\":0,\"assists\":0,\"x\":100.0,\"y\":100.0,\"hp\":100.0,\"total_earned_gold\":800,\"last_hits\":5,\"denies\":1}",
            "{\"t\":10.0,\"tick\":1,\"player\":1,\"team\":3,\"name\":\"bob\",\"hero\":\"Axe\",\"level\":1,\"kills\":0,\"deaths\":0,\"assists\":0,\"x\":-100.0,\"y\":-100.0,\"hp\":100.0,\"total_earned_gold\":600,\"last_hits\":0,\"denies\":0}",
            "{\"t\":70.0,\"tick\":2,\"player\":1,\"team\":3,\"name\":\"bob\",\"hero\":\"Axe\",\"level\":1,\"kills\":0,\"deaths\":0,\"assists\":0,\"x\":-100.0,\"y\":-100.0,\"hp\":100.0,\"total_earned_gold\":700,\"last_hits\":4,\"denies\":1}"
        ));

        ObjectNode m = new MetricsRunner(combat, players).run();
        assertEquals(2, m.path("farm_curves").size());
        var pudge = m.path("farm_curves").get(1);
        assertEquals("pudge", pudge.path("hero").asText());
        assertEquals(2, pudge.path("points").size());
        assertEquals(30, pudge.path("points").get(0).path("t").asInt(), "bucket 0 centre = 30s");
        assertEquals(600, pudge.path("points").get(0).path("total_earned_gold").asInt());
        assertEquals(90, pudge.path("points").get(1).path("t").asInt(), "bucket 1 centre = 90s");
        assertEquals(800, pudge.path("points").get(1).path("total_earned_gold").asInt());
        assertEquals(5, pudge.path("points").get(1).path("last_hits").asInt());
        assertEquals(1, pudge.path("points").get(1).path("denies").asInt());
        var axe = m.path("farm_curves").get(0);
        assertEquals("axe", axe.path("hero").asText());
        assertEquals(4, axe.path("points").get(1).path("last_hits").asInt());
    }

    @Test
    void computesDeathCosts() throws Exception {
        Path combat = dir.resolve("combatlog.ndjson");
        Path players = dir.resolve("players.ndjson");
        Files.write(combat, List.of(
            // kill 1: pudge (team 2) kills axe; killer team gains gold/xp right after, then concedes a building
            "{\"t\":100.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"attacker_team\":2,\"target_team\":3,\"value\":300,\"value_name\":\"none\",\"x\":1.0,\"y\":2.0,\"networth\":1200,\"assists\":[]}",
            "{\"t\":100.5,\"type\":\"DOTA_COMBATLOG_GOLD\",\"target\":\"npc_dota_hero_pudge\",\"target_key\":\"pudge\",\"value\":250,\"gold_reason\":12}",
            "{\"t\":100.8,\"type\":\"DOTA_COMBATLOG_XP\",\"target\":\"npc_dota_hero_pudge\",\"target_key\":\"pudge\",\"value\":100}",
            "{\"t\":101.0,\"type\":\"DOTA_COMBATLOG_GOLD\",\"target\":\"npc_dota_hero_axe\",\"target_key\":\"axe\",\"value\":30,\"gold_reason\":12}",
            "{\"t\":112.0,\"type\":\"DOTA_COMBATLOG_TEAM_BUILDING_KILL\",\"target\":\"npc_dota_badguys_tower1_top\",\"target_key\":\"badguys_tower1_top\",\"target_team\":3,\"attacker_team\":2}",
            // kill 2: axe (team 3) kills pudge; killer team takes roshan within 20s
            "{\"t\":120.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_axe\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_pudge\",\"target_hero\":true,\"attacker_team\":3,\"target_team\":2,\"value\":300,\"value_name\":\"none\",\"x\":1.0,\"y\":2.0,\"networth\":1300,\"assists\":[]}",
            "{\"t\":121.5,\"type\":\"DOTA_COMBATLOG_GOLD\",\"target\":\"npc_dota_hero_axe\",\"target_key\":\"axe\",\"value\":300,\"gold_reason\":12}",
            "{\"t\":125.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_roshan\",\"target_hero\":false,\"attacker_team\":3,\"target_team\":0,\"value\":165,\"x\":-1000.0,\"y\":-1000.0,\"networth\":1000,\"assists\":[]}"
        ));
        Files.write(players, List.of(
            "{\"t\":0.0,\"tick\":0,\"player\":0,\"team\":2,\"name\":\"alice\",\"hero\":\"Pudge\",\"level\":5,\"kills\":0,\"deaths\":0,\"assists\":0,\"x\":100.0,\"y\":100.0,\"z\":64.0,\"hp\":1000.0,\"max_hp\":1000.0,\"total_earned_gold\":1200,\"last_hits\":15,\"denies\":2}",
            "{\"t\":0.0,\"tick\":0,\"player\":1,\"team\":3,\"name\":\"bob\",\"hero\":\"Axe\",\"level\":4,\"kills\":0,\"deaths\":0,\"assists\":0,\"x\":-100.0,\"y\":-100.0,\"z\":64.0,\"hp\":900.0,\"max_hp\":900.0,\"total_earned_gold\":1100,\"last_hits\":12,\"denies\":1}"));
        ObjectNode m = new MetricsRunner(combat, players).run();
        assertEquals(2, m.path("kills").size());
        JsonNode k0 = m.path("kills").get(0);
        assertEquals(0, k0.path("kill_id").asInt());
        assertEquals(250, k0.path("killer_team_gold").asInt(), "gold only attributed to killer team in window");
        assertEquals(100, k0.path("killer_team_xp").asInt());
        assertEquals("building", k0.path("conceded_objective").path("kind").asText());
        assertEquals("badguys_tower1_top", k0.path("conceded_objective").path("target_key").asText());
        JsonNode k1 = m.path("kills").get(1);
        assertEquals(1, k1.path("kill_id").asInt());
        assertEquals(300, k1.path("killer_team_gold").asInt());
        assertEquals("roshan", k1.path("conceded_objective").path("kind").asText());
    }

    @Test
    void teamKillsUseAttackerTeam() throws Exception {
        Path combat = dir.resolve("combatlog.ndjson");
        Path players = dir.resolve("players.ndjson");
        Files.write(combat, List.of(
            // pudge (team 2) kills axe (team 3) twice, axe kills pudge once
            "{\"t\":100.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"attacker_team\":2,\"target_team\":3,\"value\":300,\"x\":1.0,\"y\":2.0,\"networth\":1200,\"assists\":[0]}",
            "{\"t\":101.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"attacker_team\":2,\"target_team\":3,\"value\":300,\"x\":1.0,\"y\":2.0}",
            "{\"t\":102.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_axe\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_pudge\",\"target_hero\":true,\"attacker_team\":3,\"target_team\":2,\"value\":300,\"x\":1.0,\"y\":2.0}",
            "{\"t\":103.0,\"type\":\"DOTA_COMBATLOG_PURCHASE\",\"target\":\"npc_dota_hero_pudge\",\"target_key\":\"pudge\",\"value_name\":\"item_blinkdagger\"}"
        ));
        Files.write(players, List.of(
            "{\"t\":0.0,\"tick\":0,\"player\":0,\"team\":2,\"name\":\"alice\",\"hero\":\"Pudge\",\"level\":5,\"kills\":2,\"deaths\":1,\"assists\":3,\"x\":100.0,\"y\":100.0,\"z\":64.0,\"hp\":1000.0,\"max_hp\":1000.0,\"total_earned_gold\":1200,\"last_hits\":15,\"denies\":2}",
            "{\"t\":0.0,\"tick\":0,\"player\":1,\"team\":3,\"name\":\"bob\",\"hero\":\"Axe\",\"level\":4,\"kills\":1,\"deaths\":2,\"assists\":1,\"x\":-100.0,\"y\":-100.0,\"z\":64.0,\"hp\":900.0,\"max_hp\":900.0,\"total_earned_gold\":1100,\"last_hits\":12,\"denies\":1}"));

        ObjectNode m = new MetricsRunner(combat, players).run();
        var kills = m.path("summary").path("team_kills");
        // team_kills must count kills BY each team (attacker), not deaths (victim)
        assertEquals(2, kills.get(0).path("kills").asLong());
        assertEquals(1, kills.get(1).path("kills").asLong());
        assertEquals("radiant", kills.get(0).path("side").asText());
        assertEquals("dire", kills.get(1).path("side").asText());
    }

    @Test
    void computesCurvesAndItems() throws Exception {
        ObjectNode m = runMetrics();
        assertTrue(m.path("gold_curves").size() >= 1);
        var gold = m.path("gold_curves").get(0);
        assertTrue(gold.path("points").size() >= 1);
        assertEquals(250, gold.path("points").get(0).path("gold").asLong());

        assertTrue(m.path("xp_curves").size() >= 1);

        assertTrue(m.path("item_timeline").size() >= 1);
        assertEquals("item_blinkdagger",
            m.path("item_timeline").get(0).path("items").get(0).path("item").asText());
        assertEquals(2, m.path("item_timeline").get(0).path("items").size(),
            "repeated purchases must be retained");
    }

    @Test
    void goldCurveUsesLastCumulativeValueInBucket() throws Exception {
        Path combat = dir.resolve("combatlog.ndjson");
        Path players = dir.resolve("players.ndjson");
        Files.write(combat, List.of(
            "{\"t\":10.0,\"type\":\"DOTA_COMBATLOG_GOLD\",\"target\":\"npc_dota_hero_pudge\",\"value\":300,\"attacker_hero\":false,\"target_hero\":true,\"attacker_team\":0,\"target_team\":2}",
            "{\"t\":20.0,\"type\":\"DOTA_COMBATLOG_GOLD\",\"target\":\"npc_dota_hero_pudge\",\"value\":-100,\"attacker_hero\":false,\"target_hero\":true,\"attacker_team\":0,\"target_team\":2}",
            "{\"t\":20.0,\"type\":\"DOTA_COMBATLOG_GOLD\",\"target\":\"npc_dota_hero_pudge\",\"value\":50,\"attacker_hero\":false,\"target_hero\":true,\"attacker_team\":0,\"target_team\":2}",
            "{\"t\":20.0,\"type\":\"DOTA_COMBATLOG_GOLD\",\"target\":\"npc_dota_hero_pudge\",\"value\":-25,\"attacker_hero\":false,\"target_hero\":true,\"attacker_team\":0,\"target_team\":2}",
            "{\"t\":100.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_axe\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_pudge\",\"target_hero\":true,\"attacker_team\":3,\"target_team\":2,\"value\":300,\"value_name\":null,\"x\":1.0,\"y\":2.0,\"networth\":800,\"assists\":[]}"
        ));
        Files.write(players, List.of(
            "{\"t\":0.0,\"tick\":0,\"player\":0,\"team\":2,\"name\":\"alice\",\"hero\":\"Pudge\",\"level\":1,\"kills\":0,\"deaths\":1,\"assists\":0,\"x\":100.0,\"y\":100.0,\"hp\":100.0,\"total_earned_gold\":800,\"last_hits\":0,\"denies\":0}"
        ));

        ObjectNode m = new MetricsRunner(combat, players).run();
        assertEquals(225, m.path("gold_curves").get(0).path("points").get(0).path("gold").asLong(),
            "bucket value must be the cumulative value at the last event, not the peak");
    }

    @Test
    void normalizesGameClockAndUsesReplayWinner() throws Exception {
        Path combat = dir.resolve("combatlog.ndjson");
        Path players = dir.resolve("players.ndjson");
        Files.write(combat, List.of(
            "{\"t\":300.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"attacker_team\":2,\"target_team\":3,\"value\":300,\"value_name\":\"none\",\"x\":1,\"y\":2,\"networth\":1000,\"assists\":[]}"
        ));
        Files.write(players, List.of(
            "{\"t\":300.0,\"tick\":1,\"player\":0,\"team\":2,\"name\":\"alice\",\"hero\":\"Pudge\",\"level\":1,\"kills\":1,\"deaths\":0,\"assists\":0,\"x\":1,\"y\":1,\"hp\":100,\"total_earned_gold\":800,\"last_hits\":6,\"denies\":0}",
            "{\"t\":300.0,\"tick\":1,\"player\":1,\"team\":3,\"name\":\"bob\",\"hero\":\"Axe\",\"level\":1,\"kills\":0,\"deaths\":1,\"assists\":0,\"x\":2,\"y\":2,\"hp\":0,\"total_earned_gold\":700,\"last_hits\":5,\"denies\":1}"
        ));
        Files.writeString(dir.resolve("match.json"),
            "{\"game_start_time_raw\":280.0,\"game_end_time_raw\":880.0," +
            "\"game_duration_sec\":600.0,\"winner_team\":2," +
            "\"radiant_score\":9,\"dire_score\":7}");

        ObjectNode m = new MetricsRunner(combat, players).run();
        assertEquals(20.0, m.path("kills").get(0).path("t").asDouble(), 1e-6);
        assertEquals(300.0, m.path("kills").get(0).path("raw_t").asDouble(), 1e-6);
        assertEquals(600.0, m.path("summary").path("duration_sec").asDouble(), 1e-6);
        assertEquals(2, m.path("summary").path("winner_team").asInt());
        assertEquals(9, m.path("summary").path("team_kills").get(0).path("kills").asInt());
        assertEquals(7, m.path("summary").path("team_kills").get(1).path("kills").asInt());
    }

    @Test
    void rejectsCombatLogMissingCriticalColumns() throws Exception {
        Path combat = dir.resolve("combatlog.ndjson");
        Path players = dir.resolve("players.ndjson");
        // missing attacker_team / target_team / attacker_hero would silently skew team_kills and damage
        Files.write(combat, List.of(
            "{\"t\":100.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_pudge\",\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"value\":300}"
        ));
        Files.write(players, List.of(
            "{\"t\":100.0,\"tick\":3000,\"player\":0,\"team\":2,\"name\":\"alice\",\"hero\":\"Pudge\",\"level\":5,\"kills\":2,\"deaths\":1,\"assists\":3,\"total_earned_gold\":1200,\"last_hits\":15,\"denies\":2}"
        ));
        assertThrows(IllegalArgumentException.class,
            () -> new MetricsRunner(combat, players).run());
    }

    @Test
    void persistsAllDerivedMetricTables() throws Exception {
        ObjectNode m = runMetrics();
        Path db = dir.resolve("metrics.duckdb");
        assertTrue(Files.exists(db), "expected a persisted metrics.duckdb");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + db)) {
            List<String> tables = new ArrayList<>();
            try (var rs = conn.createStatement().executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema='main' ORDER BY table_name")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String t : List.of("combatlog", "players", "kills", "hero_damage",
                "gold_curves", "xp_curves", "item_timeline", "damage", "damage_per_minute",
                "teamfights", "teamfight_economy", "roshan_kills", "building_kills", "farm_curves",
                "roster", "lanes", "death_costs", "conceded_objectives")) {
                assertTrue(tables.contains(t), "expected persisted table " + t + " but got " + tables);
            }
            long teamfights = 0;
            try (var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM teamfights")) {
                if (rs.next()) {
                    teamfights = rs.getLong(1);
                }
            }
            assertEquals(m.path("teamfights").size(), teamfights);
            long ecoRows = 0;
            long radGold = -1;
            long direXp = -1;
            try (var rs = conn.createStatement().executeQuery(
                "SELECT id, team, gold, xp FROM teamfight_economy ORDER BY id, team")) {
                while (rs.next()) {
                    ecoRows++;
                    if (rs.getLong(1) == 0 && rs.getInt(2) == 2) {
                        radGold = rs.getLong(3);
                    }
                    if (rs.getLong(1) == 0 && rs.getInt(2) == 3) {
                        direXp = rs.getLong(4);
                    }
                }
            }
            assertEquals(2, ecoRows, "one fight with both teams gaining gold/xp");
            assertEquals(250, radGold);
            assertEquals(180, direXp);
            long damageHeroes = 0;
            try (var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM damage")) {
                if (rs.next()) {
                    damageHeroes = rs.getLong(1);
                }
            }
            assertTrue(damageHeroes >= 2, "damage table should cover both heroes");
            long goldHeroes = 0;
            try (var rs = conn.createStatement().executeQuery("SELECT COUNT(DISTINCT hero) FROM gold_curves")) {
                if (rs.next()) {
                    goldHeroes = rs.getLong(1);
                }
            }
            assertTrue(goldHeroes >= 1, "gold_curves should contain at least one hero");
            long farmHeroes = 0;
            try (var rs = conn.createStatement().executeQuery("SELECT COUNT(DISTINCT hero) FROM farm_curves")) {
                if (rs.next()) {
                    farmHeroes = rs.getLong(1);
                }
            }
            assertTrue(farmHeroes >= 2, "farm_curves should contain both heroes");
            long rosterRows = 0;
            try (var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM roster")) {
                if (rs.next()) {
                    rosterRows = rs.getLong(1);
                }
            }
            assertEquals(2, rosterRows, "roster should contain both heroes");
            long costRows = 0;
            try (var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM death_costs")) {
                if (rs.next()) {
                    costRows = rs.getLong(1);
                }
            }
            assertEquals(m.path("kills").size(), costRows, "death_costs should cover every kill");
            long joinedCosts = 0;
            try (var rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM kills k JOIN death_costs d USING (kill_id)")) {
                if (rs.next()) {
                    joinedCosts = rs.getLong(1);
                }
            }
            assertEquals(costRows, joinedCosts, "kill_id should join kills to death_costs without loss");
        }
    }

    @Test
    void rejectsPlayersMissingTeam() throws Exception {
        Path combat = dir.resolve("combatlog.ndjson");
        Path players = dir.resolve("players.ndjson");
        Files.write(combat, List.of(
            "{\"t\":100.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"attacker_team\":2,\"target_team\":3,\"value\":300}"
        ));
        // player rows carry no team -> side attribution in roster / team_kills would be broken
        Files.write(players, List.of(
            "{\"t\":100.0,\"tick\":3000,\"player\":0,\"name\":\"alice\",\"hero\":\"Pudge\",\"level\":5,\"kills\":2,\"deaths\":1,\"assists\":3,\"total_earned_gold\":1200,\"last_hits\":15,\"denies\":2}"
        ));
        assertThrows(IllegalArgumentException.class,
            () -> new MetricsRunner(combat, players).run());
    }
}
