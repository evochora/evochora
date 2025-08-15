package org.evochora.server.setup;

/**
 * Server runtime configuration.
 * <p>
 * Defaults are chosen to enable out-of-the-box runs while keeping memory bounded.
 */
public final class Config {

    /**
     * Maximum queue memory in bytes. Defaults to 512 MB.
     */
    private final long maxQueueBytes;

    /**
     * Directory where simulation run databases are written.
     * Relative paths are resolved from the process working directory.
     */
    private final String runsDirectory;

    public Config() {
        this(512L * 1024L * 1024L, "runs");
    }

    public Config(long maxQueueBytes, String runsDirectory) {
        this.maxQueueBytes = maxQueueBytes;
        this.runsDirectory = runsDirectory;
    }

    public long getMaxQueueBytes() {
        return maxQueueBytes;
    }

    public String getRunsDirectory() {
        return runsDirectory;
    }
}


