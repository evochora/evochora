package org.evochora.cli.commands.node;

import java.time.Instant;
import java.util.List;

import org.evochora.cli.CliResourceFactory;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * Looks the newest run in a storage resource up, for the node subcommands that take up a run
 * without being told which one.
 * <p>
 * Run IDs begin with the timestamp of their start and therefore sort chronologically, so the last
 * of the listed IDs is the newest run the storage holds.
 */
final class LatestRun {

    private static final Logger LOGGER = LoggerFactory.getLogger(LatestRun.class);

    private LatestRun() {
    }

    /**
     * Returns the ID of the newest run in the named storage resource.
     * <p>
     * The resource is built from {@code pipeline.resources.<storageName>} of the given
     * configuration, asked for its runs and closed again, so nothing of it outlives the lookup.
     *
     * @param config      the configuration of the invocation
     * @param storageName the name of the storage resource under {@code pipeline.resources}
     * @return the ID of the newest run
     * @throws IllegalArgumentException if the configuration has no such storage resource
     * @throws IllegalStateException    if the storage holds no run
     * @throws Exception                if the storage cannot be built or read
     */
    static String runId(final Config config, final String storageName) throws Exception {
        final String path = "pipeline.resources." + storageName;
        if (!config.hasPath(path)) {
            throw new IllegalArgumentException(
                "Storage resource '" + storageName + "' is not configured under pipeline.resources.");
        }

        final IBatchStorageRead storage =
            CliResourceFactory.create(storageName, IBatchStorageRead.class, config.getConfig(path));
        try {
            final List<String> runIds = storage.listRunIds(Instant.EPOCH);
            if (runIds.isEmpty()) {
                throw new IllegalStateException(
                    "Storage resource '" + storageName + "' holds no simulation run. "
                        + "Name a run with --run, or point the storage at a directory that holds one.");
            }
            return runIds.get(runIds.size() - 1);
        } finally {
            close(storage, storageName);
        }
    }

    /**
     * Closes the storage resource if its implementation holds anything that must be released.
     *
     * @param storage     the resource built for the lookup
     * @param storageName the configured name of the resource, for the message of a failed close
     */
    private static void close(final IBatchStorageRead storage, final String storageName) {
        if (!(storage instanceof AutoCloseable closeable)) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            LOGGER.warn("Failed to close storage resource '{}': {}", storageName, e.getMessage());
        }
    }
}
