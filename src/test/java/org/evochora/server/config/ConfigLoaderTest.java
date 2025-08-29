package org.evochora.server.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Tag;

/**
 * Contains unit tests for the {@link ConfigLoader}.
 * These tests verify that the default simulation and server configuration
 * can be loaded correctly and contains the expected values.
 * These are unit tests and do not require external resources.
 */
class ConfigLoaderTest {

    /**
     * Verifies that loading the default configuration produces a valid, non-null
     * {@link SimulationConfiguration} object with all expected default values for
     * the simulation, pipeline, and server settings.
     * This is a unit test for the configuration loading logic.
     */
    @Test
    @Tag("unit")
    void loadDefault_shouldCreateValidConfiguration() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        
        assertThat(config).isNotNull();
        assertThat(config.simulation).isNotNull();
        assertThat(config.pipeline).isNotNull();
        
        // Check simulation config
        assertThat(config.simulation.environment).isNotNull();
        assertThat(config.simulation.environment.shape).isEqualTo(new int[]{100, 30});
        assertThat(config.simulation.environment.toroidal).isTrue();
        assertThat(config.simulation.seed).isEqualTo(123456789L);
        
        // Check pipeline config
        assertThat(config.pipeline.simulation).isNotNull();
        assertThat(config.pipeline.simulation.autoStart).isTrue();
        assertThat(config.pipeline.simulation.outputPath).isEqualTo("runs/");
        
        assertThat(config.pipeline.indexer).isNotNull();
        assertThat(config.pipeline.indexer.autoStart).isTrue();
        assertThat(config.pipeline.indexer.inputPath).isEqualTo("runs/");
        assertThat(config.pipeline.indexer.outputPath).isEqualTo("runs/");
        
        assertThat(config.pipeline.server).isNotNull();
        assertThat(config.pipeline.server.autoStart).isTrue();
        assertThat(config.pipeline.server.inputPath).isEqualTo("runs/");
        assertThat(config.pipeline.server.port).isEqualTo(7070);
        assertThat(config.pipeline.server.host).isEqualTo("localhost");
    }

    /**
     * Verifies that the getDimensions() helper method correctly retrieves the world dimensions
     * from the nested configuration structure.
     * This is a unit test for a configuration accessor.
     */
    @Test
    @Tag("unit")
    void getDimensions_shouldWorkWithNewStructure() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        assertThat(config.getDimensions()).isEqualTo(2);
    }

    /**
     * Verifies that the getWebPort() helper method correctly retrieves the web server port
     * from the nested configuration structure.
     * This is a unit test for a configuration accessor.
     */
    @Test
    @Tag("unit")
    void getWebPort_shouldWorkWithNewStructure() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        assertThat(config.getWebPort()).isEqualTo(7070);
    }
}
