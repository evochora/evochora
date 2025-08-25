package org.evochora.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the simulation configuration from JSON.
 */
public final class ConfigLoader {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_RESOURCE_PATH = "org/evochora/config/config.json";

    private ConfigLoader() {}

    public static SimulationConfiguration loadDefault() {
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (is != null) {
                return OBJECT_MAPPER.readValue(is, SimulationConfiguration.class);
            }
        } catch (Exception ignore) {}
        
        // Fallback default with new structure
        SimulationConfiguration cfg = new SimulationConfiguration();
        
        // Create simulation config
        SimulationConfiguration.SimulationConfig simConfig = new SimulationConfiguration.SimulationConfig();
        simConfig.environment = new SimulationConfiguration.EnvironmentConfig();
        simConfig.environment.shape = new int[]{120, 80};
        simConfig.environment.toroidal = true;
        simConfig.seed = 123456789L;
        cfg.simulation = simConfig;
        
        // Create pipeline config
        SimulationConfiguration.PipelineConfig pipelineConfig = new SimulationConfiguration.PipelineConfig();
        
        SimulationConfiguration.SimulationServiceConfig simService = new SimulationConfiguration.SimulationServiceConfig();
        simService.autoStart = true;
        simService.outputPath = "runs/";
        pipelineConfig.simulation = simService;
        
        SimulationConfiguration.IndexerServiceConfig indexerService = new SimulationConfiguration.IndexerServiceConfig();
        indexerService.autoStart = true;
        indexerService.inputPath = "runs/";
        indexerService.outputPath = "runs/";
        indexerService.batchSize = 1000;
        pipelineConfig.indexer = indexerService;
        
        SimulationConfiguration.PersistenceServiceConfig persistenceService = new SimulationConfiguration.PersistenceServiceConfig();
        persistenceService.autoStart = true;
        persistenceService.batchSize = 1000;
        pipelineConfig.persistence = persistenceService;
        
        SimulationConfiguration.ServerServiceConfig serverService = new SimulationConfiguration.ServerServiceConfig();
        serverService.autoStart = true;
        serverService.inputPath = "runs/";
        serverService.port = 7070;
        serverService.host = "localhost";
        pipelineConfig.server = serverService;
        
        cfg.pipeline = pipelineConfig;
        
        return cfg;
    }

    public static SimulationConfiguration loadFromFile(Path file) throws Exception {
        try (InputStream is = Files.newInputStream(file)) {
            return OBJECT_MAPPER.readValue(is, SimulationConfiguration.class);
        }
    }
}


