package dev.dota.etl.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentBuilderTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"future","missing","absent","NaN","age","age5","stale"})
    void usePositionUsesActualClockAndInvalidLatestBlocksFallback(String scenario) throws Exception {
        if (!scenario.equals("future")) sample(0,8,9,"complete",0);
        sample(0,10,12,"complete",0);
        if (scenario.equals("missing") || scenario.equals("NaN")) {
            conn.createStatement().execute("UPDATE players_v SET equipment=json_merge_patch(equipment,'{\"observed_raw_t\":"
                +(scenario.equals("missing")?"null":"\"NaN\"")+"}') WHERE t=10");
        }
        if(scenario.equals("absent")) conn.createStatement().execute("UPDATE players_v SET equipment=NULL WHERE t=10");
        double use=switch(scenario) {case "future" -> 11;case "age5" -> 17;case "stale" -> 17.01;default -> 13;};
        conn.createStatement().execute("INSERT INTO local_fights VALUES (0,"+use+","+use+")");
        if(scenario.equals("future")) conn.createStatement().execute("UPDATE local_fights SET start=10,\"end\"=13");
        conn.createStatement().execute("INSERT INTO local_fight_players VALUES (0,'axe',1)");
        conn.createStatement().execute("INSERT INTO local_fight_events VALUES (0,"+use+",100,100,'axe',NULL,100)");
        conn.createStatement().execute("INSERT INTO combatlog_v VALUES (1,"+use+","+(use+100)
            +",'axe',true,'hero_action_identity','npc_dota_hero_axe','DOTA_COMBATLOG_ITEM','item_blink')");
        var m=build();
        if(scenario.equals("age") || scenario.equals("age5")) {
            var u=m.path("equipment_uses").get(0);
            assertEquals(12,u.path("position_sample_t").asDouble());
            assertEquals(use-12,u.path("position_age_sec").asDouble());
            assertEquals(1000,u.path("position_tick").asInt());
            assertEquals(m.path("equipment_windows").get(0).path("sample_t"),u.path("position_sample_t"));
        } else assertTrue(m.path("equipment_uses").isEmpty(),scenario);
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private Connection conn;

    @BeforeEach void setup() throws Exception {
        conn = DriverManager.getConnection("jdbc:duckdb:");
        try (var st = conn.createStatement()) {
            st.execute("CREATE TABLE players_v(player INTEGER,hero_key VARCHAR,team INTEGER,tick INTEGER,t DOUBLE,raw_t DOUBLE,hp DOUBLE,x DOUBLE,y DOUBLE,equipment JSON)");
            st.execute("CREATE TABLE hero_team(player INTEGER,hero_key VARCHAR,team INTEGER)");
            st.execute("INSERT INTO hero_team VALUES (0,'axe',2),(1,'pudge',3),(2,'dark_seer',3)");
            st.execute("CREATE TABLE hero_kills(kill_id INTEGER,target_player_key VARCHAR,target_team INTEGER,t DOUBLE)");
            st.execute("CREATE TABLE local_fights(id INTEGER,start DOUBLE,\"end\" DOUBLE)");
            st.execute("CREATE TABLE local_fight_players(fight_id INTEGER,hero_key VARCHAR,presence_events INTEGER)");
            st.execute("CREATE TABLE local_fight_events(fight_id INTEGER,t DOUBLE,x DOUBLE,y DOUBLE,attacker_presence_key VARCHAR,target_presence_key VARCHAR,event_id INTEGER)");
            st.execute("CREATE TABLE combatlog_v(event_id INTEGER,t DOUBLE,raw_t DOUBLE,credited_attacker_key VARCHAR,attacker_hero BOOLEAN,attribution_source VARCHAR,attacker VARCHAR,type VARCHAR,inflictor VARCHAR)");
        }
    }

    @AfterEach void close() throws Exception { conn.close(); }

    private void sample(int player, double scheduled, double observed, String status, Integer slot) throws Exception {
        ObjectNode e = mapper.createObjectNode().put("observed_raw_t", observed+100)
            .put("status",status).put("slot_count",25).put("source","selected_hero.m_hItems/EntityNames")
            .put("clock_source","tick_end_latest_combatlog");
        var slots = e.putArray("slots");
        for (int i=0;i<25;i++) {
            ObjectNode s = slots.addObject().put("slot",i)
                .put("region",dev.dota.etl.extract.ExtractionProcessor.equipmentRegion(i,10836));
            if (slot!=null && slot==i) {
                s.put("status","occupied").put("entity_uid",101+player).put("item","item_blink");
            } else s.put("status","empty").putNull("entity_uid").putNull("item");
        }
        try (var st = conn.prepareStatement("INSERT INTO players_v VALUES (?,?,?,?,?,?,1000,100,100,?::JSON)")) {
            st.setInt(1,player); st.setString(2,player==0?"axe":"pudge"); st.setInt(3,player==0?2:3);
            st.setInt(4,(int)(scheduled*100)); st.setDouble(5,scheduled); st.setDouble(6,scheduled+100);
            st.setString(7,status==null?null:e.toString()); st.execute();
        }
    }

    private ObjectNode build() throws Exception {
        ObjectNode root=mapper.createObjectNode();
        new EquipmentBuilder(conn).addTo(root,100,10836);
        // Every exported row is exactly the table row, including nested slots and nulls.
        for (String table : EquipmentBuilder.TABLES) {
            var rows=mapper.createArrayNode();
            try(var st=conn.createStatement(); var rs=st.executeQuery("SELECT to_json(r) FROM (SELECT * FROM "+table+" ORDER BY ALL) r")) {
                while(rs.next()) rows.add(mapper.readTree(rs.getString(1)));
            }
            assertEquals(rows,root.path(table),table);
        }
        return root;
    }

    private JsonNode first(ObjectNode m,String kind,int player) {
        for(var f:m.path("equipment_first_observations"))
            if(f.path("observation").asText().equals(kind) && f.path("player").asInt()==player) return f;
        fail("missing first "+kind); return null;
    }

    @Test void separatesStashCarriedMainAndConsecutiveMovesFromMissingSamples() throws Exception {
        sample(0,0,0,"complete",null);
        sample(0,1,1,"complete",9);
        sample(0,2,2,"complete",6);
        sample(0,3,3,"complete",0);
        sample(0,4,4,"complete",null);
        sample(0,5,5,"complete",0);
        sample(0,6,6,null,null);
        sample(0,7,7,"complete",null);
        sample(0,20,20,"complete",0);
        sample(1,0,0,"complete",0);
        ObjectNode m=build();
        assertEquals(1,first(m,"inventory",0).path("sample_t").asDouble());
        assertEquals(2,first(m,"carried",0).path("sample_t").asDouble());
        assertEquals(3,first(m,"main",0).path("sample_t").asDouble());
        assertEquals(1,first(m,"main",0).path("uncertainty_sec").asDouble());
        assertTrue(first(m,"main",1).path("uncertainty_sec").isNull());
        List<String> changes=new ArrayList<>();
        for(var c:m.path("equipment_changes")) if(c.path("player").asInt()==0) changes.add(c.path("change_type").asText());
        assertEquals(List.of("appeared_observed","slot_moved_observed","slot_moved_observed",
            "disappeared_observed","appeared_observed","comparison_unknown","comparison_unknown"),changes);
    }

    @Test void latestMissingBlocksFallbackAgeIsInclusiveAndFutureObservationNeverLeaks() throws Exception {
        sample(0,0,0,"complete",0);
        sample(0,6,6,"partial",null);
        sample(0,7,7,null,null);
        sample(0,8,9,"complete",0); // scheduled label is earlier than actual observation
        sample(1,0,0,"complete",9);
        try(var st=conn.createStatement()) {
            st.execute("INSERT INTO hero_kills VALUES (0,'axe',2,-1),(1,'axe',2,5),(2,'axe',2,5.01),(3,'axe',2,6),(4,'axe',2,8),(5,'axe',2,9),(6,'pudge',3,3)");
        }
        var m=build();
        List<String> states=new ArrayList<>();
        for(var w:m.path("equipment_windows")) {
            states.add(w.path("status").asText());
            if(!w.path("status").asText().equals("observed")) assertTrue(w.path("slots").isNull());
            assertEquals("unknown",w.path("availability").asText());
        }
        assertEquals(List.of("missing","observed","stale","partial","legacy_unknown","observed","observed"),states);
        assertEquals(7,m.path("equipment_windows").get(4).path("sample_t").asDouble());
        assertEquals("stash",m.path("equipment_windows").get(6).path("slots").get(0).path("region").asText());
    }

    @Test void itemUsesRequireRealIdentityInclusiveWindowAndLocalMemberBodyNotRemoteCredit() throws Exception {
        sample(0,10,10,"complete",0);
        sample(0,15,15,"complete",0);
        sample(1,10,10,"complete",0);
        try(var st=conn.createStatement()) {
            st.execute("INSERT INTO hero_kills VALUES (0,'axe',2,25)"); // [10,25]
            st.execute("INSERT INTO local_fights VALUES (0,10,15),(1,10,15)");
            st.execute("INSERT INTO local_fight_players VALUES (0,'axe',2),(0,'dark_seer',0),(1,'pudge',1)");
            st.execute("INSERT INTO local_fight_events VALUES (0,10,100,100,'axe',NULL,100),(0,15,100,100,'axe',NULL,101),(1,10,10000,10000,'pudge',NULL,102)");
        }
        double[] times={9.99,10,15,15.01,25,25.01,12,12,12};
        for(int i=0;i<times.length;i++) {
            try(var st=conn.prepareStatement("INSERT INTO combatlog_v VALUES (?,?,?,'axe',true,?,'npc_dota_hero_axe','DOTA_COMBATLOG_ITEM','item_blink')")) {
                st.setInt(1,i); st.setDouble(2,times[i]); st.setDouble(3,times[i]+100);
                st.setString(4,i==6?"verified_illusion_source":i==7?"unknown":"hero_action_identity"); st.execute();
            }
        }
        try(var st=conn.createStatement()) {
            st.execute("UPDATE combatlog_v SET credited_attacker_key='pudge' WHERE event_id=8");
        }
        var m=build();
        List<Long> local=new ArrayList<>(),death=new ArrayList<>();
        for(var u:m.path("equipment_uses")) {
            (u.path("context_type").asText().equals("death")?death:local).add(u.path("event_id").asLong());
            if(u.path("context_type").asText().equals("local_fight")) {
                assertTrue(u.path("member_event_id").asInt()>=100);
                assertTrue(u.path("position_age_sec").asDouble()<=5);
            }
        }
        assertEquals(List.of(1L,2L,3L,4L),death);
        assertEquals(List.of(1L,2L),local);
        assertEquals(3,m.path("equipment_windows").size()); // no credited-only dark seer context
    }

    @Test void mismatchedPlayerIdentityCannotProduceKnownEquipment() throws Exception {
        sample(0,0,0,"complete",0);
        sample(0,1,1,"complete",0);
        try(var st=conn.createStatement()) {
            st.execute("UPDATE players_v SET hero_key='pudge' WHERE t=1");
            st.execute("INSERT INTO hero_kills VALUES (0,'axe',2,2)");
        }
        var m=build();
        assertEquals("identity_unknown",m.path("equipment_windows").get(0).path("status").asText());
        assertTrue(m.path("equipment_windows").get(0).path("slots").isNull());
    }

    @Test void claimedCompleteWithMissingSlotsMustNotBecomeEmptyInventory() throws Exception {
        sample(0,0,0,"complete",0);
        sample(0,1,1,"complete",null);
        try(var st=conn.createStatement()) {
            st.execute("UPDATE players_v SET equipment=json_merge_patch(equipment,'{\"slots\":null}') WHERE t=1");
            st.execute("INSERT INTO hero_kills VALUES (0,'axe',2,2)");
        }
        var m=build();
        assertNotEquals("observed",m.path("equipment_windows").get(0).path("status").asText());
        assertEquals("comparison_unknown",m.path("equipment_changes").get(1).path("change_type").asText());
    }

    @Test void missingObservationClockBlocksKnownStateAndFirstObservation() throws Exception {
        sample(0,0,0,"complete",0);
        try(var st=conn.createStatement()) {
            st.execute("UPDATE players_v SET equipment=json_merge_patch(equipment,'{\"observed_raw_t\":null}')");
            st.execute("INSERT INTO hero_kills VALUES (0,'axe',2,2)");
        }
        var m=build();
        assertEquals("clock_unknown",m.path("equipment_windows").get(0).path("status").asText());
        assertTrue(m.path("equipment_first_observations").isEmpty());
    }

    @Test void nonfiniteObservationClockCannotDisappearAndExposeOlderSnapshot() throws Exception {
        sample(0,0,0,"complete",0);
        sample(0,1,1,"complete",0);
        try(var st=conn.createStatement()) {
            st.execute("UPDATE players_v SET equipment=json_merge_patch(equipment,'{\"observed_raw_t\":\"NaN\"}') WHERE t=1");
            st.execute("INSERT INTO hero_kills VALUES (0,'axe',2,2)");
        }
        var m=build();
        assertEquals("clock_unknown",m.path("equipment_windows").get(0).path("status").asText());
        assertEquals(1,m.path("equipment_windows").get(0).path("sample_t").asDouble());
    }

    @Test void sampledCounterZeroAndNullSurviveJsonAndWindowWithoutAvailabilityInference() throws Exception {
        sample(0,0,0,"complete",0);
        sample(0,1,1,"complete",0);
        for(int t=0;t<2;t++) {
            ObjectNode e;
            try(var rs=conn.createStatement().executeQuery("SELECT equipment FROM players_v WHERE t="+t)) {
                rs.next(); e=(ObjectNode)mapper.readTree(rs.getString(1));
            }
            ObjectNode slot=(ObjectNode)e.path("slots").get(0);
            slot.put("item","item_black_king_bar");
            if(t==0) slot.put("cooldown_remaining_sec",0).put("item_charges",0);
            else slot.putNull("cooldown_remaining_sec").putNull("item_charges");
            try(var st=conn.prepareStatement("UPDATE players_v SET equipment=?::JSON WHERE t=?")) {
                st.setString(1,e.toString()); st.setInt(2,t); st.execute();
            }
        }
        conn.createStatement().execute("INSERT INTO hero_kills VALUES (0,'axe',2,0),(1,'axe',2,1)");
        var m=build();
        var zero=m.path("equipment_windows").get(0);
        var unknown=m.path("equipment_windows").get(1);
        assertTrue(zero.path("slots").get(0).path("cooldown_remaining_sec").isNumber());
        assertEquals(0,zero.path("slots").get(0).path("item_charges").asInt());
        assertTrue(unknown.path("slots").get(0).path("cooldown_remaining_sec").isNull());
        assertTrue(unknown.path("slots").get(0).path("item_charges").isNull());
        assertEquals("unknown",zero.path("availability").asText());
        assertEquals("unknown",unknown.path("availability").asText());
    }

    @Test void newerMissingTickBlocksDelayedOlderObservationDespiteEarlierScheduledLabel() throws Exception {
        sample(0,0,2,"complete",0);
        sample(0,1,3,null,null);
        conn.createStatement().execute("INSERT INTO hero_kills VALUES (0,'axe',2,4)");
        var m=build();
        assertEquals("legacy_unknown",m.path("equipment_windows").get(0).path("status").asText());
        assertTrue(m.path("equipment_windows").get(0).path("slots").isNull());
    }
}
