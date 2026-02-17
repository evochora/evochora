package org.evochora.node.processes.broker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Map;

import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Integration tests for {@link EmbeddedBrokerProcess}.
 * <p>
 * Verifies broker lifecycle management: start with enabled=true, skip with
 * enabled=false, and clean shutdown.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class EmbeddedBrokerProcessTest {

    private static final String TEST_DIR_PATH = System.getProperty("java.io.tmpdir") + "/artemis-process-test";

    @BeforeEach
    void ensureCleanState() throws Exception {
        EmbeddedBrokerProcess.resetForTesting();
    }

    @AfterEach
    void cleanup() throws Exception {
        EmbeddedBrokerProcess.resetForTesting();
        deleteDirectory(new File(TEST_DIR_PATH));
    }

    @Test
    @DisplayName("Should start embedded broker when enabled")
    @AllowLog(level = LogLevel.ERROR, loggerPattern = "io\\.netty\\.util\\.ResourceLeakDetector")
    @AllowLog(level = LogLevel.WARN, loggerPattern = "org\\.apache\\.activemq\\.artemis.*")
    void shouldStartBrokerWhenEnabled() {
        String configPath = TEST_DIR_PATH.replace("\\", "/");

        Config options = ConfigFactory.parseString("""
            enabled = true
            dataDirectory = "%s"
            persistenceEnabled = true
            journalRetention { enabled = false }
            """.formatted(configPath));

        EmbeddedBrokerProcess process = new EmbeddedBrokerProcess("test-broker", Map.of(), options);

        assertThat(EmbeddedBrokerProcess.isBrokerStarted()).isFalse();

        process.start();

        assertThat(EmbeddedBrokerProcess.isBrokerStarted()).isTrue();
        assertThat(EmbeddedBrokerProcess.getServer()).isNotNull();

        process.stop();

        assertThat(EmbeddedBrokerProcess.isBrokerStarted()).isFalse();
    }

    @Test
    @DisplayName("Should skip startup when disabled")
    void shouldSkipWhenDisabled() {
        Config options = ConfigFactory.parseString("enabled = false");

        EmbeddedBrokerProcess process = new EmbeddedBrokerProcess("test-broker-disabled", Map.of(), options);
        process.start();

        assertThat(EmbeddedBrokerProcess.isBrokerStarted()).isFalse();
    }

    @Test
    @DisplayName("Should handle stop when broker not started")
    void shouldHandleStopWhenNotStarted() {
        Config options = ConfigFactory.parseString("enabled = false");

        EmbeddedBrokerProcess process = new EmbeddedBrokerProcess("test-broker-noop", Map.of(), options);
        process.start();
        process.stop(); // Should not throw
    }

    private static void deleteDirectory(File dir) {
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            dir.delete();
        }
    }
}
