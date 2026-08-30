package org.evochora.datapipeline.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.resources.storage.PublishedOutputStream;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.ConfigFactory;

/**
 * Tests how the configured sampling interval is turned into absolute tick intervals.
 * <p>
 * The configured value counts recorded ticks, so the interval a plugin is matched against is the
 * product of the run's recording interval, the configured value and the LOD factor raised to the
 * level. These tests pin that product and the failure modes that must not degrade into a guess.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class AbstractAnalyticsPluginSamplingIntervalTest {

    @Test
    void effectiveInterval_multipliesRecordingIntervalWithConfiguredValue() {
        // Run records every 100th tick, plugin takes every 10th recording -> every 1000th tick
        TestPlugin plugin = initializedPlugin(Map.of(
            "metricId", "test", "samplingInterval", 10, "lodFactor", 10, "lodLevels", 3), 100);

        assertThat(plugin.getEffectiveSamplingInterval(0)).isEqualTo(1_000);
        assertThat(plugin.getEffectiveSamplingInterval(1)).isEqualTo(10_000);
        assertThat(plugin.getEffectiveSamplingInterval(2)).isEqualTo(100_000);
    }

    @Test
    void effectiveInterval_everyRecordedTickWhenConfiguredValueIsOne() {
        // Run records every 10000th tick, plugin takes every recording
        TestPlugin plugin = initializedPlugin(Map.of(
            "metricId", "test", "samplingInterval", 1, "lodFactor", 10, "lodLevels", 2), 10_000);

        assertThat(plugin.getEffectiveSamplingInterval(0)).isEqualTo(10_000);
        assertThat(plugin.getEffectiveSamplingInterval(1)).isEqualTo(100_000);
    }

    @Test
    void effectiveInterval_recordingEveryTickLeavesConfiguredValueUnchanged() {
        TestPlugin plugin = initializedPlugin(Map.of(
            "metricId", "test", "samplingInterval", 100, "lodFactor", 10, "lodLevels", 2), 1);

        assertThat(plugin.getEffectiveSamplingInterval(0)).isEqualTo(100);
        assertThat(plugin.getEffectiveSamplingInterval(1)).isEqualTo(1_000);
    }

    @Test
    void initialize_failsWhenMetadataStatesNoRecordingInterval() {
        TestPlugin plugin = new TestPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "test")));

        assertThatThrownBy(() -> plugin.initialize(contextWithConfig("{ \"environment\": {} }")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("samplingInterval");
    }

    @Test
    void initialize_failsOnInvalidRecordingInterval() {
        TestPlugin plugin = new TestPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "test")));

        assertThatThrownBy(() -> plugin.initialize(contextWithConfig("{ \"samplingInterval\": 0 }")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a valid recording interval");
    }

    @Test
    void effectiveInterval_failsWhenPluginWasInitializedWithoutContext() {
        TestPlugin plugin = new TestPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "test")));
        plugin.initialize(null);

        assertThatThrownBy(() -> plugin.getEffectiveSamplingInterval(0))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without an analytics context");
    }

    @Test
    void initialize_failsWhenContextCarriesNoRunConfiguration() {
        // A context whose metadata exists but states nothing would otherwise leave the intervals
        // unset, and the failure would surface inside the indexer's per-plugin bulkhead
        TestPlugin plugin = new TestPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "test")));

        assertThatThrownBy(() -> plugin.initialize(contextWithConfig("")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("carries no run configuration");
    }

    @Test
    void configure_rejectsIntervalsBelowOne() {
        assertThatThrownBy(() -> new TestPlugin().configure(ConfigFactory.parseMap(
                Map.of("metricId", "test", "samplingInterval", 0))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("samplingInterval must be at least 1");

        assertThatThrownBy(() -> new TestPlugin().configure(ConfigFactory.parseMap(
                Map.of("metricId", "test", "lodFactor", 0))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lodFactor must be at least 1");

        assertThatThrownBy(() -> new TestPlugin().configure(ConfigFactory.parseMap(
                Map.of("metricId", "test", "lodLevels", 0))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lodLevels must be at least 1");
    }

    @Test
    void effectiveInterval_failsForLevelOutsideConfiguredRange() {
        TestPlugin plugin = initializedPlugin(Map.of(
            "metricId", "test", "samplingInterval", 1, "lodLevels", 2), 1);

        assertThatThrownBy(() -> plugin.getEffectiveSamplingInterval(2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("outside the configured range");
    }

    @Test
    void initialize_failsWhenIntervalExceedsIntRange() {
        TestPlugin plugin = new TestPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of(
            "metricId", "test", "samplingInterval", 1000, "lodFactor", 10, "lodLevels", 8)));

        assertThatThrownBy(() -> plugin.initialize(contextWithConfig("{ \"samplingInterval\": 10000 }")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exceeds the supported range");
    }

    // ========================================================================
    // Test helpers
    // ========================================================================

    private TestPlugin initializedPlugin(Map<String, ?> configMap, int recordingInterval) {
        TestPlugin plugin = new TestPlugin();
        plugin.configure(ConfigFactory.parseMap(configMap));
        plugin.initialize(contextWithConfig("{ \"samplingInterval\": " + recordingInterval + " }"));
        return plugin;
    }

    private IAnalyticsContext contextWithConfig(String resolvedConfigJson) {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setResolvedConfigJson(resolvedConfigJson)
            .build();

        return new IAnalyticsContext() {
            @Override
            public SimulationMetadata getMetadata() {
                return metadata;
            }

            @Override
            public String getRunId() {
                return "test-run";
            }

            @Override
            public PublishedOutputStream openArtifactStream(String metricId, String lodLevel, String filename)
                    throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public Path getTempDirectory() {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** Minimal concrete plugin for testing AbstractAnalyticsPlugin. */
    private static class TestPlugin extends AbstractAnalyticsPlugin {
        @Override
        public ParquetSchema getSchema() {
            return ParquetSchema.builder().column("tick", ColumnType.BIGINT).build();
        }

        @Override
        public List<Object[]> extractRows(TickData tick) {
            return List.of();
        }

        @Override
        public ManifestEntry getManifestEntry() {
            ManifestEntry entry = new ManifestEntry();
            entry.id = metricId;
            entry.name = "Test Metric";
            return entry;
        }
    }
}
