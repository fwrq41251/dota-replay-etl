package dev.dota.etl.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dota.etl.util.AtomicFiles;
import dev.dota.etl.util.BuildInfo;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Downloads Dota 2 replay files (.dem.bz2) from Valve's replay CDN.
 *
 * The CDN URL requires the replay salt + cluster for a match id. Two resolvers:
 *  - OpenDota's public /api/matches/{id} endpoint (default, no key required)
 *  - Valve IDOTA2Match_570/GetReplayInfo when STEAM_API_KEY is set (preferred)
 *
 * Download URL format: http://replay{cluster}.valve.net/570/{matchId}_{replaySalt}.dem.bz2
 */
public final class ReplayDownloader {

    private static final Logger log = LoggerFactory.getLogger(ReplayDownloader.class);

    private static final String STEAM_REPLAY_API =
        "https://api.steampowered.com/IDOTA2Match_570/GetReplayInfo/v1";
    private static final String OPEN_DOTA_MATCH_API =
        "https://api.opendota.com/api/matches/";
    private static final String OPEN_DOTA_REQUEST_API =
        "https://api.opendota.com/api/request/";
    private static final String CDN_TEMPLATE =
        "http://replay%d.valve.net/570/%d_%d.dem.bz2";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Identifies this tool to public APIs (OpenDota in particular throttles requests without a UA). */
    static final String USER_AGENT = "dota-replay-etl/" + BuildInfo.version();

    private final HttpClient http;
    private final String apiKey;

    public ReplayDownloader() {
        this(System.getenv("STEAM_API_KEY"));
    }

    public ReplayDownloader(String apiKey) {
        this.apiKey = (apiKey == null || apiKey.isBlank()) ? null : apiKey;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /** Builds an HTTP request carrying this tool's User-Agent (visible to package tests). */
    static HttpRequest.Builder requestBuilder(URI uri, Duration timeout) {
        return HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("User-Agent", USER_AGENT);
    }

    /** Downloads and decompresses the replay for a match id. Returns the path to the plain .dem file. */
    public Path download(long matchId, Path destDir) throws IOException, InterruptedException {
        Files.createDirectories(destDir);
        Path demPath = destDir.resolve(matchId + ".dem");
        if (isReplayFile(demPath)) {
            log.info("replay already cached at {}", demPath);
            return demPath;
        }
        if (Files.exists(demPath)) {
            log.warn("ignoring invalid replay cache at {}; it will be replaced after a successful download", demPath);
        }

        ReplayInfo info = resolveReplayInfo(matchId);
        URI cdnUrl = info.replayUrl != null
            ? URI.create(info.replayUrl)
            : URI.create(String.format(CDN_TEMPLATE, info.cluster, matchId, info.replaySalt));

        log.info("downloading replay {} from {}", matchId, cdnUrl);
        Path compressedTemp = AtomicFiles.createTempSibling(destDir.resolve(matchId + ".dem.compressed"));
        Path demTemp = AtomicFiles.createTempSibling(demPath);
        try {
            HttpRequest req = requestBuilder(cdnUrl, Duration.ofMinutes(20)).GET().build();
            HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(compressedTemp));
            if (resp.statusCode() != 200) {
                throw new IOException("replay CDN returned HTTP " + resp.statusCode() + " for " + cdnUrl
                    + " (replay may not be available for this match)");
            }
            decompress(compressedTemp, demTemp);
            if (!isReplayFile(demTemp)) {
                throw new IOException("decompressed file does not have a supported Dota replay header");
            }
            AtomicFiles.replace(demTemp, demPath);
        } finally {
            Files.deleteIfExists(compressedTemp);
            Files.deleteIfExists(demTemp);
        }
        log.info("replay saved to {} ({} bytes)", demPath, Files.size(demPath));
        return demPath;
    }

    private ReplayInfo resolveReplayInfo(long matchId) throws IOException, InterruptedException {
        return apiKey != null ? resolveViaSteam(matchId) : resolveViaOpenDota(matchId);
    }

