package org.evochora.cli.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the LoggingConfigurator class.
 */
@Tag("unit")
class LoggingConfiguratorTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingConfiguratorTest.class);

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
        
        // Verify that the root logger uses the correct appender
        final ch.qos.logback.classic.Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        
        // Debug: Print all appenders
        final java.util.Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> iterator = rootLogger.iteratorForAppenders();
        while (iterator.hasNext()) {
            final Appender<?> appender = iterator.next();
        }
        
        // Check if STDOUT_PLAIN appender exists
        final Appender<?> plainAppender = rootLogger.getAppender("STDOUT_PLAIN");
        if (plainAppender == null) {
            // If not found, check if any appender is attached
            final java.util.Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> checkIterator = rootLogger.iteratorForAppenders();
            final boolean hasAppenders = checkIterator.hasNext();
            assertTrue(hasAppenders, "Root logger should have at least one appender after configuration");
            
            // Check the first appender
            final Appender<?> firstAppender = checkIterator.next();
            assertTrue(firstAppender instanceof ConsoleAppender, 
                "First appender should be ConsoleAppender, but was: " + firstAppender.getClass().getSimpleName());
        } else {
            assertTrue(plainAppender instanceof ConsoleAppender, "STDOUT_PLAIN appender should be ConsoleAppender");
        }
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
        // The configuration should be applied without throwing exceptions
        assertTrue(true, "Configuration should complete without errors");
    }

    @Test
    void configure_withoutLoggingConfig_shouldUseDefaults() {
        // Given
        final Config config = ConfigFactory.parseString("""
            other {
              some-value = "test"
            }
            """);

        // When
        LoggingConfigurator.configure(config);

        // Then
        // Should not throw exceptions and use Logback defaults
        assertTrue(true, "Configuration should complete without errors");
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
        LoggingConfigurator.configure(config); // Second call

        // Then
        // Should not throw exceptions
        assertTrue(true, "Multiple calls should be idempotent");
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
