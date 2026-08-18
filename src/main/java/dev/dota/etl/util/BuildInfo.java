package dev.dota.etl.util;

public final class BuildInfo {

    public static final int EXTRACTION_SCHEMA_VERSION = 2;
    public static final int METRICS_SCHEMA_VERSION = 6;

    public static String version() {
        String version = BuildInfo.class.getPackage().getImplementationVersion();
        return version == null ? "development" : version;
    }

    private BuildInfo() {
    }
}