    private ReplayInfo resolveViaSteam(long matchId) throws IOException, InterruptedException {
        URI uri = URI.create(STEAM_REPLAY_API + "?key=" + apiKey + "&match_id=" + matchId + "&appid=570");
        HttpRequest req = requestBuilder(uri, Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("GetReplayInfo returned HTTP " + resp.statusCode());
        }
        JsonNode root = MAPPER.readTree(resp.body()).path("result");
        int status = root.path("status").asInt();
        if (status != 1) {
            throw new IOException(
                "GetReplayInfo failed (status " + status + "): " + root.path("message").asText("unknown reason")
            );
        }
        long salt = root.path("replay_salt").asLong();
        long cluster = root.path("cluster").asLong();
        String replayUrl = root.path("replay_url").asText(null);
        if (salt == 0 && replayUrl == null) {
            throw new IOException("GetReplayInfo returned no replay_salt and no replay_url");
        }
        return new ReplayInfo(salt, cluster, replayUrl);
    }

    private ReplayInfo resolveViaOpenDota(long matchId) throws IOException, InterruptedException {
        ReplayInfo info = openDotaReplayInfo(matchId);
        if (info != null) {
            return info;
        }
        // Very recent matches are often not parsed yet; ask OpenDota to parse and poll.
        log.info("OpenDota has no replay_salt/cluster for match {} yet; requesting a parse and polling...", matchId);
        HttpRequest req = requestBuilder(URI.create(OPEN_DOTA_REQUEST_API + matchId), Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("OpenDota parse request returned HTTP {} (continuing to poll anyway)", resp.statusCode());
        }
        for (int i = 1; i <= 8; i++) {
            Thread.sleep(15000);
            info = openDotaReplayInfo(matchId);
            if (info != null) {
                log.info("OpenDota finished parsing match {} after poll {}", matchId, i);
                return info;
            }
            log.info("parse not ready yet (poll {}/8), waiting...", i);
        }
        throw new IOException("OpenDota still has no replay_salt/cluster for match " + matchId
            + " after requesting a parse");
    }

    /** Returns replay info, or null when OpenDota hasn't parsed the match yet. */
    private ReplayInfo openDotaReplayInfo(long matchId) throws IOException, InterruptedException {
        HttpRequest req = requestBuilder(URI.create(OPEN_DOTA_MATCH_API + matchId), Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("OpenDota /api/matches returned HTTP " + resp.statusCode());
        }
        JsonNode root = MAPPER.readTree(resp.body());
        long salt = root.path("replay_salt").asLong();
        long cluster = root.path("cluster").asLong();
        if (salt == 0 || cluster == 0) {
            return null;
        }
        return new ReplayInfo(salt, cluster, null);
    }

    static void decompress(Path compressed, Path dem) throws IOException {
        byte[] magic;
        try (InputStream f = Files.newInputStream(compressed)) {
            magic = f.readNBytes(4);
        }
        boolean isBzip2 = magic.length >= 3 && magic[0] == 'B' && magic[1] == 'Z' && magic[2] == 'h';
        boolean isZstd = magic.length >= 4 && magic[0] == 0x28 && magic[1] == (byte) 0xB5
            && magic[2] == 0x2F && magic[3] == (byte) 0xFD;
        if (!isBzip2 && !isZstd) {
            throw new IOException("replay download is neither BZip2 nor Zstandard "
                + "(magic: " + hex(magic) + "); the CDN may have served an error page");
        }
        try (InputStream in = isBzip2
                ? new BZip2CompressorInputStream(Files.newInputStream(compressed))
                : new ZstdCompressorInputStream(Files.newInputStream(compressed));
             OutputStream out = Files.newOutputStream(dem,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    static boolean isReplayFile(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) < 8) {
            return false;
        }
        byte[] header;
        try (InputStream in = Files.newInputStream(file)) {
            header = in.readNBytes(8);
        }
        return startsWith(header, "PBDEMS2\0") || startsWith(header, "HL2DEMO\0");
    }

    private static boolean startsWith(byte[] bytes, String expected) {
        byte[] prefix = expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x ", x & 0xff));
        }
        return sb.toString().trim();
    }

    private record ReplayInfo(long replaySalt, long cluster, String replayUrl) {
    }
}
