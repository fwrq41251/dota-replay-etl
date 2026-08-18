package dev.dota.etl.sink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class NdjsonWriter implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BufferedWriter writer;
    private long count;

    public NdjsonWriter(Path file) {
        try {
            Files.createDirectories(file.getParent());
            this.writer = Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open NDJSON sink " + file, e);
        }
    }

    public ObjectNode newRecord() {
        return MAPPER.createObjectNode();
    }

    public void write(ObjectNode record) {
        try {
            writer.write(MAPPER.writeValueAsString(record));
            writer.write('\n');
            count++;
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("json serialization failed", e);
        } catch (IOException e) {
            throw new UncheckedIOException("write failed", e);
        }
    }

    public long count() {
        return count;
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException("close failed", e);
        }
    }
}