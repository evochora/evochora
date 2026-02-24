package org.evochora.cli.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConfigLoader} to verify the configuration priority hierarchy:
 * <ol>
 *   <li>System Properties (highest priority)</li>
 *   <li>Environment Variables</li>
 *   <li>Configuration File</li>
 *   <li>Default reference configuration (lowest priority)</li>
 * </ol>
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class ConfigLoaderTest {

    @BeforeEach
    void setUp() {
        ConfigFactory.invalidateCaches();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("test.value");
        System.clearProperty("test.priority");
        System.clearProperty("test.nested.setting");
        System.clearProperty("test.base-value");
        System.clearProperty("test.referenced-value");
        ConfigFactory.invalidateCaches();
    }

    @Test
    @DisplayName("loadFromFile should load configuration file with defaults when no overrides present")
    void loadFromFile_shouldLoadConfigFileWithDefaults() {
        Config config = ConfigLoader.loadFromFile(testResource("test-config.conf"));

        assertNotNull(config);
        assertTrue(config.hasPath("test.value"));
        assertEquals("file-value", config.getString("test.value"));
        assertEquals("file-priority", config.getString("test.priority"));
        assertEquals("file-nested", config.getString("test.nested.setting"));
    }

    @Test
    @DisplayName("System property should override file configuration")
    void loadFromFile_systemPropertyShouldOverrideFileConfig() {
        System.setProperty("test.value", "system-value");
        ConfigFactory.invalidateCaches();

        Config config = ConfigLoader.loadFromFile(testResource("test-config.conf"));

        assertEquals("system-value", config.getString("test.value"));
        assertEquals("file-priority", config.getString("test.priority"));
    }

    @Test
    @DisplayName("System property should override nested configuration values")
    void loadFromFile_systemPropertyShouldOverrideNestedConfig() {
        System.setProperty("test.nested.setting", "system-nested");
        ConfigFactory.invalidateCaches();

        Config config = ConfigLoader.loadFromFile(testResource("test-config.conf"));

        assertEquals("system-nested", config.getString("test.nested.setting"));
        assertEquals("file-value", config.getString("test.value"));
    }

    @Test
    @DisplayName("loadDefaults should return valid config without a config file")
    void loadDefaults_shouldReturnValidConfig() {
        Config config = ConfigLoader.loadDefaults();

        assertNotNull(config);
    }

    @Test
    @DisplayName("Should preserve configuration hierarchy order")
    void loadFromFile_shouldPreserveConfigurationHierarchyOrder() {
        System.setProperty("test.priority", "system-priority");
        System.setProperty("test.value", "system-value");
        ConfigFactory.invalidateCaches();

        Config config = ConfigLoader.loadFromFile(testResource("test-config.conf"));

        assertEquals("system-priority", config.getString("test.priority"));
        assertEquals("system-value", config.getString("test.value"));
        assertEquals("file-nested", config.getString("test.nested.setting"));
    }

    @Test
    @DisplayName("Should resolve configuration references correctly")
    void loadFromFile_shouldResolveConfigurationReferences() {
        System.setProperty("test.priority", "system-override");
        ConfigFactory.invalidateCaches();

        Config config = ConfigLoader.loadFromFile(testResource("references-config.conf"));

        assertEquals("base-suffix", config.getString("test.referenced-value"));
        assertEquals("system-override", config.getString("test.priority"));
    }

    @Test
    @DisplayName("Profile override should propagate through substitution chain to downstream services")
    void loadFromFile_profileOverrideShouldPropagateToServices() {
        Config userConfig = ConfigFactory.parseString("pipeline.tuning = ${profiles.sampled}");
        Config config = ConfigFactory.systemProperties()
                .withFallback(userConfig)
                .withFallback(ConfigFactory.defaultReferenceUnresolved())
                .resolve();

        // Tuning level: profile values applied
        assertEquals(5000, config.getInt("pipeline.tuning.samplingInterval"));
        assertEquals(3, config.getInt("pipeline.tuning.insertBatchSize"));
        assertEquals(10000, config.getInt("pipeline.tuning.flushTimeoutMs"));

        // Downstream: simulation-engine picks up tuning via ${pipeline.tuning.xxx}
        assertEquals(5000, config.getInt("pipeline.services.simulation-engine.options.samplingInterval"));
        assertEquals(5, config.getInt("pipeline.services.simulation-engine.options.accumulatedDeltaInterval"));

        // Downstream: persistence-service picks up tuning via ${pipeline.tuning.xxx}
        assertEquals(1, config.getInt("pipeline.services.persistence-service-1.options.maxBatchSize"));
        assertEquals(60, config.getInt("pipeline.services.persistence-service-1.options.batchTimeoutSeconds"));

        // Downstream: indexer picks up tuning via ${pipeline.tuning.xxx}
        assertEquals(3, config.getInt("pipeline.services.environment-indexer-1.options.insertBatchSize"));
    }

    /**
     * Locates a test resource file on the classpath.
     *
     * @param name the resource file name (relative to this test class's package).
     * @return the {@link File} pointing to the test resource.
     */
    private File testResource(final String name) {
        final URL url = getClass().getResource(name);
        assertNotNull(url, "Test resource not found: " + name);
        try {
            return new File(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid test resource URI: " + url, e);
        }
    }
}
