package dev.dota.etl.download;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayDownloaderTest {

    @TempDir
    Path dir;

    @Test
    void decompressesBzip2() throws Exception {
        byte[] content = "hello bzip2 replay".getBytes();
        Path compressed = dir.resolve("in.bz2");
        try (var out = new BZip2CompressorOutputStream(Files.newOutputStream(compressed))) {
            out.write(content);
        }
        Path dem = dir.resolve("out.dem");
        ReplayDownloader.decompress(compressed, dem);
        assertEquals(new String(content), new String(Files.readAllBytes(dem)));
    }

    @Test
    void decompressesZstd() throws Exception {
        byte[] content = "hello zstd replay".getBytes();
        Path compressed = dir.resolve("in.zst");
        try (var out = new ZstdCompressorOutputStream(Files.newOutputStream(compressed))) {
            out.write(content);
        }
        Path dem = dir.resolve("out.dem");
        ReplayDownloader.decompress(compressed, dem);
        assertEquals(new String(content), new String(Files.readAllBytes(dem)));
    }

    @Test
    void rejectsUnknownMagic() throws Exception {
        Path compressed = dir.resolve("in.bin");
        Files.write(compressed, "<html>404 not found</html>".getBytes());
        assertThrows(Exception.class,
            () -> ReplayDownloader.decompress(compressed, dir.resolve("out.dem")));
    }
}