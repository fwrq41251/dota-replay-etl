package dev.dota.etl.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dota.etl.sink.NdjsonWriter;
import dev.dota.etl.util.AtomicFiles;
import dev.dota.etl.util.BuildInfo;
import skadistats.clarity.Clarity;
import skadistats.clarity.processor.runner.SimpleRunner;
import skadistats.clarity.source.MappedFileSource;
import skadistats.clarity.wire.shared.demo.proto.Demo;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
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
        if (sampleIntervalSec <= 0) {
            throw new IllegalArgumentException("sample interval must be greater than zero");
        }
        Demo.CDemoFileHeader header = Clarity.headerForFile(demFile.toString());

        String replaySha256 = sha256(demFile);
        long matchId = matchIdFromFilename(demFile.getFileName().toString());
        String outputKey = matchId == 0 ? "local-" + replaySha256.substring(0, 12) : String.valueOf(matchId);
        Path dir = outDir.resolve(outputKey);
        Files.createDirectories(dir);

        Path combatPath = dir.resolve("combatlog.ndjson");
        Path playersPath = dir.resolve("players.ndjson");
        Path matchPath = dir.resolve("match.json");
        Path combatTemp = AtomicFiles.createTempSibling(combatPath);
        Path playersTemp = AtomicFiles.createTempSibling(playersPath);
        Path matchTemp = AtomicFiles.createTempSibling(matchPath);

        long combatCount;
        long playersCount;
        int lastTick;
        float gameStartTime;
        float gameEndTime;
        int gameWinner;
        int radiantScore;
        int direScore;
        try {
            try (NdjsonWriter combat = new NdjsonWriter(combatTemp);
                 NdjsonWriter players = new NdjsonWriter(playersTemp)) {
                ExtractionProcessor proc = new ExtractionProcessor(combat, players, sampleIntervalSec);
                try (MappedFileSource source = new MappedFileSource(demFile.toString())) {
                    new SimpleRunner(source).runWith(proc);
                }
                lastTick = proc.lastTick();
                combatCount = combat.count();
                playersCount = players.count();
                gameStartTime = proc.gameStartTime();
                gameEndTime = proc.gameEndTime();
                gameWinner = proc.gameWinner();
                radiantScore = proc.radiantScore();
                direScore = proc.direScore();
            }

            if (gameWinner == 0) {
                Demo.CDemoFileInfo info = Clarity.infoForFile(demFile.toString());
                if (info.hasGameInfo() && info.getGameInfo().hasDota()
                    && info.getGameInfo().getDota().hasGameWinner()) {
                    gameWinner = info.getGameInfo().getDota().getGameWinner();
                }
            }
            writeMatchJson(matchTemp, matchId, demFile, replaySha256, header, sampleIntervalSec, lastTick,
                combatCount, playersCount, gameStartTime, gameEndTime, gameWinner,
                radiantScore, direScore);
            AtomicFiles.replace(combatTemp, combatPath);
            AtomicFiles.replace(playersTemp, playersPath);
            AtomicFiles.replace(matchTemp, matchPath);
            invalidateDerivedOutputs(dir);
        } finally {
            Files.deleteIfExists(combatTemp);
            Files.deleteIfExists(playersTemp);
            Files.deleteIfExists(matchTemp);
        }
        return new Result(matchId, dir, combatPath, playersPath, matchPath, combatCount, playersCount, lastTick);
    }

    private static void writeMatchJson(Path path, long matchId, Path demFile, String replaySha256,
                                       Demo.CDemoFileHeader header,
                                       int sampleIntervalSec, int lastTick,
                                       long combatCount, long playersCount,
                                       float gameStartTime, float gameEndTime, int gameWinner,
                                       int radiantScore, int direScore) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", BuildInfo.EXTRACTION_SCHEMA_VERSION);
        root.put("etl_version", BuildInfo.version());
        root.put("generated_at", Instant.now().toString());
        root.put("match_id", matchId);
        root.put("source_replay", demFile.getFileName().toString());
        root.put("source_replay_size", Files.size(demFile));
        root.put("source_replay_sha256", replaySha256);
        put(root, "map_name", header.getMapName());
        put(root, "demo_file_stamp", header.getDemoFileStamp());
        root.put("network_protocol", header.getNetworkProtocol());
        root.put("build_num", header.getBuildNum());
        root.put("playback_ticks", lastTick);
        root.put("duration_sec", round1(lastTick / (double) DOTA_TICK_RATE));
        root.put("sample_interval_sec", sampleIntervalSec);
        root.put("combat_log_entries", combatCount);
        root.put("player_samples", playersCount);
        if (gameStartTime > 0) {
            root.put("game_start_time_raw", round1(gameStartTime));
        }
        if (gameEndTime > gameStartTime) {
            root.put("game_end_time_raw", round1(gameEndTime));
            root.put("game_duration_sec", round1(gameEndTime - gameStartTime));
        }
        if (gameWinner == 2 || gameWinner == 3) {
            root.put("winner_team", gameWinner);
            root.put("winner_side", gameWinner == 2 ? "radiant" : "dire");
        }
        if (radiantScore >= 0 && direScore >= 0) {
            root.put("radiant_score", radiantScore);
            root.put("dire_score", direScore);
        }
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
    }

    private static void invalidateDerivedOutputs(Path dir) throws Exception {
        Files.deleteIfExists(dir.resolve("metrics.json"));
        Files.deleteIfExists(dir.resolve("metrics.duckdb"));
        Files.deleteIfExists(dir.resolve("prompt.md"));
        try (var files = Files.newDirectoryStream(dir, "player-review-*.md")) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        }
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

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
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
