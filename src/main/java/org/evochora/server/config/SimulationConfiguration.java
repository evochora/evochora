package org.evochora.server.config;

import java.util.List;
import java.util.Map;

/**
 * Holds simulation configuration loaded from JSON.
 */
public final class SimulationConfiguration {

    public static final class SimulationConfig {
        public EnvironmentConfig environment;
        public OrganismDefinition[] organisms;
        public List<EnergyStrategyConfig> energyStrategies;
        public Long seed;
    }

    public static final class EnvironmentConfig {
        public int[] shape;
        public boolean toroidal;
    }

    public static final class PlacementConfig {
        public String strategy; // e.g., "fixed" (only supported for now)
        public int[][] positions; // array of coordinates
    }

    public static final class OrganismDefinition {
        public String id;
        public String program; // resource path relative to org/evochora/organism/
        public int initialEnergy;
        public PlacementConfig placement;
    }

    public static final class EnergyStrategyConfig {
        public String type;
        public Map<String, Object> params;
    }

    public static final class PipelineConfig {
        public SimulationServiceConfig simulation;
        public IndexerServiceConfig indexer;
        public PersistenceServiceConfig persistence;
        public ServerServiceConfig server;
    }

    public static final class SimulationServiceConfig {
        public Boolean autoStart;
        public String outputPath;
    }

    public static final class IndexerServiceConfig {
        public boolean autoStart;
        public String inputPath;
        public String outputPath;
        public int batchSize = 1000; // Default batch size
    }

    public static final class PersistenceServiceConfig {
        public boolean autoStart;
        public int batchSize = 1000; // Default batch size
        public String jdbcUrl;
    }

    public static final class ServerServiceConfig {
        public Boolean autoStart;
        public String inputPath;
        public String debugDbFile; // optional: specific debug DB to serve
        public Integer port;
        public String host;
    }

    // Configuration structure
    public SimulationConfig simulation;
    public PipelineConfig pipeline;

    public int getDimensions() {
        if (simulation != null && simulation.environment != null && simulation.environment.shape != null) {
            return simulation.environment.shape.length;
        }
        return 2;
    }

    public Integer getWebPort() {
        if (pipeline != null && pipeline.server != null && pipeline.server.port != null) {
            return pipeline.server.port;
        }
        return 7070; // default
    }
}


