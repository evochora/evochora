package org.evochora.datapipeline.resources.topics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.junit.EmbeddedActiveMQExtension;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.resources.IResource.UsageState;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Integration tests for ArtemisTopicResource.
 * <p>
 * These tests use embedded ActiveMQ Artemis (in-vm) for fast, isolated testing.
 * Tests verify end-to-end functionality including broker startup, message flow, and competing consumers.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class ArtemisTopicIntegrationTest {
    
    // JUnit 5 Extension manages embedded server lifecycle automatically
    @RegisterExtension
    static EmbeddedActiveMQExtension artemisServer = new EmbeddedActiveMQExtension();
    
    private ArtemisTopicResource<BatchInfo> topic;
    
    @AfterEach
    void cleanup() throws Exception {
        if (topic != null) {
            topic.close();
        }
    }
    
    @Test
    @DisplayName("Should initialize Artemis broker and connect")
    void shouldInitializeBroker() throws Exception {
        // Given - Use In-VM broker connected to the server managed by EmbeddedActiveMQExtension
        // ID 0 is the default for the first in-vm acceptor
        Config config = ConfigFactory.parseString("""
            brokerUrl = "vm://0"
            embedded {
                enabled = false // Don't start another internal broker, use the test one
            }
        """);
        
        // When
        this.topic = new ArtemisTopicResource<>("test-topic-artemis", config);
        
        // Then
        assertThat(this.topic).isNotNull();
        assertThat(this.topic.getResourceName()).isEqualTo("test-topic-artemis");
        
        // Verify state is active
        assertThat(this.topic.getWriteUsageState()).isEqualTo(UsageState.ACTIVE);
        assertThat(this.topic.getReadUsageState()).isEqualTo(UsageState.ACTIVE);
    }
    
    @Test
    @DisplayName("Should write and read message end-to-end")
    @AllowLog(level = LogLevel.ERROR, loggerPattern = "io\\.netty\\.util\\.ResourceLeakDetector")
    void shouldWriteAndReadMessage() throws Exception {
        // Given - Setup topic
        Config config = ConfigFactory.parseString("""
            brokerUrl = "vm://0"
            embedded {
                enabled = false
            }
        """);
        this.topic = new ArtemisTopicResource<>("batch-topic-artemis", config);
        
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer-service", "writer-port", "topic-write", "batch-topic-artemis", Map.of()));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, javax.jms.Message> reader = (ITopicReader<BatchInfo, javax.jms.Message>) this.topic.getWrappedResource(
            new ResourceContext("reader-service", "reader-port", "topic-read", "batch-topic-artemis", Map.of("consumerGroup", "test-consumer-group")));
        
        String simulationRunId = "20250101-TEST-RUN";
        this.topic.setSimulationRun(simulationRunId);
        
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("SIM_20250101_TEST")
            .setStoragePath("/data/batch_001.parquet")
            .setTickStart(100)
            .setTickEnd(200)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        // When - Write message
        writer.send(message);
        
        // Then - Read message
        var receivedMessage = reader.poll(5, TimeUnit.SECONDS);
        
        assertThat(receivedMessage).isNotNull();
        assertThat(receivedMessage.payload()).isEqualTo(message);
        assertThat(receivedMessage.messageId()).isNotBlank();
        assertThat(receivedMessage.timestamp()).isPositive();
        
        // Acknowledge message
        reader.ack(receivedMessage);
    }
    
    @Test
    @DisplayName("Should support competing consumers (load balancing)")
    @AllowLog(level = LogLevel.ERROR, loggerPattern = "io\\.netty\\.util\\.ResourceLeakDetector")
    void shouldSupportCompetingConsumers() throws Exception {
        // Given
        Config config = ConfigFactory.parseString("""
            brokerUrl = "vm://0"
            embedded {
                enabled = false
            }
        """);
        this.topic = new ArtemisTopicResource<>("competing-test-topic", config);
        
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer", "writer", "topic-write", "test", Map.of()));
        
        // Two readers, SAME consumer group -> Competing Consumers
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, javax.jms.Message> reader1 = (ITopicReader<BatchInfo, javax.jms.Message>) this.topic.getWrappedResource(
            new ResourceContext("r1", "p1", "topic-read", "test", Map.of("consumerGroup", "workers")));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, javax.jms.Message> reader2 = (ITopicReader<BatchInfo, javax.jms.Message>) this.topic.getWrappedResource(
            new ResourceContext("r2", "p2", "topic-read", "test", Map.of("consumerGroup", "workers")));
            
        this.topic.setSimulationRun("RUN-COMPETING");
        
        // When - Write 10 messages
        int messageCount = 10;
        for (int i = 0; i < messageCount; i++) {
            writer.send(BatchInfo.newBuilder().setTickStart(i).build());
        }
        
        // Then - Consume all messages
        int consumed1 = 0;
        int consumed2 = 0;
        
        // We expect load balancing.
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
    void shouldSupportPubSub() throws Exception {
        // Given
        Config config = ConfigFactory.parseString("""
            brokerUrl = "vm://0"
            embedded {
                enabled = false
            }
        """);
        this.topic = new ArtemisTopicResource<>("pubsub-test-topic", config);
        
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer", "writer", "topic-write", "test", Map.of()));
        
        // Two readers, DIFFERENT consumer groups -> Pub/Sub (Broadcast)
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, javax.jms.Message> groupA = (ITopicReader<BatchInfo, javax.jms.Message>) this.topic.getWrappedResource(
            new ResourceContext("r1", "p1", "topic-read", "test", Map.of("consumerGroup", "group-A")));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, javax.jms.Message> groupB = (ITopicReader<BatchInfo, javax.jms.Message>) this.topic.getWrappedResource(
            new ResourceContext("r2", "p2", "topic-read", "test", Map.of("consumerGroup", "group-B")));
        
        this.topic.setSimulationRun("RUN-PUBSUB");
        
        // When
        writer.send(BatchInfo.newBuilder().setTickStart(1).build());
        
        // Then - BOTH groups must receive the message
        assertThat(groupA.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(groupB.poll(5, TimeUnit.SECONDS)).isNotNull();
    }
}

