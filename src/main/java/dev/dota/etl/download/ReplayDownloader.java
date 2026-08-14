package dev.dota.etl.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
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
    private static final String CDN_TEMPLATE =
        "http://replay%d.valve.net/570/%d_%d.dem.bz2";

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    /** Downloads and decompresses the replay for a match id. Returns the path to the plain .dem file. */
    public Path download(long matchId, Path destDir) throws IOException, InterruptedException {
        Files.createDirectories(destDir);
        Path demPath = destDir.resolve(matchId + ".dem");
        if (Files.exists(demPath) && Files.size(demPath) > 0) {
            log.info("replay already cached at {}", demPath);
            return demPath;
        }

        ReplayInfo info = resolveReplayInfo(matchId);
        URI cdnUrl = info.replayUrl != null
            ? URI.create(info.replayUrl)
            : URI.create(String.format(CDN_TEMPLATE, info.cluster, matchId, info.replaySalt));

        log.info("downloading replay {} from {}", matchId, cdnUrl);
        Path bz2 = destDir.resolve(matchId + ".dem.bz2");
        HttpRequest req = HttpRequest.newBuilder(cdnUrl)
            .timeout(Duration.ofMinutes(20))
            .GET()
            .build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(bz2));
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(bz2);
            throw new IOException("replay CDN returned HTTP " + resp.statusCode() + " for " + cdnUrl
                + " (replay may not be available for this match)");
        }
        decompress(bz2, demPath);
        Files.deleteIfExists(bz2);
        log.info("replay saved to {} ({} bytes)", demPath, Files.size(demPath));
        return demPath;
    }

    private ReplayInfo resolveReplayInfo(long matchId) throws IOException, InterruptedException {
        return apiKey != null ? resolveViaSteam(matchId) : resolveViaOpenDota(matchId);
    }

    private ReplayInfo resolveViaSteam(long matchId) throws IOException, InterruptedException {
        URI uri = URI.create(STEAM_REPLAY_API + "?key=" + apiKey + "&match_id=" + matchId + "&appid=570");
        HttpRequest req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
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
        HttpRequest req = HttpRequest.newBuilder(URI.create(OPEN_DOTA_MATCH_API + matchId))
            .timeout(Duration.ofSeconds(30))
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
            throw new IOException("OpenDota returned no replay_salt/cluster for match " + matchId);
        }
        return new ReplayInfo(salt, cluster, null);
    }

    private static void decompress(Path bz2, Path dem) throws IOException {
        try (InputStream in = new BZip2CompressorInputStream(Files.newInputStream(bz2));
             OutputStream out = Files.newOutputStream(dem, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private record ReplayInfo(long replaySalt, long cluster, String replayUrl) {
    }
}