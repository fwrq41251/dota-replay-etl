package dev.dota.etl.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AtomicFilesTest {

    @Test
    void replacesExistingContent(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("result.json");
        Files.writeString(target, "old");

        AtomicFiles.writeString(target, "new");

        assertEquals("new", Files.readString(target));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }
}
