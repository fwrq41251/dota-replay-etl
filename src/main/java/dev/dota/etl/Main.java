package dev.dota.etl;

import dev.dota.etl.download.ReplayDownloader;
import dev.dota.etl.extract.ReplayExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry point.
 *
 * <pre>
 *   dota-replay-etl analyze &lt;matchIdOrFile&gt; [--out DIR] [--cache DIR] [--sample SEC]
 *   dota-replay-etl download &lt;matchId&gt; [--out FILE]
 * </pre>
 *
 * When &lt;matchIdOrFile&gt; is all digits it is treated as a match id and downloaded
 * first (requires STEAM_API_KEY), otherwise it is treated as a path to a .dem file.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    public static void main(String[] args) {
        try {
            int code = new Main().run(args);
            System.exit(code);
        } catch (Exception e) {
            log.error("fatal: {}", e.getMessage());
            if (Boolean.getBoolean("dota.etl.debug")) {
                e.printStackTrace(System.err);
            }
            System.exit(1);
        }
    }

    private int run(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return 2;
        }
        String cmd = args[0];
        switch (cmd) {
            case "analyze":
                return analyze(args);
            case "metrics":
                return metrics(args);
            case "report":
                return report(args);
            case "download":
                return download(args);
            case "help":
            case "-h":
            case "--help":
                usage();
                return 0;
            default:
                log.error("unknown command '{}'", cmd);
                usage();
                return 2;
        }
    }

    private int analyze(String[] args) throws Exception {
        if (args.length < 2) {
            log.error("analyze requires an input (match id or .dem file path)");
            return 2;
        }
        String input = args[1];
        Path out = arg(args, "--out").map(Path::of).orElse(Path.of("out"));
        Path cache = arg(args, "--cache").map(Path::of).orElse(Path.of("replays"));
        int sample = arg(args, "--sample").map(Integer::parseInt).orElse(1);

        Path dem;
        if (isDigits(input)) {
            long matchId = Long.parseLong(input);
            dem = new ReplayDownloader().download(matchId, cache);
        } else {
            dem = Path.of(input);
            if (!Files.exists(dem)) {
                log.error("file not found: {}", dem.toAbsolutePath());
                return 1;
            }
        }

        log.info("extracting {}", dem);
        long t0 = System.currentTimeMillis();
        ReplayExtractor.Result result = ReplayExtractor.run(dem, out, sample);
        long elapsedMs = System.currentTimeMillis() - t0;
        log.info("match {}: {} combat log entries, {} player samples, duration {}s in {}ms",
            result.matchId(), result.combatLogCount(), result.playersCount(),
            result.lastTick() / 30, elapsedMs);
        log.info("output: {}", result.dir().toAbsolutePath());
        return 0;
    }

    private int metrics(String[] args) throws Exception {
        if (args.length < 2) {
            log.error("metrics requires an output directory containing the ETL result (e.g. out/6676393091)");
            return 2;
        }
        Path dir = Path.of(args[1]);
        Path combat = dir.resolve("combatlog.ndjson");
        Path players = dir.resolve("players.ndjson");
        if (!Files.exists(combat) || !Files.exists(players)) {
            log.error("missing combatlog.ndjson / players.ndjson in {}", dir.toAbsolutePath());
            return 1;
        }
        String matchId = "";
        Path matchJson = dir.resolve("match.json");
        if (Files.exists(matchJson)) {
            var node = MAPPER.readTree(Files.readString(matchJson));
            matchId = String.valueOf(node.path("match_id").asLong(0));
        }
        dev.dota.etl.metrics.MetricsRunner runner = new dev.dota.etl.metrics.MetricsRunner(combat, players);
        long t0 = System.currentTimeMillis();
        com.fasterxml.jackson.databind.node.ObjectNode metrics = runner.run();
        Files.writeString(runner.metricsJson(),
            MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metrics) + "\n");
        log.info("match {} metrics written to {} in {}ms",
            matchId, runner.metricsJson().toAbsolutePath(), System.currentTimeMillis() - t0);
        log.info("duckdb persisted to {}", runner.dbFile().toAbsolutePath());
        return 0;
    }

    private int report(String[] args) throws Exception {
        if (args.length < 2) {
            log.error("report requires an output directory containing metrics.json (e.g. out/6676393091)");
            return 2;
        }
        Path dir = Path.of(args[1]);
        Path metrics = dir.resolve("metrics.json");
        if (!Files.exists(metrics)) {
            log.error("missing metrics.json in {} (run `metrics` first)", dir.toAbsolutePath());
            return 1;
        }
        dev.dota.etl.report.ReportGenerator generator = new dev.dota.etl.report.ReportGenerator(dir);
        generator.generatePrompt();
        log.info("prompt written to {}", generator.promptFile().toAbsolutePath());
        log.info("(dry-run: copy the prompt into any LLM; an API call mode can be added later)");
        return 0;
    }

    private int download(String[] args) throws Exception {
        if (args.length < 2) {
            log.error("download requires a match id");
            return 2;
        }
        long matchId = Long.parseLong(args[1]);
        Path out = arg(args, "--out").map(Path::of).orElse(Path.of("replays"));
        Path dem = new ReplayDownloader().download(matchId, out);
        log.info("downloaded {}", dem.toAbsolutePath());
        return 0;
    }

    private static java.util.Optional<String> arg(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return java.util.Optional.of(args[i + 1]);
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean isDigits(String s) {
        return s.chars().allMatch(Character::isDigit);
    }

    private static void usage() {
        System.out.println("""
            dota-replay-etl - Dota 2 .dem replay ETL

            Usage:
              dota-replay-etl analyze <matchIdOrFile> [--out DIR] [--cache DIR] [--sample SEC]
                  Parse a replay. <matchIdOrFile> is a match id (downloaded first, requires
                  STEAM_API_KEY) or a path to a .dem file.
                  --out     output directory (default: ./out)
                  --cache   replay cache directory (default: ./replays)
                  --sample  player state sampling interval in seconds (default: 1)

              dota-replay-etl metrics <outputDir>
                  Compute match metrics (roster, kills, teamfights, gold/xp curves, items)
                  from an analyze result directory via DuckDB.

              dota-replay-etl report <outputDir>
                  Assemble a Chinese LLM review prompt from metrics.json into prompt.md
                  (dry-run only, no API call).

              dota-replay-etl download <matchId> [--out FILE]
                  Download and decompress a replay by match id (requires STEAM_API_KEY).

            Output layout (per match):
              <out>/<matchId>/combatlog.ndjson   every combat log entry, one JSON object per line
              <out>/<matchId>/players.ndjson     per-second sampled player state (10 players)
              <out>/<matchId>/match.json         match-level facts
              <out>/<matchId>/metrics.json       computed metrics (via `metrics` command)
              <out>/<matchId>/metrics.duckdb     persisted tables for ad-hoc SQL
              <out>/<matchId>/prompt.md          LLM review prompt (via `report` command)
            """);
    }

    private Main() {
    }
}