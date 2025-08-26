package org.evochora.server.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Tag;

class ConfigLoaderTest {

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

    @Test
    @Tag("unit")
    void getDimensions_shouldWorkWithNewStructure() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        assertThat(config.getDimensions()).isEqualTo(2);
    }

    @Test
    @Tag("unit")
    void getWebPort_shouldWorkWithNewStructure() {
        SimulationConfiguration config = ConfigLoader.loadDefault();
        assertThat(config.getWebPort()).isEqualTo(7070);
    }
}
