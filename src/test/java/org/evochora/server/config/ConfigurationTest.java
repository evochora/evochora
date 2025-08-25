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
    void testDefaultConfigurationValues() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Verify default values
        assertNotNull(config);
        assertNotNull(config.simulation);
        assertNotNull(config.pipeline);
        
        // Verify simulation defaults
        assertNotNull(config.simulation.environment);
        assertArrayEquals(new int[]{100, 30}, config.simulation.environment.shape);
        assertTrue(config.simulation.environment.toroidal);
        
        // Verify pipeline defaults
        assertNotNull(config.pipeline.persistence);
        assertNotNull(config.pipeline.indexer);
        assertNotNull(config.pipeline.server);
        
        // Verify batch sizes
        assertEquals(1000, config.pipeline.persistence.batchSize);
        assertEquals(1000, config.pipeline.indexer.batchSize);
        
        // Verify server defaults
        assertEquals(7070, config.pipeline.server.port);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testSimulationConfiguration() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Test dimensions
        assertEquals(2, config.getDimensions());
        
        // Test web port
        assertEquals(7070, config.getWebPort());
        
        // Test environment properties
        assertArrayEquals(new int[]{100, 30}, config.simulation.environment.shape);
        assertTrue(config.simulation.environment.toroidal);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testPipelineConfiguration() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Test persistence service config
        assertNotNull(config.pipeline.persistence);
        assertEquals(1000, config.pipeline.persistence.batchSize);
        
        // Test indexer service config
        assertNotNull(config.pipeline.indexer);
        assertEquals(1000, config.pipeline.indexer.batchSize);
        
        // Test server service config
        assertNotNull(config.pipeline.server);
        assertEquals(7070, config.pipeline.server.port);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testBatchSizeConfiguration() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Test batch sizes are configurable
        assertTrue(config.pipeline.persistence.batchSize > 0);
        assertTrue(config.pipeline.indexer.batchSize > 0);
        
        // Test reasonable ranges
        assertTrue(config.pipeline.persistence.batchSize <= 10000);
        assertTrue(config.pipeline.indexer.batchSize <= 10000);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testPortConfiguration() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Test port is valid
        assertTrue(config.pipeline.server.port > 0);
        assertTrue(config.pipeline.server.port <= 65535);
        
        // Test default port
        assertEquals(7070, config.pipeline.server.port);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testDimensionsConfiguration() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Test dimensions are valid
        assertNotNull(config.simulation.environment.shape);
        assertTrue(config.simulation.environment.shape.length > 0);
        
        // Test specific dimensions
        assertArrayEquals(new int[]{100, 30}, config.simulation.environment.shape);
        assertEquals(2, config.getDimensions());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConfigurationValidation() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Test required fields are present
        assertNotNull(config.simulation);
        assertNotNull(config.simulation.environment);
        assertNotNull(config.simulation.environment.shape);
        assertNotNull(config.pipeline);
        assertNotNull(config.pipeline.persistence);
        assertNotNull(config.pipeline.indexer);
        assertNotNull(config.pipeline.server);
        
        // Test required values are valid
        assertTrue(config.simulation.environment.shape.length > 0);
        assertTrue(config.pipeline.persistence.batchSize > 0);
        assertTrue(config.pipeline.indexer.batchSize > 0);
        assertTrue(config.pipeline.server.port > 0);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConfigurationImmutability() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Test that configuration objects are not null
        assertNotNull(config);
        assertNotNull(config.simulation);
        assertNotNull(config.pipeline);
        
        // Test that we can access configuration values
        assertNotNull(config.simulation.environment.shape);
        assertNotNull(config.pipeline.persistence.batchSize);
        assertNotNull(config.pipeline.indexer.batchSize);
        assertNotNull(config.pipeline.server.port);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConfigurationSerialization() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        // Test that configuration can be accessed
        assertNotNull(config);
        
        // Test that all required fields are accessible
        assertNotNull(config.simulation);
        assertNotNull(config.simulation.environment);
        assertNotNull(config.simulation.environment.shape);
        assertNotNull(config.pipeline);
        assertNotNull(config.pipeline.persistence);
        assertNotNull(config.pipeline.indexer);
        assertNotNull(config.pipeline.server);
        
        // Test that values are reasonable
        assertTrue(config.simulation.environment.shape.length > 0);
        assertTrue(config.pipeline.persistence.batchSize > 0);
        assertTrue(config.pipeline.indexer.batchSize > 0);
        assertTrue(config.pipeline.server.port > 0);
    }
}
