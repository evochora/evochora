package org.evochora.datapipeline.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for the accessors that read a run's configuration back out of its metadata.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class MetadataConfigHelperTest {

    @Test
    void environmentPropertiesReadsAToroidalWorld() {
        SimulationMetadata metadata = metadataWith(TestMetadataHelper.builder()
            .shape(100, 100)
            .toroidal(true)
            .build());

        EnvironmentProperties props = MetadataConfigHelper.environmentProperties(metadata);

        assertThat(props.getWorldShape()).containsExactly(100, 100);
        assertThat(props.isToroidal()).isTrue();
    }

    @Test
    void environmentPropertiesReadsABoundedWorldOfThreeDimensions() {
        SimulationMetadata metadata = metadataWith(configJson("[10, 10, 10]", "BOUNDED"));

        EnvironmentProperties props = MetadataConfigHelper.environmentProperties(metadata);

        assertThat(props.getWorldShape()).containsExactly(10, 10, 10);
        assertThat(props.isToroidal()).isFalse();
    }

    @Test
    void environmentPropertiesReadsAWorldOfOneDimension() {
        SimulationMetadata metadata = metadataWith(configJson("[1000]", "TORUS"));

        EnvironmentProperties props = MetadataConfigHelper.environmentProperties(metadata);

        assertThat(props.getWorldShape()).containsExactly(1000);
        assertThat(props.isToroidal()).isTrue();
    }

    private static SimulationMetadata metadataWith(String resolvedConfigJson) {
        return SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setResolvedConfigJson(resolvedConfigJson)
            .build();
    }

    /**
     * Builds a resolved config for a shape the shared test helper cannot express, which only
     * builds two-dimensional worlds.
     */
    private static String configJson(String shape, String topology) {
        return String.format("""
            {
                "environment": {
                    "shape": %s,
                    "topology": "%s"
                },
                "samplingInterval": 1,
                "accumulatedDeltaInterval": 100,
                "snapshotInterval": 10,
                "chunkInterval": 1,
                "plugins": [],
                "organisms": []
            }
            """, shape, topology);
    }
}
