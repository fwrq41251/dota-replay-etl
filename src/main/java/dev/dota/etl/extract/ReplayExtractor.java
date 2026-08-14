package dev.dota.etl.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dota.etl.sink.NdjsonWriter;
import skadistats.clarity.Clarity;
import skadistats.clarity.processor.runner.SimpleRunner;
import skadistats.clarity.source.MappedFileSource;
import skadistats.clarity.wire.shared.demo.proto.Demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs the clarity extraction over a single .dem file and writes the output streams. */
public final class ReplayExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern MATCH_ID_IN_FILENAME = Pattern.compile("(\\d{6,})\\.dem");
    private static final int DOTA_TICK_RATE = 30;

    public record Result(long matchId, Path dir, Path combatLog, Path players, Path match,
                         long combatLogCount, long playersCount, int lastTick) {
    }

    public static Result run(Path demFile, Path outDir, int sampleIntervalSec) throws Exception {
        Demo.CDemoFileHeader header = Clarity.headerForFile(demFile.toString());

        long matchId = matchIdFromFilename(demFile.getFileName().toString());
        Path dir = outDir.resolve(String.valueOf(matchId));
        Files.createDirectories(dir);

        Path combatPath = dir.resolve("combatlog.ndjson");
        Path playersPath = dir.resolve("players.ndjson");
        Path matchPath = dir.resolve("match.json");

        int sampleEveryTicks = Math.max(1, sampleIntervalSec) * DOTA_TICK_RATE;
        MatchMeta meta = new MatchMeta(
            matchId,
            header.getMapName(),
            header.getDemoFileStamp(),
            header.getNetworkProtocol(),
            header.getBuildNum()
        );

        long combatCount;
        long playersCount;
        int lastTick;
        try (NdjsonWriter combat = new NdjsonWriter(combatPath);
             NdjsonWriter players = new NdjsonWriter(playersPath)) {
            ExtractionProcessor proc = new ExtractionProcessor(combat, players, meta, sampleIntervalSec);
            try (MappedFileSource source = new MappedFileSource(demFile.toString())) {
                new SimpleRunner(source).runWith(proc);
            }
            lastTick = proc.lastTick();
            combatCount = combat.count();
            playersCount = players.count();
        }

        writeMatchJson(matchPath, matchId, header, sampleIntervalSec, lastTick, combatCount, playersCount);
        return new Result(matchId, dir, combatPath, playersPath, matchPath, combatCount, playersCount, lastTick);
    }

    private static void writeMatchJson(Path path, long matchId, Demo.CDemoFileHeader header,
                                       int sampleIntervalSec, int lastTick,
                                       long combatCount, long playersCount) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("match_id", matchId);
        put(root, "map_name", header.getMapName());
        put(root, "demo_file_stamp", header.getDemoFileStamp());
        root.put("network_protocol", header.getNetworkProtocol());
        root.put("build_num", header.getBuildNum());
        root.put("playback_ticks", lastTick);
        root.put("duration_sec", round1(lastTick / (double) DOTA_TICK_RATE));
        root.put("sample_interval_sec", sampleIntervalSec);
        root.put("combat_log_entries", combatCount);
        root.put("player_samples", playersCount);
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
    }

    public static long matchIdFromFilename(String name) {
        Matcher m = MATCH_ID_IN_FILENAME.matcher(name);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return 0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null && !value.isEmpty()) {
            node.put(field, value);
        }
    }

    private ReplayExtractor() {
    }
}