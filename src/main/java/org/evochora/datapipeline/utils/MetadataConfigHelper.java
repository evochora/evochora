package org.evochora.datapipeline.utils;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.memory.SimulationParameters;

/**
 * Helper class to extract configuration values from SimulationMetadata.
 * <p>
 * Since SimulationMetadata stores most configuration in resolvedConfigJson,
 * this helper provides convenient access to commonly needed values without
 * requiring each caller to parse the JSON themselves.
 */
public final class MetadataConfigHelper {

    private MetadataConfigHelper() {
        // Utility class
    }

    /**
     * Parses the resolved config from metadata.
     *
     * @param metadata The simulation metadata
     * @return The parsed Config object
     */
    public static Config getResolvedConfig(SimulationMetadata metadata) {
        return ConfigFactory.parseString(metadata.getResolvedConfigJson());
    }

    /**
     * Gets the environment shape from metadata.
     *
     * @param metadata The simulation metadata
     * @return Array of dimension sizes
     */
    public static int[] getEnvironmentShape(SimulationMetadata metadata) {
        Config config = getResolvedConfig(metadata);
        return config.getIntList("environment.shape").stream().mapToInt(i -> i).toArray();
    }

    /**
     * Gets whether the environment is toroidal from metadata.
     *
     * @param metadata The simulation metadata
     * @return true if toroidal, false otherwise
     */
    public static boolean isEnvironmentToroidal(SimulationMetadata metadata) {
        Config config = getResolvedConfig(metadata);
        return "TORUS".equalsIgnoreCase(config.getString("environment.topology"));
    }

    /**
     * Gets the sampling interval from metadata.
     *
     * @param metadata The simulation metadata
     * @return The sampling interval (default: 1)
     */
    public static int getSamplingInterval(SimulationMetadata metadata) {
        Config config = getResolvedConfig(metadata);
        return config.hasPath("samplingInterval") ? config.getInt("samplingInterval") : 1;
    }

    /**
     * Gets the accumulated delta interval from metadata.
     *
     * @param metadata The simulation metadata
     * @return The accumulated delta interval
     */
    public static int getAccumulatedDeltaInterval(SimulationMetadata metadata) {
        Config config = getResolvedConfig(metadata);
        return config.hasPath("accumulatedDeltaInterval")
            ? config.getInt("accumulatedDeltaInterval")
            : SimulationParameters.DEFAULT_ACCUMULATED_DELTA_INTERVAL;
    }

    /**
     * Gets the snapshot interval from metadata.
     *
     * @param metadata The simulation metadata
     * @return The snapshot interval
     */
    public static int getSnapshotInterval(SimulationMetadata metadata) {
        Config config = getResolvedConfig(metadata);
        return config.hasPath("snapshotInterval")
            ? config.getInt("snapshotInterval")
            : SimulationParameters.DEFAULT_SNAPSHOT_INTERVAL;
    }

    /**
     * Gets the chunk interval from metadata.
     *
     * @param metadata The simulation metadata
     * @return The chunk interval
     */
    public static int getChunkInterval(SimulationMetadata metadata) {
        Config config = getResolvedConfig(metadata);
        return config.hasPath("chunkInterval")
            ? config.getInt("chunkInterval")
            : SimulationParameters.DEFAULT_CHUNK_INTERVAL;
    }
}
