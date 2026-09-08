package dev.dota.etl.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class LocalFightBuilderTest {
    @ParameterizedTest
    @ValueSource(strings={"future","missing","NaN","age"})
    void memberPositionsUseActualClockForBothBodiesAndBlockInvalidLatest(String scenario) throws Exception {
        sample("a",2,8,100); sample("b",3,8,100);
        sample("a",2,10,100); sample("b",3,10,100);
        conn.createStatement().execute("UPDATE players_v SET equipment=json_object('observed_raw_t',t)");
        String observed=switch(scenario) {case "missing" -> "null";case "NaN" -> "\"NaN\"";default -> "12";};
        conn.createStatement().execute("UPDATE players_v SET equipment='{\"observed_raw_t\":"+observed+"}' WHERE t=10");
        event(scenario.equals("future")?11:13,"a","b","roster_team_match",null);
        var e=run().path("local_fight_events").get(0);
        if(scenario.equals("age")) {
            assertEquals(12,e.path("sample_t").asDouble());
            assertEquals(12,e.path("attacker_sample_t").asDouble());
            assertEquals(1,e.path("location_age_sec").asDouble());
            assertEquals(1,e.path("attacker_location_age_sec").asDouble());
        } else if(scenario.equals("future")) {
            assertEquals(8,e.path("sample_t").asDouble()); // older genuine observation, never the future position
        } else {
            assertTrue(e.path("sample_t").isNull());
            assertTrue(e.path("attacker_presence_key").isNull());
            assertTrue(e.path("fight_id").isNull());
        }
    }
    private Connection conn;
    private long id;

    @BeforeEach
    void setup() throws Exception {
        conn = DriverManager.getConnection("jdbc:duckdb:");
        conn.createStatement().execute("""
            CREATE TABLE combatlog_v(event_id BIGINT,t DOUBLE,raw_t DOUBLE,type VARCHAR,value DOUBLE,
              attacker VARCHAR,target VARCHAR,credited_attacker_key VARCHAR,attribution_source VARCHAR,
              target_player_key VARCHAR,attacker_team INT,target_team INT,attacker_hero BOOLEAN);
            CREATE TABLE players_v(t DOUBLE,tick INT,player INT,hero_key VARCHAR,team INT,x DOUBLE,y DOUBLE,hp INT,equipment JSON);
            CREATE TABLE hero_death_events(event_id BIGINT,kill_id BIGINT,death_class VARCHAR);
            CREATE TABLE hero_team AS SELECT * FROM (VALUES ('a',2),('b',3),('c',2),('d',3),('owner',2)) h(hero_key,team);
            """);
    }

    @AfterEach
    void close() throws Exception { conn.close(); }

    private void sample(String hero, int team, double t, double x) throws Exception {
        try (var st = conn.prepareStatement("INSERT INTO players_v VALUES (?, ?, 0, ?, ?, ?, 100, 100,?::JSON)")) {
            st.setDouble(1,t); st.setInt(2,(int)(t*30)); st.setString(3,hero);
            st.setInt(4,team); st.setDouble(5,x); st.setString(6,"{\"observed_raw_t\":"+t+"}"); st.execute();
        }
    }

    private void event(double t, String attacker, String target, String source, String death) throws Exception {
        id++;
        try (var st = conn.prepareStatement("INSERT INTO combatlog_v VALUES (?,?,?,?,?,?,?,?,?,?,2,3,true)")) {
            st.setLong(1,id); st.setDouble(2,t); st.setDouble(3,t);
            st.setString(4,death == null ? "DOTA_COMBATLOG_DAMAGE" : "DOTA_COMBATLOG_DEATH");
            st.setInt(5,100); st.setString(6,attacker); st.setString(7,target);
            st.setString(8,attacker); st.setString(9,source); st.setString(10,target); st.execute();
        }
        if (death != null) {
            try (var st = conn.prepareStatement("INSERT INTO hero_death_events VALUES (?,?,?)")) {
                st.setLong(1,id); st.setLong(2,id); st.setString(3,death); st.execute();
            }
        }
    }

    private void direct(double t, String a, String b, double x) throws Exception {
        sample(a,2,t,x); sample(b,3,t,x);
        event(t,a,b,"roster_team_match",null);
    }

    private ObjectNode run() throws Exception {
        ObjectNode root = new ObjectMapper().createObjectNode();
        new LocalFightBuilder(conn).addTo(root,0);
        return root;
    }

    @Test
    void separatesConcurrentRemoteFightsAndUnrelatedNearbyPairsButMergesConnectedInteractions() throws Exception {
        direct(10,"a","b",100);
        direct(10,"c","d",8000);
        direct(11,"a","b",200);
        direct(11,"c","d",8100);
        // Even at the same place, unrelated pairs need an actual shared-body interaction.
        direct(12,"c","d",300);
        var m = run();
        assertEquals(3,m.path("local_fights").size());
        assertEquals(200,m.path("local_fights").get(0).path("hero_damage").asInt());
        assertEquals(200,m.path("local_fights").get(1).path("hero_damage").asInt());
        assertEquals(2,m.path("local_fight_players").get(0).path("presence_events").asInt());
        for (JsonNode p : m.path("local_fight_players")) {
            if (p.path("fight_id").asInt()==0) assertTrue(p.path("hero_key").asText().matches("a|b"));
        }
    }

    @Test
    void inclusiveGapDurationAndCompleteLinkDistancePreventUnboundedChains() throws Exception {
        for (int t=0;t<=35;t+=5) direct(t,"a","b",100);
        direct(41,"a","b",100); // gap > 5
        direct(42,"a","b",2500); // distance exactly 2400
        direct(43,"a","b",2501); // near latest member, too far from first
        var fights = run().path("local_fights");
        assertEquals(4,fights.size());
        assertEquals(30,fights.get(0).path("duration").asInt());
        assertEquals(7,fights.get(0).path("event_count").asInt());
        assertEquals(2,fights.get(2).path("event_count").asInt());
    }

    @Test
    void missingStaleFutureAndUnknownIdentityPositionsNeverBecomeZeroOrBorrowOwnerPosition() throws Exception {
        sample("a",2,0,100); sample("b",3,0,100);
        event(5,"a","b","roster_team_match",null); // inclusive age
        event(5.01,"a","b","roster_team_match",null); // stale
        sample("d",3,20,100);
        event(10,"a","d","roster_team_match",null); // future only
        event(10,"a",null,"roster_team_match","unknown"); // no real-target identity
        var m = run();
        assertEquals(1,m.path("local_fights").size());
        for (int i=1;i<4;i++) {
            var e = m.path("local_fight_events").get(i);
            assertTrue(e.path("fight_id").isNull());
            assertTrue(e.path("x").isNull());
            assertEquals("unknown",e.path("location_source").asText());
        }
    }

    @Test
    void illusionCreditDoesNotProvePresenceOrConnectRemoteTargetsAndAegisIsNotDeath() throws Exception {
        sample("owner",2,10,100); sample("b",3,10,100); sample("d",3,10,8000);
        event(10,"owner","b","verified_illusion_source",null);
        event(10,"owner","d","verified_illusion_source",null);
        event(11,"owner","b","verified_illusion_source","aegis_respawn");
        event(12,"owner","b","verified_illusion_source","unknown");
        event(12,"owner","b","verified_illusion_source","illusion_death");
        var m=run();
        assertEquals(2,m.path("local_fights").size());
        assertEquals(4,m.path("local_fight_events").size());
        var f=m.path("local_fights").get(0);
        assertEquals(1,f.path("aegis_respawns").asInt());
        assertEquals(1,f.path("deaths").asInt());
        assertEquals(1,f.path("unknown_deaths").asInt());
        assertEquals(0,f.path("scored_deaths").asInt());
        for (JsonNode p:m.path("local_fight_players")) {
            if (p.path("hero_key").asText().equals("owner")) {
                assertEquals(0,p.path("presence_events").asInt());
                assertEquals(100,p.path("damage_dealt").asInt());
            }
        }
        assertEquals("unknown_no_spatial_causal_attribution",f.path("economy_status").asText());
        assertFalse(f.has("economy"));
    }

    @Test
    void connectedBodiesGiveExplicitHeuristicKindsWithoutInferringIntent() throws Exception {
        direct(0,"a","b",100);
        direct(1,"c","b",100);
        event(2,"a","b","roster_team_match","scored_death");
        direct(40,"a","b",100);
        direct(41,"c","b",100);
        direct(80,"a","b",100);
        direct(81,"c","b",100);
        direct(82,"owner","b",100);
        direct(83,"a","d",100);
        conn.createStatement().execute("INSERT INTO hero_team VALUES ('e',3)");
        direct(84,"a","e",100);
        var fights = run().path("local_fights");
        assertEquals(3,fights.size());
        assertEquals("pickoff",fights.get(0).path("kind").asText());
        assertEquals("skirmish",fights.get(1).path("kind").asText());
        assertEquals("teamfight",fights.get(2).path("kind").asText());
    }

    @Test
    void nonHeroDamageDoesNotSeedFightsButNonPlayerLastHitDeathIsRetained() throws Exception {
        direct(0,"a","b",100);
        event(1,"a","b","unknown","scored_death");
        conn.createStatement().execute("UPDATE combatlog_v SET attacker_hero=false,credited_attacker_key=NULL");
        var m=run();
        assertEquals(1,m.path("local_fight_events").size());
        assertEquals(1,m.path("local_fights").get(0).path("scored_deaths").asInt());
        assertEquals(0,m.path("local_fights").get(0).path("hero_damage").asInt());
        assertEquals(1,m.path("local_fight_players").size());
    }

    @Test
    void emptyInputsProduceTypedEmptyTables() throws Exception {
        var m=run();
        assertTrue(m.path("local_fights").isEmpty());
        assertTrue(m.path("local_fight_players").isEmpty());
        assertTrue(m.path("local_fight_events").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"hp=0", "hp=NULL", "x=NULL", "y=NULL", "x='NaN'", "y='Infinity'", "x=0,y=0"})
    void latestInvalidAttackerSamplePreservesDamageCreditButCannotConnectFights(String invalid) throws Exception {
        direct(10,"a","b",100);
        sample("a",2,11,100);
        conn.createStatement().execute("UPDATE players_v SET " + invalid + " WHERE hero_key='a' AND t=11");
        sample("d",3,12,100);
        event(12,"a","d","roster_team_match",null);
        var m = run();
        var dot = m.path("local_fight_events").get(1);
        assertTrue(dot.path("attacker_presence_key").isNull());
        assertEquals("a",dot.path("credited_attacker_key").asText());
        assertEquals(2,m.path("local_fights").size(), "dead/invalid attacker cannot bridge different targets");
        var owner = m.path("local_fight_players").get(2);
        assertEquals("a",owner.path("hero_key").asText());
        assertEquals(100,owner.path("damage_dealt").asInt());
        assertEquals(0,owner.path("presence_events").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"hp=0", "hp=NULL", "x=NULL", "y=NULL", "x='NaN'", "y='Infinity'", "x=0,y=0"})
    void latestInvalidTargetSampleCannotFallBackToOlderLocatedSample(String invalid) throws Exception {
        direct(10,"a","b",100);
        sample("b",3,11,100);
        conn.createStatement().execute("UPDATE players_v SET " + invalid + " WHERE hero_key='b' AND t=11");
        event(12,"a","b","roster_team_match",null);
        var m = run();
        var damage = m.path("local_fight_events").get(1);
        assertTrue(damage.path("fight_id").isNull());
        assertTrue(damage.path("x").isNull());
        assertTrue(damage.path("y").isNull());
        assertEquals("unknown",damage.path("location_source").asText());
        assertEquals(1,m.path("local_fights").get(0).path("event_count").asInt());
    }

    @Test
    void deathAllowsLatestZeroHpButNotLatestMissingPosition() throws Exception {
        sample("b",3,10,100);
        sample("b",3,11,200);
        conn.createStatement().execute("UPDATE players_v SET hp=0 WHERE t=11");
        event(12,"a","b","roster_team_match","scored_death");
        sample("b",3,13,300);
        conn.createStatement().execute("UPDATE players_v SET x=NULL WHERE t=13");
        event(14,"a","b","roster_team_match","unknown");
        var m = run();
        assertEquals(200,m.path("local_fight_events").get(0).path("x").asInt());
        assertEquals(11,m.path("local_fight_events").get(0).path("sample_t").asInt());
        assertTrue(m.path("local_fight_events").get(1).path("fight_id").isNull());
        assertEquals(1,m.path("local_fights").get(0).path("deaths").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "3", "0", "4", "NULL"})
    void onlyLegalEnemyTeamDeathCreditsAttackerKillWhileVictimDeathRemains(String attackerTeam) throws Exception {
        sample("b",3,10,100);
        event(10,"a","b","roster_team_match","scored_death");
        conn.createStatement().execute("UPDATE combatlog_v SET attacker_team=" + attackerTeam);
        var m = run();
        var attacker = m.path("local_fight_players").get(0);
        var victim = m.path("local_fight_players").get(1);
        assertEquals("a",attacker.path("hero_key").asText());
        assertEquals(attackerTeam.equals("2") ? 1 : 0,attacker.path("kills").asInt());
        assertEquals(1,victim.path("deaths").asInt());
        assertEquals(1,victim.path("scored_deaths").asInt());
    }

    @Test
    void suicideRetainsVictimDeathWithoutCreditingPersonalKill() throws Exception {
        sample("b",3,10,100);
        event(10,"b","b","roster_team_match","unknown");
        conn.createStatement().execute("UPDATE combatlog_v SET attacker_team=3");
        var m = run();
        assertEquals(1,m.path("local_fight_players").size());
        var victim = m.path("local_fight_players").get(0);
        assertEquals(0,victim.path("kills").asInt());
        assertEquals(1,victim.path("deaths").asInt());
        assertEquals(1,victim.path("unknown_deaths").asInt());
    }
}
