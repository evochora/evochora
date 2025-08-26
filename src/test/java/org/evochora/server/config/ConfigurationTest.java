package org.evochora.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConfigurationCanBeLoaded() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Verify configuration can be loaded
        assertNotNull(config, "Configuration should be loadable");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testSimulationSectionExists() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Verify simulation section exists
        assertNotNull(config.simulation, "Simulation section should exist");
        assertNotNull(config.simulation.environment, "Environment section should exist");
        assertNotNull(config.simulation.environment.shape, "Environment shape should exist");
        assertNotNull(config.simulation.organisms, "Organisms section should exist");
        assertNotNull(config.simulation.energyStrategies, "Energy strategies should exist");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testPipelineSectionExists() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Verify pipeline section exists
        assertNotNull(config.pipeline, "Pipeline section should exist");
        assertNotNull(config.pipeline.simulation, "Pipeline simulation section should exist");
        assertNotNull(config.pipeline.indexer, "Indexer section should exist");
        assertNotNull(config.pipeline.persistence, "Persistence section should exist");
        assertNotNull(config.pipeline.server, "Server section should exist");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testRequiredConfigurationFieldsArePresent() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Test that all required fields are present and accessible
        assertNotNull(config.simulation, "Simulation section required");
        assertNotNull(config.simulation.environment, "Environment section required");
        assertNotNull(config.simulation.environment.shape, "Environment shape required");
        assertNotNull(config.simulation.organisms, "Organisms required");
        assertNotNull(config.simulation.energyStrategies, "Energy strategies required");
        
        assertNotNull(config.pipeline, "Pipeline section required");
        assertNotNull(config.pipeline.simulation, "Pipeline simulation required");
        assertNotNull(config.pipeline.indexer, "Indexer required");
        assertNotNull(config.pipeline.persistence, "Persistence required");
        assertNotNull(config.pipeline.server, "Server required");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConfigurationStructureIsValid() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Test that configuration structure is valid (not null, accessible)
        assertNotNull(config, "Configuration object should not be null");
        assertNotNull(config.simulation, "Simulation should not be null");
        assertNotNull(config.pipeline, "Pipeline should not be null");
        
        // Test that we can access configuration values without errors
        assertNotNull(config.simulation.environment.shape);
        assertNotNull(config.simulation.organisms);
        assertNotNull(config.simulation.energyStrategies);
        assertNotNull(config.pipeline.simulation);
        assertNotNull(config.pipeline.indexer);
        assertNotNull(config.pipeline.persistence);
        assertNotNull(config.pipeline.server);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConfigurationIsAccessible() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Test that all configuration sections are accessible
        assertNotNull(config.simulation);
        assertNotNull(config.simulation.environment);
        assertNotNull(config.simulation.environment.shape);
        assertNotNull(config.simulation.organisms);
        assertNotNull(config.simulation.energyStrategies);
        
        assertNotNull(config.pipeline);
        assertNotNull(config.pipeline.simulation);
        assertNotNull(config.pipeline.indexer);
        assertNotNull(config.pipeline.persistence);
        assertNotNull(config.pipeline.server);
        
        // Test that we can access nested properties without errors
        assertNotNull(config.pipeline.simulation.autoStart);
        assertNotNull(config.pipeline.simulation.outputPath);
        assertNotNull(config.pipeline.simulation.autoPauseTicks);
        
        assertNotNull(config.pipeline.indexer.autoStart);
        assertNotNull(config.pipeline.indexer.inputPath);
        assertNotNull(config.pipeline.indexer.outputPath);
        assertNotNull(config.pipeline.indexer.batchSize);
        
        assertNotNull(config.pipeline.persistence.autoStart);
        assertNotNull(config.pipeline.persistence.batchSize);
        
        assertNotNull(config.pipeline.server.autoStart);
        assertNotNull(config.pipeline.server.inputPath);
        assertNotNull(config.pipeline.server.port);
        assertNotNull(config.pipeline.server.host);
    }
}
