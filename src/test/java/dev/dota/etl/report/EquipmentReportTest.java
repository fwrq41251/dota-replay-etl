package dev.dota.etl.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentReportTest {
    @Test void rendersOnlyMatchingContextAndNeverTurnsZeroCooldownOrNoRecordIntoAvailability() throws Exception {
        var m=new ObjectMapper().readTree("""
            {"equipment_windows":[
              {"context_type":"death","context_id":7,"hero_key":"axe","status":"observed",
               "sample_t":12.2,"age_sec":0.8,"source":"selected_hero.m_hItems/EntityNames","completeness":"complete",
               "slots":[{"status":"occupied","slot":0,"region":"main","item":"item_black_king_bar",
                         "cooldown_remaining_sec":0,"item_charges":0}]},
              {"context_type":"death","context_id":8,"hero_key":"axe","status":"partial",
               "sample_t":20,"age_sec":1,"completeness":"partial","slots":null}],
             "equipment_uses":[
              {"context_type":"death","context_id":7,"hero_key":"axe","item":"item_magic_wand","t":12.3,"event_id":44},
              {"context_type":"local_fight","context_id":7,"hero_key":"axe","item":"wrong_context","t":12.3},
              {"context_type":"death","context_id":7,"hero_key":"pudge","item":"wrong_player","t":12.3}]}
            """);
        String observed=EquipmentReport.window(m,"death",7,"axe").replace("\\_","_");
        assertTrue(observed.contains("sampled_cd_sec=0.000"));
        assertTrue(observed.contains("availability=unknown"));
        assertTrue(observed.contains("item_magic_wand"));
        assertTrue(observed.contains("event=44"));
        assertFalse(observed.contains("wrong_"));
        String partial=EquipmentReport.window(m,"death",8,"axe");
        assertTrue(partial.contains("equipment=partial"));
        assertTrue(partial.contains("not proof of non-use"));
        assertFalse(partial.contains("item_black_king_bar"));
        assertTrue(EquipmentReport.window(m,"local_fight",7,"axe").contains("no verified body context"));
    }
}
