package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Message;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.resources.IResource.UsageState;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for H2TopicResource.
 * <p>
 * These tests use in-memory H2 database for fast, isolated testing without filesystem artifacts.
 * Tests verify end-to-end functionality including database operations, triggers, and message flow.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class H2TopicIntegrationTest {
    
    private H2TopicResource<BatchInfo> topic;
    
    @AfterEach
    void cleanup() throws Exception {
        if (topic != null) {
            topic.close();
        }
    }
    
    @Test
    @DisplayName("Should initialize H2 database and create centralized tables")
    void shouldInitializeDatabase() throws Exception {
        // Given - Use in-memory H2 (no filesystem artifacts!)
        Config config = ConfigFactory.parseString("dbPath = \"mem:h2-topic-init\"");
        
        // When
        this.topic = new H2TopicResource<>("test-topic", config);
        
        // Then
        assertThat(this.topic).isNotNull();
        assertThat(this.topic.getResourceName()).isEqualTo("test-topic");
        assertThat(this.topic.getMessagesTable()).isEqualTo("topic_messages");
        assertThat(this.topic.getConsumerGroupTable()).isEqualTo("topic_consumer_group");
        assertThat(this.topic.getClaimTimeoutSeconds()).isEqualTo(300);  // Default
        
        // Verify HikariCP pool is active
        assertThat(this.topic.getWriteUsageState()).isEqualTo(UsageState.ACTIVE);
        assertThat(this.topic.getReadUsageState()).isEqualTo(UsageState.ACTIVE);
    }
    
    @Test
    @DisplayName("Should write and read message end-to-end")
    void shouldWriteAndReadMessage() throws Exception {
        // Given - Setup topic
        Config config = ConfigFactory.parseString("dbPath = \"mem:h2-e2e\"");
        this.topic = new H2TopicResource<>("batch-topic", config);
        
        // Create writer and reader FIRST (before setSimulationRun)
        // Use getWrappedResource() (proper API) instead of createWriterDelegate() (template method)
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer-service", "writer-port", "topic-write", "batch-topic", Map.of()));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> reader = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-service", "reader-port", "topic-read", "batch-topic", Map.of("consumerGroup", "test-consumer-group")));
        
        // Set simulation run (creates schema + tables, propagates to delegates)
        String simulationRunId = "20250101-TEST-RUN";
        this.topic.setSimulationRun(simulationRunId);
        
        // Create test message
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("SIM_20250101_TEST")
            .setStorageKey("/data/batch_001.parquet")
            .setTickStart(100)
            .setTickEnd(200)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        // When - Write message
        writer.send(message);
        
        // Then - Read message (with timeout to prevent hanging)
        var receivedMessage = reader.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        
        assertThat(receivedMessage).isNotNull();
        assertThat(receivedMessage.payload()).isEqualTo(message);
        assertThat(receivedMessage.messageId()).isNotBlank();
        assertThat(receivedMessage.timestamp()).isPositive();
        
        // Verify message content
        BatchInfo receivedPayload = receivedMessage.payload();
        assertThat(receivedPayload.getSimulationRunId()).isEqualTo("SIM_20250101_TEST");
        assertThat(receivedPayload.getStorageKey()).isEqualTo("/data/batch_001.parquet");
        assertThat(receivedPayload.getTickStart()).isEqualTo(100);
        assertThat(receivedPayload.getTickEnd()).isEqualTo(200);
        assertThat(receivedPayload.getWrittenAtMs()).isPositive();
        
        // Acknowledge message
        reader.ack(receivedMessage);
        
        // Verify metrics
        assertThat(this.topic.getMetrics()).containsKeys(
            "messages_published", "messages_received", "messages_acknowledged");
        assertThat(this.topic.getMetrics().get("messages_published")).isEqualTo(1L);
        assertThat(this.topic.getMetrics().get("messages_received")).isEqualTo(1L);
        assertThat(this.topic.getMetrics().get("messages_acknowledged")).isEqualTo(1L);
    }
    
    @Test
    @DisplayName("Should support multiple consumer groups (pub/sub pattern)")
    void shouldSupportMultipleConsumerGroups() throws Exception {
        // Given - Setup topic with two consumer groups
        Config config = ConfigFactory.parseString("dbPath = \"mem:h2-consumer-groups\"");
        this.topic = new H2TopicResource<>("test-topic", config);
        
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer-service", "writer-port", "topic-write", "test-topic", Map.of()));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> readerGroupA = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-a", "reader-port-a", "topic-read", "test-topic", Map.of("consumerGroup", "group-a")));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> readerGroupB = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-b", "reader-port-b", "topic-read", "test-topic", Map.of("consumerGroup", "group-b")));
        
        this.topic.setSimulationRun("20250101-CONSUMER-GROUPS");
        
        // When - Write one message
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("20250101-CONSUMER-GROUPS")
            .setStorageKey("/data/batch_001.parquet")
            .setTickStart(100)
            .setTickEnd(200)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        writer.send(message);
        
        // Then - BOTH consumer groups should receive the same message
        var messageGroupA = readerGroupA.receive();
        var messageGroupB = readerGroupB.receive();
        
        assertThat(messageGroupA).isNotNull();
        assertThat(messageGroupB).isNotNull();
        assertThat(messageGroupA.payload()).isEqualTo(message);
        assertThat(messageGroupB.payload()).isEqualTo(message);
        assertThat(messageGroupA.messageId()).isEqualTo(messageGroupB.messageId());
        
        // ACK from both groups
        readerGroupA.ack(messageGroupA);
        readerGroupB.ack(messageGroupB);
        
        // Verify metrics
        assertThat(this.topic.getMetrics().get("messages_published")).isEqualTo(1L);
        assertThat(this.topic.getMetrics().get("messages_received")).isEqualTo(2L);  // 2 reads (one per group)
        assertThat(this.topic.getMetrics().get("messages_acknowledged")).isEqualTo(2L);  // 2 acks
    }
    
    @Test
    @DisplayName("Should handle competing consumers (load balancing within consumer group)")
    void shouldHandleCompetingConsumers() throws Exception {
        // Given - Setup topic with two readers in SAME consumer group
        Config config = ConfigFactory.parseString("dbPath = \"mem:h2-competing\"");
        this.topic = new H2TopicResource<>("test-topic", config);
        
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer-service", "writer-port", "topic-write", "test-topic", Map.of()));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> reader1 = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-1", "reader-port-1", "topic-read", "test-topic", Map.of("consumerGroup", "workers")));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> reader2 = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-2", "reader-port-2", "topic-read", "test-topic", Map.of("consumerGroup", "workers")));
        
        this.topic.setSimulationRun("20250101-COMPETING");
        
        // When - Write TWO messages
        BatchInfo message1 = BatchInfo.newBuilder()
            .setSimulationRunId("20250101-COMPETING")
            .setStorageKey("/data/batch_001.parquet")
            .setTickStart(100)
            .setTickEnd(200)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        BatchInfo message2 = BatchInfo.newBuilder()
            .setSimulationRunId("20250101-COMPETING")
            .setStorageKey("/data/batch_002.parquet")
            .setTickStart(201)
            .setTickEnd(300)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        writer.send(message1);
        writer.send(message2);
        
        // Then - Each reader should get ONE message (load balanced with FOR UPDATE SKIP LOCKED)
        var receivedMsg1 = reader1.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        var receivedMsg2 = reader2.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        
        assertThat(receivedMsg1).isNotNull();
        assertThat(receivedMsg2).isNotNull();
        
        // Messages should be DIFFERENT (load balanced)
        assertThat(receivedMsg1.messageId()).isNotEqualTo(receivedMsg2.messageId());
        
        // ACK both
        reader1.ack(receivedMsg1);
        reader2.ack(receivedMsg2);
        
        // Verify metrics
        assertThat(this.topic.getMetrics().get("messages_published")).isEqualTo(2L);
        assertThat(this.topic.getMetrics().get("messages_received")).isEqualTo(2L);
        assertThat(this.topic.getMetrics().get("messages_acknowledged")).isEqualTo(2L);
    }
    
    @Test
    @DisplayName("Should reassign stuck messages after claim timeout")
    @ExpectLog(level = LogLevel.WARN, messagePattern = "Reassigned stuck message.*")
    void shouldReassignStuckMessages() throws Exception {
        // Given - Setup topic with SHORT claim timeout (1 second)
        Config config = ConfigFactory.parseString("dbPath = \"mem:h2-stuck\"\nclaimTimeout = 1");
        this.topic = new H2TopicResource<>("test-topic", config);
        
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer-service", "writer-port", "topic-write", "test-topic", Map.of()));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> reader1 = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-1", "reader-port-1", "topic-read", "test-topic", Map.of("consumerGroup", "workers")));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> reader2 = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-2", "reader-port-2", "topic-read", "test-topic", Map.of("consumerGroup", "workers")));
        
        this.topic.setSimulationRun("20250101-STUCK");
        
        // When - Write message, reader1 claims it but does NOT ack
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("20250101-STUCK")
            .setStorageKey("/data/batch_001.parquet")
            .setTickStart(100)
            .setTickEnd(200)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        writer.send(message);
        
        var claimedMessage = reader1.poll(5, TimeUnit.SECONDS);
        assertThat(claimedMessage).isNotNull();
        
        // Do NOT ack - let it timeout
        // Use Awaitility to wait for claim timeout (1 second) + small buffer
        await().pollDelay(1500, TimeUnit.MILLISECONDS).until(() -> true);
        
        // Then - reader2 should get the SAME message (reassigned)
        var reassignedMessage = reader2.poll(5, TimeUnit.SECONDS);
        
        assertThat(reassignedMessage).isNotNull();
        assertThat(reassignedMessage.messageId()).isEqualTo(claimedMessage.messageId());
        
        // Verify stuck message counter
        assertThat(this.topic.getMetrics().get("stuck_messages_reassigned")).isEqualTo(1L);
        
        // Cleanup
        reader2.ack(reassignedMessage);
    }
    
    @Test
    @DisplayName("Should reject stale ACK after message reassignment")
    @ExpectLog(level = LogLevel.WARN, messagePattern = "Reassigned stuck message.*")
    @ExpectLog(level = LogLevel.WARN, messagePattern = "Stale ACK rejected.*")
    void shouldRejectStaleAckAfterReassignment() throws Exception {
        // Given - Setup topic with SHORT claim timeout
        Config config = ConfigFactory.parseString("dbPath = \"mem:h2-stale-ack\"\nclaimTimeout = 1");
        this.topic = new H2TopicResource<>("test-topic", config);
        
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer-service", "writer-port", "topic-write", "test-topic", Map.of()));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> reader1 = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-1", "reader-port-1", "topic-read", "test-topic", Map.of("consumerGroup", "workers")));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> reader2 = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-2", "reader-port-2", "topic-read", "test-topic", Map.of("consumerGroup", "workers")));
        
        this.topic.setSimulationRun("20250101-STALE-ACK");
        
        // When - Write message, reader1 claims it but delays ACK
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("20250101-STALE-ACK")
            .setStorageKey("/data/batch_001.parquet")
            .setTickStart(100)
            .setTickEnd(200)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        writer.send(message);
        
        var claimedByReader1 = reader1.poll(5, TimeUnit.SECONDS);
        assertThat(claimedByReader1).isNotNull();
        
        // Wait for timeout, then reader2 claims it
        // Use Awaitility to wait for claim timeout (1 second) + small buffer
        await().pollDelay(1500, TimeUnit.MILLISECONDS).until(() -> true);
        var reassignedToReader2 = reader2.poll(5, TimeUnit.SECONDS);
        assertThat(reassignedToReader2).isNotNull();
        assertThat(reassignedToReader2.messageId()).isEqualTo(claimedByReader1.messageId());
        
        // reader2 ACKs successfully
        reader2.ack(reassignedToReader2);
        
        // Then - reader1 tries to ACK with old claim version (should be rejected silently)
        reader1.ack(claimedByReader1);  // Stale ACK - should be rejected
        
        // Verify stale ACK counter (this is a delegate-level metric)
        if (reader1 instanceof H2TopicReaderDelegate delegate) {
            assertThat(delegate.getMetrics().get("delegate_stale_acks_rejected")).isEqualTo(1L);
        }
    }
}

