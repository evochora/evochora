package org.evochora.datapipeline.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.resources.storage.PublishedOutputStream;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Tests that maxDataPoints is read from config and applied to manifest entries.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class AbstractAnalyticsPluginMaxDataPointsTest {

    @Test
    void configure_maxDataPointsFromConfig() {
        TestPlugin plugin = createPlugin(Map.of("metricId", "test", "maxDataPoints", 200));
        assertThat(plugin.maxDataPoints).isEqualTo(200);
    }

    @Test
    void configure_maxDataPointsDefaultNull() {
        TestPlugin plugin = createPlugin(Map.of("metricId", "test"));
        assertThat(plugin.maxDataPoints).isNull();
    }

    @Test
    void getManifestEntries_appliesMaxDataPoints() {
        TestPlugin plugin = createPlugin(Map.of("metricId", "test", "maxDataPoints", 100));

        List<ManifestEntry> entries = plugin.getManifestEntries();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).maxDataPoints).isEqualTo(100);
    }

    @Test
    void getManifestEntries_leavesNullWhenNotConfigured() {
        TestPlugin plugin = createPlugin(Map.of("metricId", "test"));

        List<ManifestEntry> entries = plugin.getManifestEntries();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).maxDataPoints).isNull();
    }

    @Test
    void getManifestEntries_namesTheTickIntervalOfEveryLevel() {
        // Recording every 100 ticks, a plugin taking every second recording, factor 10 per level
        TestPlugin plugin = new TestPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of(
            "metricId", "test", "samplingInterval", 2, "lodFactor", 10, "lodLevels", 3)));
        plugin.initialize(runRecordingEvery(100));

        ManifestEntry entry = plugin.getManifestEntries().get(0);

        assertThat(entry.tickIntervals)
            .containsExactly(Map.entry("lod0", 200), Map.entry("lod1", 2000), Map.entry("lod2", 20000));
    }

    @Test
    void getManifestEntries_returnsEmptyForNullEntry() {
        NullEntryPlugin plugin = new NullEntryPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "test")));

        List<ManifestEntry> entries = plugin.getManifestEntries();

        assertThat(entries).isEmpty();
    }

    // ========================================================================
    // Test helpers
    // ========================================================================

    /**
     * A configured and initialized plugin. A manifest entry names the tick interval of every
     * level, which a plugin only knows once it has read the recording interval of the run.
     */
    private TestPlugin createPlugin(Map<String, ?> configMap) {
        TestPlugin plugin = new TestPlugin();
        Config config = ConfigFactory.parseMap(configMap);
        plugin.configure(config);
        plugin.initialize(runRecordingEvery(100));
        return plugin;
    }

    /** A context for a run that records every {@code interval} ticks. */
    private IAnalyticsContext runRecordingEvery(int interval) {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setResolvedConfigJson("{ \"samplingInterval\": " + interval + " }")
            .build();
        return new IAnalyticsContext() {
            @Override public SimulationMetadata getMetadata() { return metadata; }
            @Override public String getRunId() { return "test-run"; }
            @Override public PublishedOutputStream openArtifactStream(String m, String l, String f) {
                throw new UnsupportedOperationException();
            }
            @Override public java.nio.file.Path getTempDirectory() {
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

    /** Plugin that returns null from getManifestEntry. */
    private static class NullEntryPlugin extends TestPlugin {
        @Override
        public ManifestEntry getManifestEntry() {
            return null;
        }
    }
}
