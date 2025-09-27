package org.evochora.node.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigLoader to verify the configuration priority hierarchy:
 * 1. System Properties (highest priority)
 * 2. Environment Variables (mapped to dot-notation)
 * 3. Configuration File
 * 4. Default reference configuration (lowest priority)
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class ConfigLoaderTest {

    @BeforeEach
    void setUp() {
        // Invalidate the cache before each test to ensure a clean slate
        ConfigFactory.invalidateCaches();
    }

    @AfterEach
    void tearDown() {
        // Clean up all system properties used in tests
        System.clearProperty("test.value");
        System.clearProperty("test.priority");
        System.clearProperty("test.nested.setting");
        System.clearProperty("test.base-value");
        System.clearProperty("test.referenced-value");
        
        // Invalidate ConfigFactory cache to ensure test isolation
        ConfigFactory.invalidateCaches();
    }

    @Test
    @DisplayName("Should load configuration file with defaults when no overrides present")
    void load_shouldLoadConfigFileWithDefaults() {
        // Act
        Config config = ConfigLoader.load("org/evochora/node/config/test-config.conf");

        // Assert
        assertNotNull(config);
        assertTrue(config.hasPath("test.value"));
        assertEquals("file-value", config.getString("test.value"));
        assertEquals("file-priority", config.getString("test.priority"));
        assertEquals("file-nested", config.getString("test.nested.setting"));
    }

    @Test
    @DisplayName("System property should override file configuration")
    void load_systemPropertyShouldOverrideFileConfig() {
        // Arrange - Set system property to override file configuration
        System.setProperty("test.value", "system-value");
        // Invalidate cache after setting system property to ensure it's picked up
        ConfigFactory.invalidateCaches();

        // Act
        Config config = ConfigLoader.load("org/evochora/node/config/test-config.conf");

        // Assert - System property should override file config
        assertEquals("system-value", config.getString("test.value"));
        // File config should still be used for other values
        assertEquals("file-priority", config.getString("test.priority"));
    }

    @Test
    @DisplayName("System property should override nested configuration values")
    void load_systemPropertyShouldOverrideNestedConfig() {
        // Arrange
        System.setProperty("test.nested.setting", "system-nested");
        // Invalidate cache after setting system property to ensure it's picked up
        ConfigFactory.invalidateCaches();

        // Act
        Config config = ConfigLoader.load("org/evochora/node/config/test-config.conf");

        // Assert
        assertEquals("system-nested", config.getString("test.nested.setting"));
        assertEquals("file-value", config.getString("test.value")); // Other values unchanged
    }

    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = "Configuration file 'non-existent-config.conf' not found or is empty. Using defaults.")
    @DisplayName("Should handle missing configuration file gracefully")
    void load_shouldHandleMissingConfigFile() {
        // Act
        Config config = ConfigLoader.load("non-existent-config.conf");

        // Assert
        assertNotNull(config);
        // Should still have access to reference configuration defaults
        // (assuming reference.conf exists with some default values)
    }

    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = "Configuration file.*not found or is empty. Using defaults.")
    @DisplayName("Should handle empty configuration file")
    void load_shouldHandleEmptyConfigFile() {
        // Act
        Config config = ConfigLoader.load("org/evochora/node/config/empty-config");

        // Assert
        assertNotNull(config);
        // Should still have access to reference configuration defaults
    }

    @Test
    @DisplayName("Should preserve configuration hierarchy order")
    void load_shouldPreserveConfigurationHierarchyOrder() {
        // Arrange - Set system properties to override file configuration
        System.setProperty("test.priority", "system-priority");
        System.setProperty("test.value", "system-value");
        // Invalidate cache after setting system properties to ensure they're picked up
        ConfigFactory.invalidateCaches();
        
        // File already has: test.value = "file-value", test.priority = "file-priority"

        // Act
        Config config = ConfigLoader.load("org/evochora/node/config/test-config.conf");

        // Assert - Verify priority order: System > File > Defaults
        assertEquals("system-priority", config.getString("test.priority")); // System wins
        assertEquals("system-value", config.getString("test.value")); // System wins over file
        assertEquals("file-nested", config.getString("test.nested.setting")); // File value (no override)
    }

    @Test
    @DisplayName("Should resolve configuration references correctly")
    void load_shouldResolveConfigurationReferences() {
        // Arrange - Set system property to override
        System.setProperty("test.priority", "system-override");
        // Invalidate cache after setting system property to ensure it's picked up
        ConfigFactory.invalidateCaches();

        // Act
        Config config = ConfigLoader.load("org/evochora/node/config/references-config.conf");

        // Assert
        assertEquals("base-suffix", config.getString("test.referenced-value"));
        assertEquals("system-override", config.getString("test.priority")); // System should still override
    }
}
