package org.evochora.server.config;

import java.util.List;
import java.util.Map;

/**
 * Holds simulation configuration loaded from JSON.
 */
public final class SimulationConfiguration {

    public static final class SimulationConfig {
        public EnvironmentConfig environment;
        public List<EnergyStrategyConfig> energyStrategies;
        public List<OrganismConfig> organisms;
        public Long seed;
    }

    public static final class EnvironmentConfig {
        public int[] shape;
        public boolean toroidal;
    }



    public static final class EnergyStrategyConfig {
        public String type;
        public Map<String, Object> params;
    }

    public static final class OrganismConfig {
        public String program;
        public int initialEnergy;
        public PlacementConfig placement;
    }

    public static final class PlacementConfig {
        public String strategy;
        public List<int[]> positions;
    }

    public static final class PipelineConfig {
        public SimulationServiceConfig simulation;
        public IndexerServiceConfig indexer;
        public PersistenceServiceConfig persistence;
        public ServerServiceConfig server;
        public LoggingConfig logging;
    }

    public static final class SimulationServiceConfig {
        public Boolean autoStart;
        public String outputPath;
        public int[] autoPauseTicks; // Array of tick values where simulation should auto-pause
        public Boolean skipProgramArtefact; // Whether to skip ProgramArtifact features (default: false)
        public Integer maxMessageCount; // Maximum number of messages in queue (default: 10000)
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

    /**
     * Configuration for logging behavior.
     */
    public static final class LoggingConfig {
        /** Default log level for all loggers not explicitly configured. */
        public String defaultLogLevel = "WARN";
        
        /** Per-logger specific log levels. Key is logger name, value is log level. */
        public Map<String, String> logLevels = Map.of(
            "org.evochora.server.ServiceManager", "INFO"
        );
    }
}


