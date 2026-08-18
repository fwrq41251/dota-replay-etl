package dev.dota.etl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainTest {

    private final Main main = new Main();

    @Test
    void rejectsInvalidSampleIntervals() throws Exception {
        assertEquals(2, main.run(new String[]{"analyze", "missing.dem", "--sample", "0"}));
        assertEquals(2, main.run(new String[]{"analyze", "missing.dem", "--sample", "nope"}));
        assertEquals(2, main.run(new String[]{"analyze", "0"}));
    }

    @Test
    void rejectsUnknownMissingAndDuplicateOptions() throws Exception {
        assertEquals(2, main.run(new String[]{"analyze", "missing.dem", "--wat", "x"}));
        assertEquals(2, main.run(new String[]{"analyze", "missing.dem", "--out"}));
        assertEquals(2, main.run(new String[]{"analyze", "missing.dem", "--out", "a", "--out", "b"}));
    }

    @Test
    void rejectsExtraPositionalArguments() throws Exception {
        assertEquals(2, main.run(new String[]{"metrics", "out/1", "extra"}));
        assertEquals(2, main.run(new String[]{"report", "out/1", "extra"}));
        assertEquals(2, main.run(new String[]{"player-review", "out/1", "pudge", "extra"}));
    }
}
