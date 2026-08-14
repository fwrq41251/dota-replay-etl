package dev.dota.etl.extract;

/** Static facts about a replay captured from the demo header before extraction starts. */
public record MatchMeta(long matchId, String mapName, String demoFileStamp, int networkProtocol, int buildNum) {
}