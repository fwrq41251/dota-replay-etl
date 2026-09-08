package dev.dota.etl.extract;

import org.junit.jupiter.api.Test;
import skadistats.clarity.model.StringTable;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentExtractionTest {
    @Test void countersKeepUnknownDistinctFromZeroAndDoNotClaimAvailability() {
        var s=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        ExtractionProcessor.writeItemCounters(s,"item_black_king_bar",10836,0f,0);
        assertEquals(0,s.path("cooldown_remaining_sec").asDouble());
        assertEquals(0,s.path("item_charges").asInt());
        assertFalse(s.has("available"));
        ExtractionProcessor.writeItemCounters(s,"item_magic_wand",10836,14.7f,10);
        assertEquals(14.7,s.path("cooldown_remaining_sec").asDouble(),0.001);
        assertEquals(10,s.path("item_charges").asInt());
        ExtractionProcessor.writeItemCounters(s,"item_magic_wand",10836,Float.NaN,null);
        assertTrue(s.path("cooldown_remaining_sec").isNull());
        assertTrue(s.path("item_charges").isNull());
        ExtractionProcessor.writeItemCounters(s,"item_black_king_bar",10836,-1f,-1);
        assertTrue(s.path("cooldown_remaining_sec").isNull());
        assertTrue(s.path("item_charges").isNull());
        ExtractionProcessor.writeItemCounters(s,"item_black_king_bar",10837,0f,0);
        assertTrue(s.path("cooldown_remaining_sec").isNull());
        ExtractionProcessor.writeItemCounters(s,"item_blink",10836,0f,0);
        assertTrue(s.path("cooldown_remaining_sec").isNull());
    }
    @Test void entityNamesResolveCanonicalIdsWithoutDtClassGuessing() {
        var names=new StringTable("EntityNames",null,false,0,0,0,false);
        names.addEntry("item_blink",null);
        names.addEntry("item_branches",null);
        names.addEntry("item_quelling_blade",null);
        names.addEntry("npc_dota_hero_axe",null);
        assertEquals("item_blink",ExtractionProcessor.canonicalItemName(names,0));
        assertEquals("item_branches",ExtractionProcessor.canonicalItemName(names,1));
        assertEquals("item_quelling_blade",ExtractionProcessor.canonicalItemName(names,2));
        assertNull(ExtractionProcessor.canonicalItemName(names,3));
        assertNull(ExtractionProcessor.canonicalItemName(names,-1));
        assertNull(ExtractionProcessor.canonicalItemName(names,4));
        assertNull(ExtractionProcessor.canonicalItemName(names,null));
        assertNull(ExtractionProcessor.canonicalItemName(null,0));
    }

    @Test void onlyAuditedBuildSlotsAreClassifiedAndStashIsNotNeutral() {
        for(int i=0;i<6;i++) assertEquals("main",ExtractionProcessor.equipmentRegion(i,10836));
        for(int i=6;i<9;i++) assertEquals("backpack",ExtractionProcessor.equipmentRegion(i,10836));
        for(int i=9;i<15;i++) assertEquals("stash",ExtractionProcessor.equipmentRegion(i,10836));
        assertEquals("teleport",ExtractionProcessor.equipmentRegion(15,10836));
        assertEquals("neutral",ExtractionProcessor.equipmentRegion(16,10836));
        assertEquals("neutral_enhancement",ExtractionProcessor.equipmentRegion(17,10836));
        for(int i=18;i<25;i++) assertEquals("unknown",ExtractionProcessor.equipmentRegion(i,10836));
        assertEquals("unknown",ExtractionProcessor.equipmentRegion(-1,10836));
        assertEquals("unknown",ExtractionProcessor.equipmentRegion(0,9358));
        assertEquals("unknown",ExtractionProcessor.equipmentRegion(16,10837));
    }
}
