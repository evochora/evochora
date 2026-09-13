package org.evochora.cli.commands.node;

import java.time.Instant;
import java.util.List;

import org.evochora.cli.CliResourceFactory;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * Resolves the run a node subcommand takes up: the newest run in a storage resource when none
 * was named, or the named run after making sure the storage holds it, so that a mistyped run
 * ID is reported by the command instead of by a service inside the started node.
 * <p>
 * Run IDs begin with the timestamp of their start and therefore sort chronologically, so the last
 * of the listed IDs is the newest run the storage holds.
 */
final class RunLookup {

    private static final Logger LOGGER = LoggerFactory.getLogger(RunLookup.class);

    private RunLookup() {
    }

    /**
     * Returns the ID of the newest run in the named storage resource.
     *
     * @param config      the configuration of the invocation
     * @param storageName the name of the storage resource under {@code pipeline.resources}
     * @return the ID of the newest run
     * @throws IllegalArgumentException if the configuration has no such storage resource
     * @throws IllegalStateException    if the storage holds no run
     * @throws Exception                if the storage cannot be built or read
     */
    static String newest(final Config config, final String storageName) throws Exception {
        return withStorage(config, storageName, storage -> {
            final List<String> runIds = storage.listRunIds(Instant.EPOCH);
            if (runIds.isEmpty()) {
                throw new IllegalStateException(
                    "Storage resource '" + storageName + "' holds no simulation run. "
                        + "Name a run with --run, or point the storage at a directory that holds one.");
            }
            return runIds.get(runIds.size() - 1);
        });
    }

    /**
     * Returns the given run ID after making sure the named storage resource holds that run.
     *
     * @param config      the configuration of the invocation
     * @param storageName the name of the storage resource under {@code pipeline.resources}
     * @param runId       the run named on the command line
     * @return the run ID, unchanged
     * @throws IllegalArgumentException if the configuration has no such storage resource, or the
     *                                  storage holds no run of that ID
     * @throws Exception                if the storage cannot be built or read
     */
    static String existing(final Config config, final String storageName, final String runId) throws Exception {
        return withStorage(config, storageName, storage -> {
            if (storage.findMetadataPath(runId).isEmpty()) {
                throw new IllegalArgumentException(
                    "Storage resource '" + storageName + "' holds no run '" + runId + "'.");
            }
            return runId;
        });
    }

    /**
     * Builds the named storage resource from {@code pipeline.resources.<storageName>}, hands it
     * to the lookup and closes it again, so nothing of it outlives the lookup.
     *
     * @param config      the configuration of the invocation
     * @param storageName the name of the storage resource under {@code pipeline.resources}
     * @param lookup      what to ask the storage
     * @return what the lookup answered
     * @throws IllegalArgumentException if the configuration has no such storage resource
     * @throws Exception                if the storage cannot be built or read
     */
    private static String withStorage(final Config config, final String storageName,
                                      final Lookup lookup) throws Exception {
        final String path = "pipeline.resources." + storageName;
        if (!config.hasPath(path)) {
            throw new IllegalArgumentException(
                "Storage resource '" + storageName + "' is not configured under pipeline.resources.");
        }

        final IBatchStorageRead storage =
            CliResourceFactory.create(storageName, IBatchStorageRead.class, config.getConfig(path));
        try {
            return lookup.answer(storage);
        } finally {
            close(storage, storageName);
        }
    }

    /** A question to a storage resource that may fail like any storage access. */
    @FunctionalInterface
    private interface Lookup {
        String answer(IBatchStorageRead storage) throws Exception;
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
