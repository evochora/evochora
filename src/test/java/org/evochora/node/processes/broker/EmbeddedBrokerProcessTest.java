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
 * enabled=false, clean shutdown, and multi-broker support via server-IDs.
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

    void shouldStartBrokerWhenEnabled() {
        String configPath = TEST_DIR_PATH.replace("\\", "/");

        Config options = ConfigFactory.parseString("""
            enabled = true
            serverId = 0
            dataDirectory = "%s"
            persistenceEnabled = true
            journalRetention { enabled = false }
            """.formatted(configPath));

        EmbeddedBrokerProcess process = new EmbeddedBrokerProcess("test-broker", Map.of(), options);

        assertThat(EmbeddedBrokerProcess.isBrokerStarted(0)).isFalse();

        process.start();

        assertThat(EmbeddedBrokerProcess.isBrokerStarted(0)).isTrue();
        assertThat(EmbeddedBrokerProcess.getServer(0)).isNotNull();

        process.stop();

        assertThat(EmbeddedBrokerProcess.isBrokerStarted(0)).isFalse();
    }

    @Test
    @DisplayName("Should skip startup when disabled")
    void shouldSkipWhenDisabled() {
        Config options = ConfigFactory.parseString("enabled = false");

        EmbeddedBrokerProcess process = new EmbeddedBrokerProcess("test-broker-disabled", Map.of(), options);
        process.start();

        assertThat(EmbeddedBrokerProcess.isBrokerStarted(0)).isFalse();
    }

    @Test
    @DisplayName("Should handle stop when broker not started")
    void shouldHandleStopWhenNotStarted() {
        Config options = ConfigFactory.parseString("enabled = false");

        EmbeddedBrokerProcess process = new EmbeddedBrokerProcess("test-broker-noop", Map.of(), options);
        process.start();
        process.stop(); // Should not throw
    }

    @Test
    @DisplayName("Should run two brokers with different server-IDs independently")
    @AllowLog(level = LogLevel.ERROR, loggerPattern = "io\\.netty\\.util\\.ResourceLeakDetector")

    void shouldRunTwoBrokersIndependently() {
        String configPath = TEST_DIR_PATH.replace("\\", "/");

        Config topicBrokerConfig = ConfigFactory.parseString("""
            enabled = true
            serverId = 0
            dataDirectory = "%s/topic"
            persistenceEnabled = true
            journalRetention { enabled = true }
            """.formatted(configPath));

        Config queueBrokerConfig = ConfigFactory.parseString("""
            enabled = true
            serverId = 1
            dataDirectory = "%s/queue"
            persistenceEnabled = true
            journalRetention { enabled = false }
            """.formatted(configPath));

        EmbeddedBrokerProcess topicBroker = new EmbeddedBrokerProcess("topic-broker", Map.of(), topicBrokerConfig);
        EmbeddedBrokerProcess queueBroker = new EmbeddedBrokerProcess("queue-broker", Map.of(), queueBrokerConfig);

        // Start both
        topicBroker.start();
        queueBroker.start();

        // Both running independently
        assertThat(EmbeddedBrokerProcess.isBrokerStarted(0)).isTrue();
        assertThat(EmbeddedBrokerProcess.isBrokerStarted(1)).isTrue();
        assertThat(EmbeddedBrokerProcess.getServer(0)).isNotNull();
        assertThat(EmbeddedBrokerProcess.getServer(1)).isNotNull();
        assertThat(EmbeddedBrokerProcess.getServer(0)).isNotSameAs(EmbeddedBrokerProcess.getServer(1));

        // Retention only on topic broker
        assertThat(EmbeddedBrokerProcess.isJournalRetentionEnabled(0)).isTrue();
        assertThat(EmbeddedBrokerProcess.isJournalRetentionEnabled(1)).isFalse();

        // Stop queue broker, topic broker still running
        queueBroker.stop();
        assertThat(EmbeddedBrokerProcess.isBrokerStarted(0)).isTrue();
        assertThat(EmbeddedBrokerProcess.isBrokerStarted(1)).isFalse();

        topicBroker.stop();
        assertThat(EmbeddedBrokerProcess.isBrokerStarted(0)).isFalse();
    }

    @Test
    @DisplayName("Should parse InVM server-ID from broker URL")
    void shouldParseInVmServerId() {
        assertThat(EmbeddedBrokerProcess.parseInVmServerId("vm://0")).isEqualTo(0);
        assertThat(EmbeddedBrokerProcess.parseInVmServerId("vm://1")).isEqualTo(1);
        assertThat(EmbeddedBrokerProcess.parseInVmServerId("vm://42")).isEqualTo(42);
        assertThat(EmbeddedBrokerProcess.parseInVmServerId("tcp://localhost:61616")).isEqualTo(-1);
        assertThat(EmbeddedBrokerProcess.parseInVmServerId(null)).isEqualTo(-1);
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
