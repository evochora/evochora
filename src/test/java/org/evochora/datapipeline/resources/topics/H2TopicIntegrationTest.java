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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.evochora.junit.extensions.logging.LogWatchExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(this.topic.getAcksTable()).isEqualTo("topic_consumer_group_acks");
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
        
        // Then - Read message
        var receivedMessage = reader.receive();
        
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
}

