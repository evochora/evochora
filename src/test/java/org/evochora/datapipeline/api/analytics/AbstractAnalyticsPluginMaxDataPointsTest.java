package org.evochora.datapipeline.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

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
    void getManifestEntries_returnsEmptyForNullEntry() {
        NullEntryPlugin plugin = new NullEntryPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "test")));

        List<ManifestEntry> entries = plugin.getManifestEntries();

        assertThat(entries).isEmpty();
    }

    // ========================================================================
    // Test helpers
    // ========================================================================

    private TestPlugin createPlugin(Map<String, ?> configMap) {
        TestPlugin plugin = new TestPlugin();
        Config config = ConfigFactory.parseMap(configMap);
        plugin.configure(config);
        return plugin;
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
