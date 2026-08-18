package dev.dota.etl;

import dev.dota.etl.extract.ExtractionProcessor;
import dev.dota.etl.extract.ReplayExtractor;
import dev.dota.etl.sink.NdjsonWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreLogicTest {

    @Test
    void positionComponentCombinesCellAndVec() {
        // world origin sits at cell 128, so world = (cell - 128) * 128 + vec
        assertEquals(-168.0f, ExtractionProcessor.positionComponent(127, -40.0f), 1e-3);
        assertEquals(-16384.0f, ExtractionProcessor.positionComponent(0, 0.0f), 1e-3);
        assertEquals(-16502.0f, ExtractionProcessor.positionComponent(-1, 10.0f), 1e-3);
        // radiant fountain: cell 74 / vec 212  ->  -6700 (matches combat log location)
        assertEquals(-6700.0f, ExtractionProcessor.positionComponent(74, 212.0f), 1e-3);
    }

    @Test
    void heroNameFromClassParsesBothEngines() {
        assertEquals("Pudge", ExtractionProcessor.heroNameFromClass("CDOTA_Unit_Hero_Pudge"));
        assertEquals("Invoker", ExtractionProcessor.heroNameFromClass("DT_DOTA_Unit_Hero_Invoker"));
        assertEquals("npc_dota_hero_someone", ExtractionProcessor.heroNameFromClass("npc_dota_hero_someone"));
    }

    @Test
    void matchIdFromFilenameParsesId() {
        assertEquals(6676393091L, ReplayExtractor.matchIdFromFilename("6676393091.dem"));
        assertEquals(6676393091L, ReplayExtractor.matchIdFromFilename("path/to/6676393091.dem.bz2"));
        assertEquals(0L, ReplayExtractor.matchIdFromFilename("replay.dem"));
        assertEquals(0L, ReplayExtractor.matchIdFromFilename("123.dem"));
    }

    @Test
    void replayExtractorRejectsInvalidSample(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
            () -> ReplayExtractor.run(dir.resolve("missing.dem"), dir, 0));
    }

    @Test
    void ndjsonWriterRoundTrips(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("x.ndjson");
        try (NdjsonWriter w = new NdjsonWriter(f)) {
            var a = w.newRecord();
            a.put("t", 12.5);
            a.put("k", 3);
            w.write(a);
            var b = w.newRecord();
            b.put("name", "héllo\"\n");
            w.write(b);
            assertEquals(2, w.count());
        }
        List<String> lines = Files.readAllLines(f);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"t\":12.5"));
        assertTrue(lines.get(1).contains("\"name\":\"héllo\\\"\\n\""));
    }

    @Test
    void ndjsonWriterObliteratesExistingFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("y.ndjson");
        Files.writeString(f, "old\nold\n");
        try (NdjsonWriter w = new NdjsonWriter(f)) {
            w.write(w.newRecord());
        }
        assertEquals(1, Files.readAllLines(f).size());
    }
}
