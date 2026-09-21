package org.evochora.cli.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;

/**
 * Tests for the LoggingConfigurator class.
 */
@Tag("unit")
class LoggingConfiguratorTest {

    @BeforeEach
    void setUp() {
        LoggingConfigurator.reset();
    }

    @AfterEach
    void tearDown() {
        LoggingConfigurator.reset();
    }

    @Test
    void configure_withPlainFormat_shouldSetPlainFormat() {
        // Given
        final Config config = ConfigFactory.parseString("""
            logging {
              format = "PLAIN"
              default-level = "INFO"
            }
            """);

        // When
        LoggingConfigurator.configure(config);

        // Then
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        final String formatProperty = context.getProperty("evochora.logging.format");
        assertEquals("STDOUT_PLAIN", formatProperty, "PLAIN format should set STDOUT_PLAIN property");
    }

    @Test
    void configure_withPlainFormat_shouldSetAppenderCorrectly() {
        // Given
        final Config config = ConfigFactory.parseString("""
            logging {
              format = "PLAIN"
              default-level = "INFO"
            }
            """);

        // When
        LoggingConfigurator.configure(config);
        reconfigureLogbackForTest(); // Force Logback to apply the new properties

        // Then
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        final ch.qos.logback.classic.Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);

        // Verify that the correct appender is attached
        final Appender<?> plainAppender = rootLogger.getAppender("STDOUT_PLAIN");
        assertNotNull(plainAppender, "The STDOUT_PLAIN appender should be attached to the root logger.");
        assertTrue(plainAppender.isStarted(), "The STDOUT_PLAIN appender should be started.");

        final Appender<?> jsonAppender = rootLogger.getAppender("STDOUT");
        assertNull(jsonAppender, "The STDOUT (JSON) appender should NOT be attached when PLAIN is configured.");
    }

    private void reconfigureLogbackForTest() {
        try {
            ch.qos.logback.classic.LoggerContext context = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
            ch.qos.logback.classic.joran.JoranConfigurator configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(context);
            context.reset();
            java.net.URL configUrl = getClass().getClassLoader().getResource("logback.xml");
            if (configUrl != null) {
                configurator.doConfigure(configUrl);
            }
        } catch (Exception e) {
            // Fail the test if logback can't be reconfigured
            fail("Failed to reconfigure logback for test", e);
        }
    }

    @Test
    void configure_withJsonFormat_shouldSetJsonFormat() {
        // Given
        final Config config = ConfigFactory.parseString("""
            logging {
              format = "JSON"
              default-level = "WARN"
            }
            """);

        // When
        LoggingConfigurator.configure(config);

        // Then
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        final String formatProperty = context.getProperty("evochora.logging.format");
        assertEquals("STDOUT", formatProperty, "JSON format should set STDOUT property");
    }

    @Test
    void configure_withSpecificLoggerLevels_shouldSetLoggerLevels() {
        // Given
        final Config config = ConfigFactory.parseString("""
            logging {
              format = "PLAIN"
              default-level = "WARN"
              levels {
                "org.evochora.test" = "DEBUG"
                "org.evochora.datapipeline.ServiceManager" = "INFO"
              }
            }
            """);

        // When
        LoggingConfigurator.configure(config);

        // Then
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        assertEquals(Level.WARN, context.getLogger(Logger.ROOT_LOGGER_NAME).getLevel(),
            "default-level sets the level of the root logger");
        assertEquals(Level.DEBUG, context.getLogger("org.evochora.test").getLevel(),
            "A named logger overrides the default level");
        assertEquals(Level.INFO, context.getLogger("org.evochora.datapipeline.ServiceManager").getLevel(),
            "Every named logger receives its own level");
    }

    @Test
    void configure_withoutLoggingConfig_shouldUseDefaults() {
        // Given
        final Config config = ConfigFactory.parseString("""
            other {
              some-value = "test"
            }
            """);

        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.putProperty("evochora.logging.format", "UNTOUCHED");

        // When
        LoggingConfigurator.configure(config);

        // Then
        assertEquals("UNTOUCHED", context.getProperty("evochora.logging.format"),
            "Without a logging block no format is applied and Logback keeps its defaults");

        // Leave a valid appender name behind for tests that reconfigure Logback.
        context.putProperty("evochora.logging.format", "STDOUT");
    }

    @Test
    void configure_calledMultipleTimes_shouldBeIdempotent() {
        // Given
        final Config config = ConfigFactory.parseString("""
            logging {
              format = "PLAIN"
              default-level = "INFO"
            }
            """);

        // When
        LoggingConfigurator.configure(config);
        LoggingConfigurator.configure(ConfigFactory.parseString("""
            logging {
              format = "JSON"
            }
            """));

        // Then
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        assertEquals("STDOUT_PLAIN", context.getProperty("evochora.logging.format"),
            "The second call is skipped, so the format of the first one survives");
    }

    @Test
    void configure_withInvalidLevel_shouldHandleGracefully() {
        // Given
        final Config config = ConfigFactory.parseString("""
            logging {
              format = "PLAIN"
              default-level = "INVALID_LEVEL"
              levels {
                "org.evochora.test" = "ALSO_INVALID"
              }
            }
            """);

        // When & Then
        // Should not throw exceptions, should handle gracefully
        assertDoesNotThrow(() -> LoggingConfigurator.configure(config));
    }
}
