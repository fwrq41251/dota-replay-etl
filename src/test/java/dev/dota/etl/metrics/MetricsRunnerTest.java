package dev.dota.etl.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            "{\"t\":105.0,\"type\":\"DOTA_COMBATLOG_PURCHASE\",\"target\":\"npc_dota_hero_pudge\",\"value_name\":\"item_blinkdagger\"}",
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
            "{\"t\":100.0,\"tick\":3000,\"player\":0,\"team\":2,\"name\":\"alice\",\"hero\":\"Pudge\",\"level\":5,\"kills\":2,\"deaths\":1,\"assists\":3,\"x\":100.0,\"y\":100.0,\"z\":64.0,\"hp\":1000.0,\"max_hp\":1000.0}",
            "{\"t\":100.0,\"tick\":3000,\"player\":1,\"team\":3,\"name\":\"bob\",\"hero\":\"Axe\",\"level\":4,\"kills\":1,\"deaths\":2,\"assists\":1,\"x\":-100.0,\"y\":-100.0,\"z\":64.0,\"hp\":900.0,\"max_hp\":900.0}"
        );
        Files.write(players, playerRows);

        return new MetricsRunner(combat, players).run();
    }

    @Test
    void computesSummary() throws Exception {
        ObjectNode m = runMetrics();
        com.fasterxml.jackson.databind.JsonNode s = m.path("summary");
        assertEquals(2, s.path("team_kills").size());
        assertEquals(2, s.path("team_kills").get(0).path("kills").asLong());   // final Axe deaths
        assertEquals(1, s.path("team_kills").get(1).path("kills").asLong());   // dire killed pudge
        assertEquals(0, s.path("roshan_kills").asLong());
        assertEquals(0.0, s.path("game_start_sec").asDouble(), 1e-6);
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
    void computesTeamfights() throws Exception {
        ObjectNode m = runMetrics();
        assertTrue(m.path("teamfights").size() >= 1);
        var tf = m.path("teamfights").get(0);
        assertEquals(100.0, tf.path("start").asDouble(), 1e-6);
        assertEquals(2, tf.path("deaths").asLong());
        assertTrue(tf.path("hero_damage").asLong() >= 210);
        assertEquals(2, tf.path("participants").size());
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
            "{\"t\":0.0,\"tick\":0,\"player\":0,\"team\":2,\"name\":\"alice\",\"hero\":\"Pudge\",\"level\":5,\"kills\":2,\"deaths\":1,\"assists\":3,\"x\":100.0,\"y\":100.0,\"z\":64.0,\"hp\":1000.0,\"max_hp\":1000.0}",
            "{\"t\":0.0,\"tick\":0,\"player\":1,\"team\":3,\"name\":\"bob\",\"hero\":\"Axe\",\"level\":4,\"kills\":1,\"deaths\":2,\"assists\":1,\"x\":-100.0,\"y\":-100.0,\"z\":64.0,\"hp\":900.0,\"max_hp\":900.0}"));

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
    }

    @Test
    void normalizesGameClockAndUsesReplayWinner() throws Exception {
        Path combat = dir.resolve("combatlog.ndjson");
        Path players = dir.resolve("players.ndjson");
        Files.write(combat, List.of(
            "{\"t\":300.0,\"type\":\"DOTA_COMBATLOG_DEATH\",\"attacker\":\"npc_dota_hero_pudge\",\"attacker_hero\":true,\"target\":\"npc_dota_hero_axe\",\"target_hero\":true,\"attacker_team\":2,\"target_team\":3,\"value\":300,\"value_name\":\"none\",\"x\":1,\"y\":2,\"networth\":1000,\"assists\":[]}"
        ));
        Files.write(players, List.of(
            "{\"t\":300.0,\"tick\":1,\"player\":0,\"team\":2,\"name\":\"alice\",\"hero\":\"Pudge\",\"level\":1,\"kills\":1,\"deaths\":0,\"assists\":0,\"x\":1,\"y\":1,\"hp\":100}",
            "{\"t\":300.0,\"tick\":1,\"player\":1,\"team\":3,\"name\":\"bob\",\"hero\":\"Axe\",\"level\":1,\"kills\":0,\"deaths\":1,\"assists\":0,\"x\":2,\"y\":2,\"hp\":0}"
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
}
