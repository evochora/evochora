package org.evochora.node.processes.broker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Comparator;
import java.nio.file.Path;
import java.io.IOException;
import java.nio.file.Files;
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

import org.evochora.datapipeline.resources.broker.EmbeddedBrokerRegistry;

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

    private File testDir;

    @BeforeEach
    void ensureCleanState() throws Exception {
        EmbeddedBrokerRegistry.resetForTesting();
        testDir = Files.createTempDirectory("artemis-process-test-").toFile();
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            EmbeddedBrokerRegistry.resetForTesting();
        } finally {
            deleteDirectory(testDir);
        }
    }

    @Test
    @DisplayName("Should start embedded broker when enabled")
    @AllowLog(level = LogLevel.ERROR, loggerPattern = "io\\.netty\\.util\\.ResourceLeakDetector")

    void shouldStartBrokerWhenEnabled() {
        String configPath = testDir.getAbsolutePath().replace("\\", "/");

        Config options = ConfigFactory.parseString("""
            enabled = true
            serverId = 0
            dataDirectory = "%s"
            persistenceEnabled = true
            journalRetention { enabled = false }
            """.formatted(configPath));

        EmbeddedBrokerProcess process = new EmbeddedBrokerProcess("test-broker", Map.of(), options);

        assertThat(EmbeddedBrokerRegistry.isBrokerStarted(0)).isFalse();

        process.start();

        assertThat(EmbeddedBrokerRegistry.isBrokerStarted(0)).isTrue();
        assertThat(EmbeddedBrokerRegistry.getServer(0)).isNotNull();

        process.stop();

        assertThat(EmbeddedBrokerRegistry.isBrokerStarted(0)).isFalse();
    }

    @Test
    @DisplayName("Should skip startup when disabled")
    void shouldSkipWhenDisabled() {
        Config options = ConfigFactory.parseString("enabled = false");

        EmbeddedBrokerProcess process = new EmbeddedBrokerProcess("test-broker-disabled", Map.of(), options);
        process.start();

        assertThat(EmbeddedBrokerRegistry.isBrokerStarted(0)).isFalse();
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
        String configPath = testDir.getAbsolutePath().replace("\\", "/");

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
        assertThat(EmbeddedBrokerRegistry.isBrokerStarted(0)).isTrue();
        assertThat(EmbeddedBrokerRegistry.isBrokerStarted(1)).isTrue();
        assertThat(EmbeddedBrokerRegistry.getServer(0)).isNotNull();
        assertThat(EmbeddedBrokerRegistry.getServer(1)).isNotNull();
        assertThat(EmbeddedBrokerRegistry.getServer(0)).isNotSameAs(EmbeddedBrokerRegistry.getServer(1));

        // Retention only on topic broker
        assertThat(EmbeddedBrokerRegistry.isJournalRetentionEnabled(0)).isTrue();
        assertThat(EmbeddedBrokerRegistry.isJournalRetentionEnabled(1)).isFalse();

        // Stop queue broker, topic broker still running
        queueBroker.stop();
        assertThat(EmbeddedBrokerRegistry.isBrokerStarted(0)).isTrue();
        assertThat(EmbeddedBrokerRegistry.isBrokerStarted(1)).isFalse();

        topicBroker.stop();
        assertThat(EmbeddedBrokerRegistry.isBrokerStarted(0)).isFalse();
    }

    /** Removes the directory with everything in it; a path that cannot be deleted fails the caller. */
    private static void deleteDirectory(File dir) throws IOException {
        if (dir == null || !dir.exists()) {
            return;
        }
        try (var paths = Files.walk(dir.toPath())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
