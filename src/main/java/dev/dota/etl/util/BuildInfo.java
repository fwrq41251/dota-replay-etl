package dev.dota.etl.util;

public final class BuildInfo {

    public static final int EXTRACTION_SCHEMA_VERSION = 3;
    public static final int METRICS_SCHEMA_VERSION = 12;

    public static String version() {
        String version = BuildInfo.class.getPackage().getImplementationVersion();
        return version == null ? "development" : version;
    }

    private BuildInfo() {
    }
}
