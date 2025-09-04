package org.evochora.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contains unit tests for the {@link SimulationConfiguration} class.
 * These tests act as smoke tests to verify that the default configuration object
 * is loaded correctly and that its structure is valid and accessible.
 * These are unit tests and do not require external resources.
 */
class ConfigurationTest {

    /**
     * Verifies that the default configuration can be loaded without errors.
     * This is a unit test for the configuration loader.
     */
    @Test
    @Tag("unit")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConfigurationCanBeLoaded() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Verify configuration can be loaded
        assertNotNull(config, "Configuration should be loadable");
    }

    /**
     * Verifies that the 'simulation' section and its nested objects exist in the default configuration.
     * This is a unit test for the configuration structure.
     */
    @Test
    @Tag("unit")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testSimulationSectionExists() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Verify simulation section exists
        assertNotNull(config.simulation, "Simulation section should exist");
        assertNotNull(config.simulation.environment, "Environment section should exist");
        assertNotNull(config.simulation.environment.shape, "Environment shape should exist");

        // energyStrategies is optional and can be null
    }

    /**
     * Verifies that the 'pipeline' section and its nested service configurations exist.
     * This is a unit test for the configuration structure.
     */
    @Test
    @Tag("unit")
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

    /**
     * Verifies that all required fields and sections in the default configuration are non-null.
     * This is a unit test for the completeness of the default configuration.
     */
    @Test
    @Tag("unit")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testRequiredConfigurationFieldsArePresent() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Test that all required fields are present and accessible
        assertNotNull(config.simulation, "Simulation section required");
        assertNotNull(config.simulation.environment, "Environment section required");
        assertNotNull(config.simulation.environment.shape, "Environment shape required");

        // energyStrategies is optional and can be null
        
        assertNotNull(config.pipeline, "Pipeline section required");
        assertNotNull(config.pipeline.simulation, "Pipeline simulation required");
        assertNotNull(config.pipeline.indexer, "Indexer required");
        assertNotNull(config.pipeline.persistence, "Persistence required");
        assertNotNull(config.pipeline.server, "Server required");
    }

    /**
     * Verifies that the overall structure of the default configuration is valid and its nodes are accessible.
     * This is a unit test for the configuration's structural integrity.
     */
    @Test
    @Tag("unit")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConfigurationStructureIsValid() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Test that configuration structure is valid (not null, accessible)
        assertNotNull(config, "Configuration object should not be null");
        assertNotNull(config.simulation, "Simulation should not be null");
        assertNotNull(config.pipeline, "Pipeline should not be null");
        
        // Test that we can access configuration values without errors
        assertNotNull(config.simulation.environment.shape);
        // energyStrategies is optional and can be null
        assertNotNull(config.pipeline.simulation);
        assertNotNull(config.pipeline.indexer);
        assertNotNull(config.pipeline.persistence);
        assertNotNull(config.pipeline.server);
    }

    /**
     * Verifies that all nested properties within the default configuration are accessible and non-null.
     * This is a unit test to prevent regressions from configuration refactoring.
     */
    @Test
    @Tag("unit")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConfigurationIsAccessible() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Test that all configuration sections are accessible
        assertNotNull(config.simulation);
        assertNotNull(config.simulation.environment);
        assertNotNull(config.simulation.environment.shape);
        // energyStrategies is optional and can be null
        
        assertNotNull(config.pipeline);
        assertNotNull(config.pipeline.simulation);
        assertNotNull(config.pipeline.indexer);
        assertNotNull(config.pipeline.persistence);
        assertNotNull(config.pipeline.server);
        
        // Test that we can access nested properties without errors
        assertNotNull(config.pipeline.simulation.autoStart);
        assertNotNull(config.pipeline.simulation.outputPath);
        //assertNotNull(config.pipeline.simulation.autoPauseTicks);
        
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
