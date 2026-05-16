package org.jahia.community.clamav;

public final class ClamavConstants {

    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 3310;
    public static final int DEFAULT_CONNECTION_TIMEOUT = 2000;
    public static final int DEFAULT_READ_TIMEOUT = 20000;

    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;
    public static final int MAX_TIMEOUT_MS = 300_000;
    public static final long DEFAULT_MAX_SCAN_BYTES = 100L * 1024L * 1024L;
    public static final int MAX_BASE64_INPUT_CHARS = 140_000_000;

    private ClamavConstants() {
    }
}
