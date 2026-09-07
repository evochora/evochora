package org.evochora.datapipeline.utils;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.runtime.model.EnvironmentProperties;

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
     * Builds the environment properties of a run from its metadata.
     * <p>
     * Shape and topology are all a consumer needs to turn a persisted flat index back into a
     * coordinate and to measure a distance the way the run measured it. The two values are read
     * here together so that every consumer describes the same world; deriving them separately
     * invites one place to read the topology and another to assume it.
     *
     * @param metadata The simulation metadata
     * @return The world the run took place in
     */
    public static EnvironmentProperties environmentProperties(SimulationMetadata metadata) {
        return new EnvironmentProperties(
            getEnvironmentShape(metadata),
            isEnvironmentToroidal(metadata)
        );
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
