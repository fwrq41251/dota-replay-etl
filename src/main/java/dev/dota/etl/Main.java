package dev.dota.etl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dota.etl.download.ReplayDownloader;
import dev.dota.etl.extract.ReplayExtractor;
import dev.dota.etl.metrics.MetricsRunner;
import dev.dota.etl.report.PlayerReviewGenerator;
import dev.dota.etl.report.ReportGenerator;
import dev.dota.etl.util.AtomicFiles;
import dev.dota.etl.util.Numbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CLI entry point.
 *
 * <pre>
 *   dota-replay-etl analyze &lt;matchIdOrFile&gt; [--out DIR] [--cache DIR] [--sample SEC]
 *   dota-replay-etl pipeline &lt;matchIdOrFile&gt; [--out DIR] [--cache DIR] [--sample SEC]
 *                     [--report] [--player-review HERO]
 *   dota-replay-etl metrics &lt;outputDir&gt;
 *   dota-replay-etl report &lt;outputDir&gt;
 *   dota-replay-etl player-review &lt;outputDir&gt; &lt;heroOrNameOrIndex&gt;
 *   dota-replay-etl download &lt;matchId&gt; [--out DIR]
 * </pre>
 *
 * When &lt;matchIdOrFile&gt; is all digits it is treated as a match id and downloaded
 * first, otherwise it is treated as a path to a .dem file. STEAM_API_KEY is optional.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    int run(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return 2;
        }
        try {
            String cmd = args[0];
            return switch (cmd) {
                case "analyze" -> analyze(args);
                case "pipeline" -> pipeline(args);
                case "metrics" -> metrics(args);
                case "report" -> report(args);
                case "player-review" -> playerReview(args);
                case "download" -> download(args);
                case "help", "-h", "--help" -> {
                    usage();
                    yield 0;
                }
                default -> {
                    log.error("unknown command '{}'", cmd);
                    usage();
                    yield 2;
                }
            };
        } catch (IllegalArgumentException e) {
            log.error("{}", e.getMessage());
            return 2;
        }
    }

    private int analyze(String[] args) throws Exception {
        if (args.length < 2) {
            log.error("analyze requires an input (match id or .dem file path)");
            return 2;
        }
        String input = args[1];
        Map<String, String> options = options(args, 2, Set.of(), "--out", "--cache", "--sample");
        Path out = value(options, "--out").map(Path::of).orElse(Path.of("out"));
        Path cache = value(options, "--cache").map(Path::of).orElse(Path.of("replays"));
        int sample = value(options, "--sample").map(Main::positiveInt).orElse(1);
        resolveAndExtract(input, out, cache, sample);
        return 0;
    }

    private int pipeline(String[] args) throws Exception {
        if (args.length < 2) {
            log.error("pipeline requires an input (match id or .dem file path)");
            return 2;
        }
        String input = args[1];
        Map<String, String> options = options(args, 2, Set.of("--report"),
            "--out", "--cache", "--sample", "--player-review");
        Path out = value(options, "--out").map(Path::of).orElse(Path.of("out"));
        Path cache = value(options, "--cache").map(Path::of).orElse(Path.of("replays"));
        int sample = value(options, "--sample").map(Main::positiveInt).orElse(1);
        boolean withReport = options.containsKey("--report");
        String playerSelector = value(options, "--player-review").orElse(null);

        Path dir = resolveAndExtract(input, out, cache, sample);
        computeMetrics(dir);
        if (withReport) {
            generateReport(dir);
        }
        if (playerSelector != null) {
            generatePlayerReview(dir, playerSelector);
        }
        return 0;
    }

    private int metrics(String[] args) throws Exception {
        if (args.length != 2) {
            log.error("metrics requires an output directory containing the ETL result (e.g. out/6676393091)");
            return 2;
        }
        computeMetrics(Path.of(args[1]));
        return 0;
    }

    private int report(String[] args) throws Exception {
        if (args.length != 2) {
            log.error("report requires an output directory containing metrics.json (e.g. out/6676393091)");
            return 2;
        }
        generateReport(Path.of(args[1]));
        return 0;
    }

    private int download(String[] args) throws Exception {
        if (args.length < 2) {
            log.error("download requires a match id");
            return 2;
        }
        long matchId = positiveLong(args[1], "match id");
        Map<String, String> options = options(args, 2, Set.of(), "--out");
        Path out = value(options, "--out").map(Path::of).orElse(Path.of("replays"));
        Path dem = new ReplayDownloader().download(matchId, out);
        log.info("downloaded {}", dem.toAbsolutePath());
        return 0;
    }

    private int playerReview(String[] args) throws Exception {
        if (args.length != 3) {
            log.error("player-review requires an output directory and a player selector " +
                "(hero/name/player index, e.g. out/8943544578 slark)");
            return 2;
        }
        generatePlayerReview(Path.of(args[1]), args[2]);
        return 0;
    }

    // ------------------------------------------------------------------
    // shared pipeline stages
    // ------------------------------------------------------------------

    private static Path resolveAndExtract(String input, Path out, Path cache, int sample) throws Exception {
        Path dem;
        if (Numbers.isDigits(input)) {
            long matchId = positiveLong(input, "match id");
            dem = new ReplayDownloader().download(matchId, cache);
        } else {
            dem = Path.of(input);
            if (!Files.isRegularFile(dem)) {
                throw new IllegalArgumentException("replay file not found: " + dem.toAbsolutePath());
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
        return result.dir();
    }

    private static ObjectNode computeMetrics(Path dir) throws Exception {
        Path combat = dir.resolve("combatlog.ndjson");
        Path players = dir.resolve("players.ndjson");
        if (!Files.exists(combat) || !Files.exists(players)) {
            throw new IllegalArgumentException(
                "missing combatlog.ndjson / players.ndjson in " + dir.toAbsolutePath());
        }
        String matchId = "";
        Path matchJson = dir.resolve("match.json");
        if (Files.exists(matchJson)) {
            var node = MAPPER.readTree(Files.readString(matchJson));
            matchId = String.valueOf(node.path("match_id").asLong(0));
        }
        MetricsRunner runner = new MetricsRunner(combat, players);
        long t0 = System.currentTimeMillis();
        ObjectNode metrics = runner.run();
        AtomicFiles.writeString(runner.metricsJson(),
            MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metrics) + "\n");
        log.info("match {} metrics written to {} in {}ms",
            matchId, runner.metricsJson().toAbsolutePath(), System.currentTimeMillis() - t0);
        log.info("duckdb persisted to {}", runner.dbFile().toAbsolutePath());
        return metrics;
    }

    private static void generateReport(Path dir) throws Exception {
        if (!Files.exists(dir.resolve("metrics.json"))) {
            throw new IllegalArgumentException(
                "missing metrics.json in " + dir.toAbsolutePath() + " (run `metrics` first)");
        }
        ReportGenerator generator = new ReportGenerator(dir);
        generator.generatePrompt();
        log.info("prompt written to {}", generator.promptFile().toAbsolutePath());
        log.info("(dry-run: copy the prompt into any LLM; an API call mode can be added later)");
    }

    private static void generatePlayerReview(Path dir, String selector) throws Exception {
        if (!Files.exists(dir.resolve("metrics.json"))) {
            throw new IllegalArgumentException(
                "missing metrics.json in " + dir.toAbsolutePath() + " (run `metrics` first)");
        }
        PlayerReviewGenerator generator = new PlayerReviewGenerator(dir, selector);
        String prompt = generator.generatePrompt();
        log.info("player review prompt written to {}", generator.promptFile().toAbsolutePath());
        log.info("prompt length {} chars (dry-run: copy the prompt into any LLM)",
            prompt.length());
    }

    private static Map<String, String> options(String[] args, int start, Set<String> flags,
                                               String... allowedNames) {
        Set<String> allowed = Set.of(allowedNames);
        Map<String, String> values = new HashMap<>();
        for (int i = start; i < args.length; i++) {
            String name = args[i];
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("unknown option or unexpected argument '" + name + "'");
            }
            if (flags.contains(name)) {
                if (values.putIfAbsent(name, "true") != null) {
                    throw new IllegalArgumentException("option " + name + " was specified more than once");
                }
                continue;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("option " + name + " requires a value");
            }
            if (values.putIfAbsent(name, args[i + 1]) != null) {
                throw new IllegalArgumentException("option " + name + " was specified more than once");
            }
            i++;
        }
        return values;
    }

    private static Optional<String> value(Map<String, String> options, String name) {
        return Optional.ofNullable(options.get(name));
    }

    private static int positiveInt(String value) {
        long parsed = positiveLong(value, "sample interval");
        if (parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("sample interval is too large: " + value);
        }
        return (int) parsed;
    }

    private static long positiveLong(String value, String label) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(label + " must be greater than zero");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + label + ": '" + value + "'");
        }
    }

    private static void usage() {
        System.out.println("""
            dota-replay-etl - Dota 2 .dem replay ETL

            Usage:
              dota-replay-etl analyze <matchIdOrFile> [--out DIR] [--cache DIR] [--sample SEC]
                  Parse a replay. <matchIdOrFile> is a match id (downloaded first) or a path
                  to a .dem file. STEAM_API_KEY is optional.
                  --out     output directory (default: ./out)
                  --cache   replay cache directory (default: ./replays)
                  --sample  player state sampling interval in seconds (default: 1)

              dota-replay-etl pipeline <matchIdOrFile> [--out DIR] [--cache DIR] [--sample SEC]
                             [--report] [--player-review HERO]
                  Extract + compute metrics in one step. Flags:
                  --report           also assemble prompt.md (dry-run)
                  --player-review H  also assemble player-review-<hero>.md (dry-run)

              dota-replay-etl metrics <outputDir>
                  Compute match metrics (roster, kills, teamfights, gold/xp curves, items)
                  from an analyze result directory via DuckDB.

              dota-replay-etl report <outputDir>
                  Assemble a Chinese LLM review prompt from metrics.json into prompt.md
                  (dry-run only, no API call).

              dota-replay-etl player-review <outputDir> <heroOrNameOrIndex>
                  Assemble a single-player Chinese review prompt (出装/团战/打钱/决策)
                  for one hero into player-review-<hero>.md (dry-run, no API call).

              dota-replay-etl download <matchId> [--out DIR]
                  Download and decompress a replay by match id (STEAM_API_KEY is optional).

            Output layout (per match):
              <out>/<matchId>/combatlog.ndjson   every combat log entry, one JSON object per line
              <out>/<matchId>/players.ndjson     per-second sampled player state (10 players)
              <out>/<matchId>/match.json         match-level facts
              <out>/<matchId>/metrics.json       computed metrics (via `metrics` command)
              <out>/<matchId>/metrics.duckdb     persisted tables for ad-hoc SQL
              <out>/<matchId>/prompt.md          LLM review prompt (via `report` command)
              <out>/<matchId>/player-review-<hero>.md  single-player review prompt (via `player-review`)
            """);
    }

    Main() {
    }
}
