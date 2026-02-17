package org.evochora.datapipeline.resources.topics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.resources.IResource.UsageState;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.evochora.node.processes.broker.EmbeddedBrokerProcess;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Integration tests for ArtemisTopicResource.
 * <p>
 * These tests use the ArtemisTopicResource's singleton embedded broker (in-vm)
 * for fast, isolated testing. Tests verify end-to-end functionality including
 * broker startup, message flow, competing consumers, and journal retention replay.
 * <p>
 * <b>Note:</b> All tests share the same singleton broker instance with journal
 * retention enabled. This mirrors production behavior.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class ArtemisTopicIntegrationTest {

    private static File testDir;
    private static Config sharedConfig;

    private ArtemisTopicResource<BatchInfo> topic;

    @BeforeAll
    static void setupBroker() {
        // Create shared test directory and config for the singleton broker
        String testDirPath = System.getProperty("java.io.tmpdir") + "/artemis-integration-test";
        testDir = new File(testDirPath);

        // Register shutdown hook to clean up after ALL tests complete.
        // This ensures no artifacts remain after JVM termination.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            deleteDirectory(testDir);
        }, "artemis-test-cleanup"));

        // Replace backslashes with forward slashes for Windows compatibility
        // (HOCON interprets backslashes as escape characters)
        String configPath = testDirPath.replace("\\", "/");

        // Start topic broker (serverId=0) — matches production config
        Config brokerConfig = ConfigFactory.parseString("""
            enabled = true
            serverId = 0
            dataDirectory = "%s"
            persistenceEnabled = true
            journalRetention {
                enabled = true
                directory = "%s/history"
            }
            """.formatted(configPath, configPath));
        EmbeddedBrokerProcess.ensureStarted(brokerConfig);

        // Resource config: only topic-specific settings, no broker config
        sharedConfig = ConfigFactory.parseString("""
            brokerUrl = "vm://0"
            """);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (topic != null) {
            topic.close();
        }
    }

    @AfterAll
    static void teardownBroker() throws Exception {
        EmbeddedBrokerProcess.resetForTesting();
    }

    @Test
    @DisplayName("Should initialize Artemis broker and connect")
    @AllowLog(level = LogLevel.ERROR, loggerPattern = "io\\.netty\\.util\\.ResourceLeakDetector")
    @AllowLog(level = LogLevel.WARN, loggerPattern = "org\\.apache\\.activemq\\.artemis.*")
    void shouldInitializeBroker() throws Exception {
        // When
        this.topic = new ArtemisTopicResource<>("test-topic-artemis", sharedConfig);

        // Then
        assertThat(this.topic).isNotNull();
        assertThat(this.topic.getResourceName()).isEqualTo("test-topic-artemis");
        assertThat(this.topic.getWriteUsageState()).isEqualTo(UsageState.ACTIVE);
        assertThat(this.topic.getReadUsageState()).isEqualTo(UsageState.ACTIVE);
    }

    @Test
    @DisplayName("Should write and read message end-to-end")
    @AllowLog(level = LogLevel.ERROR, loggerPattern = "io\\.netty\\.util\\.ResourceLeakDetector")
    @AllowLog(level = LogLevel.WARN, loggerPattern = "org\\.apache\\.activemq\\.artemis.*")
    void shouldWriteAndReadMessage() throws Exception {
        // Given
        this.topic = new ArtemisTopicResource<>("batch-topic-artemis", sharedConfig);

        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer-service", "writer-port", "topic-write", "batch-topic-artemis", Map.of()));

        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, jakarta.jms.Message> reader = (ITopicReader<BatchInfo, jakarta.jms.Message>) this.topic.getWrappedResource(
            new ResourceContext("reader-service", "reader-port", "topic-read", "batch-topic-artemis", Map.of("consumerGroup", "test-consumer-group")));

        this.topic.setSimulationRun("20250101-TEST-RUN");

        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("SIM_20250101_TEST")
            .setStoragePath("/data/batch_001.parquet")
            .setTickStart(100)
            .setTickEnd(200)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();

        // When
        writer.send(message);

        // Then
        TopicMessage<BatchInfo, jakarta.jms.Message> receivedMessage = reader.poll(5, TimeUnit.SECONDS);

        assertThat(receivedMessage).isNotNull();
        assertThat(receivedMessage.payload()).isEqualTo(message);
        assertThat(receivedMessage.messageId()).isNotBlank();
        assertThat(receivedMessage.timestamp()).isPositive();

        reader.ack(receivedMessage);
    }

    @Test
    @DisplayName("Should support competing consumers (load balancing)")
    @AllowLog(level = LogLevel.ERROR, loggerPattern = "io\\.netty\\.util\\.ResourceLeakDetector")
    @AllowLog(level = LogLevel.WARN, loggerPattern = "org\\.apache\\.activemq\\.artemis.*")
    void shouldSupportCompetingConsumers() throws Exception {
        // Given
        this.topic = new ArtemisTopicResource<>("competing-test-topic", sharedConfig);

        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer", "writer", "topic-write", "test", Map.of()));

        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, jakarta.jms.Message> reader1 = (ITopicReader<BatchInfo, jakarta.jms.Message>) this.topic.getWrappedResource(
            new ResourceContext("r1", "p1", "topic-read", "test", Map.of("consumerGroup", "workers")));

        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, jakarta.jms.Message> reader2 = (ITopicReader<BatchInfo, jakarta.jms.Message>) this.topic.getWrappedResource(
            new ResourceContext("r2", "p2", "topic-read", "test", Map.of("consumerGroup", "workers")));

        this.topic.setSimulationRun("RUN-COMPETING");

        // When - Write 10 messages
        int messageCount = 10;
        for (int i = 0; i < messageCount; i++) {
            writer.send(BatchInfo.newBuilder().setTickStart(i).build());
        }

        // Then - Both consumers should receive messages (load balancing)
        int consumed1 = 0;
        int consumed2 = 0;

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && (consumed1 + consumed2) < messageCount) {
            if (reader1.poll(10, TimeUnit.MILLISECONDS) != null) consumed1++;
            if (reader2.poll(10, TimeUnit.MILLISECONDS) != null) consumed2++;
        }

        assertThat(consumed1 + consumed2).isEqualTo(messageCount);
    }

    @Test
    @DisplayName("Should support pub/sub (multiple consumer groups)")
    @AllowLog(level = LogLevel.ERROR, loggerPattern = "io\\.netty\\.util\\.ResourceLeakDetector")
    @AllowLog(level = LogLevel.WARN, loggerPattern = "org\\.apache\\.activemq\\.artemis.*")
    void shouldSupportPubSub() throws Exception {
        // Given
        this.topic = new ArtemisTopicResource<>("pubsub-test-topic", sharedConfig);

        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer", "writer", "topic-write", "test", Map.of()));

        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, jakarta.jms.Message> groupA = (ITopicReader<BatchInfo, jakarta.jms.Message>) this.topic.getWrappedResource(
            new ResourceContext("r1", "p1", "topic-read", "test", Map.of("consumerGroup", "group-A")));

        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, jakarta.jms.Message> groupB = (ITopicReader<BatchInfo, jakarta.jms.Message>) this.topic.getWrappedResource(
            new ResourceContext("r2", "p2", "topic-read", "test", Map.of("consumerGroup", "group-B")));

        this.topic.setSimulationRun("RUN-PUBSUB");

        // When
        writer.send(BatchInfo.newBuilder().setTickStart(1).build());

        // Then - BOTH groups must receive the message
        assertThat(groupA.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(groupB.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    @DisplayName("New consumer group should receive all historical messages via journal retention replay")
    @AllowLog(level = LogLevel.WARN, loggerPattern = "org\\.apache\\.activemq\\.artemis.*")
    @AllowLog(level = LogLevel.ERROR, loggerPattern = "io\\.netty\\.util\\.ResourceLeakDetector")
    void shouldReplayHistoricalMessagesForNewConsumerGroup() throws Exception {
        // Given
        this.topic = new ArtemisTopicResource<>("retention-test", sharedConfig);

        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer", "w", "topic-write", "retention-test", Map.of()));

        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, jakarta.jms.Message> groupA =
            (ITopicReader<BatchInfo, jakarta.jms.Message>) this.topic.getWrappedResource(
                new ResourceContext("indexer-a", "a", "topic-read", "retention-test",
                    Map.of("consumerGroup", "group-A")));

        this.topic.setSimulationRun("TEST-RUN-RETENTION");

        // Write 3 messages
        for (int i = 1; i <= 3; i++) {
            BatchInfo msg = BatchInfo.newBuilder()
                .setSimulationRunId("TEST-RUN-RETENTION")
                .setStoragePath("/batch/" + i + ".pb.zst")
                .setTickStart(i * 1000L)
                .setTickEnd(i * 1000L + 999)
                .setWrittenAtMs(System.currentTimeMillis())
                .build();
            writer.send(msg);
        }

        // Group A reads and ACKs all 3 messages
        Set<Long> processedByA = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            TopicMessage<BatchInfo, jakarta.jms.Message> msg = groupA.poll(5, TimeUnit.SECONDS);
            assertThat(msg).describedAs("Group A should receive message %d", i + 1).isNotNull();
            processedByA.add(msg.payload().getTickStart());
            groupA.ack(msg);
        }

        assertThat(processedByA).containsExactlyInAnyOrder(1000L, 2000L, 3000L);
        assertThat(groupA.poll(500, TimeUnit.MILLISECONDS))
            .describedAs("Group A should have no more messages").isNull();

        // NEW consumer group B joins AFTER Group A finished
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, jakarta.jms.Message> groupB =
            (ITopicReader<BatchInfo, jakarta.jms.Message>) this.topic.getWrappedResource(
                new ResourceContext("indexer-b", "b", "topic-read", "retention-test",
                    Map.of("consumerGroup", "group-B")));

        // Group B should receive ALL 3 historical messages via replay
        Set<Long> processedByB = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            TopicMessage<BatchInfo, jakarta.jms.Message> msg = groupB.poll(5, TimeUnit.SECONDS);
            assertThat(msg)
                .describedAs("Group B should receive historical message %d via replay", i + 1)
                .isNotNull();
            processedByB.add(msg.payload().getTickStart());
            groupB.ack(msg);
        }

        assertThat(processedByB)
            .describedAs("Group B should have received all 3 historical messages")
            .containsExactlyInAnyOrder(1000L, 2000L, 3000L);

        assertThat(groupB.poll(500, TimeUnit.MILLISECONDS))
            .describedAs("Group B should have no more messages").isNull();

        // Group A should still have no messages (not affected by B's replay)
        assertThat(groupA.poll(500, TimeUnit.MILLISECONDS))
            .describedAs("Group A should still have no messages").isNull();
    }

    /**
     * Helper to recursively delete a directory.
     */
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
