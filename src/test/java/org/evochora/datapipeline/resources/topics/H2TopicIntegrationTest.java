package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Message;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.resources.IResource.UsageState;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
        Config config = ConfigFactory.parseString("jdbcUrl = \"jdbc:h2:mem:h2-topic-init\"");
        
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
        Config config = ConfigFactory.parseString("jdbcUrl = \"jdbc:h2:mem:h2-e2e\"");
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
            .setStoragePath("/data/batch_001.parquet")
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
        assertThat(receivedPayload.getStoragePath()).isEqualTo("/data/batch_001.parquet");
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
        Config config = ConfigFactory.parseString("jdbcUrl = \"jdbc:h2:mem:h2-consumer-groups\"");
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
            .setStoragePath("/data/batch_001.parquet")
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
        Config config = ConfigFactory.parseString("jdbcUrl = \"jdbc:h2:mem:h2-competing\"");
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
            .setStoragePath("/data/batch_001.parquet")
            .setTickStart(100)
            .setTickEnd(200)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        BatchInfo message2 = BatchInfo.newBuilder()
            .setSimulationRunId("20250101-COMPETING")
            .setStoragePath("/data/batch_002.parquet")
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
    void shouldReassignStuckMessages() throws Exception {
        // Given - Setup topic with SHORT claim timeout (1 second)
        Config config = ConfigFactory.parseString("jdbcUrl = \"jdbc:h2:mem:h2-stuck\"\nclaimTimeout = 1");
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
            .setStoragePath("/data/batch_001.parquet")
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
    @ExpectLog(level = LogLevel.WARN, messagePattern = "Stale ACK rejected.*")
    void shouldRejectStaleAckAfterReassignment() throws Exception {
        // Given - Setup topic with SHORT claim timeout
        Config config = ConfigFactory.parseString("jdbcUrl = \"jdbc:h2:mem:h2-stale-ack\"\nclaimTimeout = 1");
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
            .setStoragePath("/data/batch_001.parquet")
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
    
    @Test
    @DisplayName("Should receive messages via polling")
    void shouldReceiveMessagesViaPolling() throws Exception {
        // Given - Setup topic with polling-based delivery
        Config config = ConfigFactory.parseString("jdbcUrl = \"jdbc:h2:mem:h2-polling-test\"");
        this.topic = new H2TopicResource<>("polling-test-topic", config);
        this.topic.setSimulationRun("RUN-POLLING-001");
        
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer-service", "writer-port", "topic-write", "polling-test-topic", Map.of()));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> reader = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-service", "reader-port", "topic-read", "polling-test-topic", Map.of("consumerGroup", "polling-group")));
        
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("RUN-POLLING-001")
            .setStoragePath("/data/batch_001.parquet")
            .setTickStart(0)
            .setTickEnd(100)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        // When - Write message
        writer.send(message);
        
        // Then - Reader polls and eventually receives (up to 1 second delay for 500ms polling)
        TopicMessage<BatchInfo, AckToken> received = await()
            .atMost(2, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .until(() -> reader.poll(100, TimeUnit.MILLISECONDS), result -> result != null);
        
        assertThat(received).isNotNull();
        assertThat(received.payload().getSimulationRunId()).isEqualTo("RUN-POLLING-001");
        assertThat(received.payload().getStoragePath()).isEqualTo("/data/batch_001.parquet");
        
        reader.ack(received);
    }
    
    @Test
    @DisplayName("Should dynamically resolve message types from google.protobuf.Any")
    void shouldDynamicallyResolveMessageTypes() throws Exception {
        // Given - Setup topic
        Config config = ConfigFactory.parseString("jdbcUrl = \"jdbc:h2:mem:h2-types-test\"");
        this.topic = new H2TopicResource<>("types-test-topic", config);
        this.topic.setSimulationRun("RUN-TYPES-001");
        
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer-service", "writer-port", "topic-write", "types-test-topic", Map.of()));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> reader = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-service", "reader-port", "topic-read", "types-test-topic", Map.of("consumerGroup", "types-group")));
        
        BatchInfo message = BatchInfo.newBuilder()
            .setSimulationRunId("test-run-789")
            .setStoragePath("test-run-789/raw/batch_0000000000_0000000100.pb")
            .setTickStart(0)
            .setTickEnd(100)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        // When - Message written with google.protobuf.Any (internally wrapped in TopicEnvelope)
        writer.send(message);
        var received = reader.poll(5, TimeUnit.SECONDS);
        
        // Then - Type correctly resolved from type URL in google.protobuf.Any
        assertThat(received).isNotNull();
        assertThat(received.payload()).isInstanceOf(BatchInfo.class);
        assertThat(received.payload().getSimulationRunId()).isEqualTo("test-run-789");
        assertThat(received.payload().getStoragePath()).isEqualTo("test-run-789/raw/batch_0000000000_0000000100.pb");
        assertThat(received.payload().getTickStart()).isEqualTo(0L);
        assertThat(received.payload().getTickEnd()).isEqualTo(100L);
        
        reader.ack(received);
    }
    
    @Test
    @DisplayName("Should allow new consumer groups to process historical messages")
    void shouldAllowNewConsumerGroupsToProcessHistoricalMessages() throws Exception {
        // Given - Setup topic and write 3 messages
        Config config = ConfigFactory.parseString("jdbcUrl = \"jdbc:h2:mem:h2-replay-test\"");
        this.topic = new H2TopicResource<>("replay-test-topic", config);
        this.topic.setSimulationRun("RUN-REPLAY-001");
        
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
            new ResourceContext("writer-service", "writer-port", "topic-write", "replay-test-topic", Map.of()));
        
        // Write 3 messages
        for (int i = 1; i <= 3; i++) {
            writer.send(BatchInfo.newBuilder()
                .setSimulationRunId("test-run-" + i)
                .setStoragePath(String.format("test-run-%d/raw/batch_0000000000_0000000100.pb", i))
                .setTickStart(i * 100L)
                .setTickEnd((i + 1) * 100L)
                .setWrittenAtMs(System.currentTimeMillis())
                .build());
        }
        
        // Group A processes all messages
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> readerA = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-a-service", "reader-port", "topic-read", "replay-test-topic", Map.of("consumerGroup", "group-a")));
        
        for (int i = 0; i < 3; i++) {
            var msg = readerA.poll(5, TimeUnit.SECONDS);
            assertThat(msg).isNotNull();
            readerA.ack(msg);
        }
        
        // Verify group A has no more messages
        assertThat(readerA.poll(100, TimeUnit.MILLISECONDS)).isNull();
        
        // When - New consumer group B joins AFTER group A finished
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> readerB = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-b-service", "reader-port", "topic-read", "replay-test-topic", Map.of("consumerGroup", "group-b")));
        
        // Then - Group B can still process all historical messages (replay functionality)
        Set<String> processedIds = new HashSet<>();
        Set<Long> processedTicks = new HashSet<>();
        
        for (int i = 0; i < 3; i++) {
            var msg = readerB.poll(5, TimeUnit.SECONDS);
            assertThat(msg).isNotNull();
            processedIds.add(msg.payload().getSimulationRunId());
            processedTicks.add(msg.payload().getTickStart());
            readerB.ack(msg);
        }
        
        // Verify all 3 historical messages were processed by group B
        assertThat(processedIds).containsExactlyInAnyOrder("test-run-1", "test-run-2", "test-run-3");
        assertThat(processedTicks).containsExactlyInAnyOrder(100L, 200L, 300L);
        
        // Verify group B has no more messages
        assertThat(readerB.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }
    
    @Test
    @DisplayName("Should handle concurrent writes and reads from multiple threads (stress test)")
    void shouldHandleConcurrentWritesAndReads() throws Exception {
        // Given - Setup topic
        Config config = ConfigFactory.parseString("jdbcUrl = \"jdbc:h2:mem:h2-concurrent-test\"");
        this.topic = new H2TopicResource<>("concurrent-test-topic", config);
        this.topic.setSimulationRun("RUN-CONCURRENT-001");
        
        // Create 3 writer delegates (simulating parallel persistence services)
        List<ITopicWriter<BatchInfo>> writers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            @SuppressWarnings("unchecked")
            ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) this.topic.getWrappedResource(
                new ResourceContext("writer-service-" + i, "writer-port", "topic-write", "concurrent-test-topic", Map.of()));
            writers.add(writer);
        }
        
        // When - Phase 1: 3 writers each write 10 messages concurrently
        ExecutorService writeExecutor = Executors.newFixedThreadPool(3);
        CountDownLatch writeLatch = new CountDownLatch(30); // 3 writers × 10 messages
        AtomicInteger writeErrors = new AtomicInteger(0);
        
        for (int writerIdx = 0; writerIdx < 3; writerIdx++) {
            final int writerId = writerIdx;
            final ITopicWriter<BatchInfo> writer = writers.get(writerId);
            
            writeExecutor.submit(() -> {
                try {
                    for (int msgIdx = 0; msgIdx < 10; msgIdx++) {
                        BatchInfo message = BatchInfo.newBuilder()
                            .setSimulationRunId("RUN-CONCURRENT-001")
                            .setStoragePath(String.format("writer_%d/batch_%03d.parquet", writerId, msgIdx))
                            .setTickStart(writerId * 1000L + msgIdx * 100L)
                            .setTickEnd(writerId * 1000L + (msgIdx + 1) * 100L)
                            .setWrittenAtMs(System.currentTimeMillis())
                            .build();
                        
                        writer.send(message);
                        writeLatch.countDown();
                    }
                } catch (Exception e) {
                    writeErrors.incrementAndGet();
                    e.printStackTrace();
                }
            });
        }
        
        // Wait for all writes to complete
        await().atMost(10, TimeUnit.SECONDS).until(() -> writeLatch.getCount() == 0);
        writeExecutor.shutdown();
        assertThat(writeExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        
        // Verify no write errors
        assertThat(writeErrors.get()).isEqualTo(0);
        
        // Then - Phase 2: Create 2 consumer groups with 2 readers each (4 readers total)
        // Group A: 2 competing consumers
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> readerA1 = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-a1-service", "reader-port", "topic-read", "concurrent-test-topic", Map.of("consumerGroup", "group-a")));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> readerA2 = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-a2-service", "reader-port", "topic-read", "concurrent-test-topic", Map.of("consumerGroup", "group-a")));
        
        // Group B: 2 competing consumers
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> readerB1 = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-b1-service", "reader-port", "topic-read", "concurrent-test-topic", Map.of("consumerGroup", "group-b")));
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, AckToken> readerB2 = (ITopicReader<BatchInfo, AckToken>) this.topic.getWrappedResource(
            new ResourceContext("reader-b2-service", "reader-port", "topic-read", "concurrent-test-topic", Map.of("consumerGroup", "group-b")));
        
        // Phase 3: All 4 readers consume messages concurrently
        ExecutorService readExecutor = Executors.newFixedThreadPool(4);
        
        // Track messages per consumer group (ensure no duplicates within group)
        Set<String> groupAMessages = ConcurrentHashMap.newKeySet();
        Set<String> groupBMessages = ConcurrentHashMap.newKeySet();
        
        // Track total reads (should be 60: 30 for group A + 30 for group B)
        CountDownLatch readLatch = new CountDownLatch(60);
        AtomicInteger readErrors = new AtomicInteger(0);
        
        // Reader A1 (group-a)
        readExecutor.submit(() -> consumeMessages(readerA1, groupAMessages, readLatch, readErrors));
        
        // Reader A2 (group-a)
        readExecutor.submit(() -> consumeMessages(readerA2, groupAMessages, readLatch, readErrors));
        
        // Reader B1 (group-b)
        readExecutor.submit(() -> consumeMessages(readerB1, groupBMessages, readLatch, readErrors));
        
        // Reader B2 (group-b)
        readExecutor.submit(() -> consumeMessages(readerB2, groupBMessages, readLatch, readErrors));
        
        // Wait for all reads to complete (60 total: 30 + 30)
        await().atMost(15, TimeUnit.SECONDS).until(() -> readLatch.getCount() == 0);
        readExecutor.shutdownNow(); // Stop readers
        assertThat(readExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        
        // Verify no read errors
        assertThat(readErrors.get()).isEqualTo(0);
        
        // Verify pub/sub: Both groups received all 30 messages
        assertThat(groupAMessages).hasSize(30);
        assertThat(groupBMessages).hasSize(30);
        
        // Verify both groups processed the SAME 30 messages (pub/sub pattern!)
        // Union of both sets should still be 30 unique messages (they overlap completely)
        Set<String> allUniqueMessages = new HashSet<>();
        allUniqueMessages.addAll(groupAMessages);
        allUniqueMessages.addAll(groupBMessages);
        assertThat(allUniqueMessages).hasSize(30); // Same 30 messages in both groups
        
        // Verify metrics: 30 writes, 60 reads (30 per group), 60 acks
        Map<String, Number> metrics = this.topic.getMetrics();
        assertThat(metrics.get("messages_published")).isEqualTo(30L);
        assertThat(metrics.get("messages_received")).isEqualTo(60L);
        assertThat(metrics.get("messages_acknowledged")).isEqualTo(60L);
    }
    
    /**
     * Helper method: Consumer thread that reads and ACKs messages.
     * Thread-safe for concurrent execution.
     */
    private void consumeMessages(
            ITopicReader<BatchInfo, AckToken> reader,
            Set<String> processedKeys,
            CountDownLatch latch,
            AtomicInteger errors) {
        try {
            while (latch.getCount() > 0) {
                var msg = reader.poll(200, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    // Track message (detect duplicates within consumer group)
                    boolean added = processedKeys.add(msg.payload().getStoragePath());
                    if (!added) {
                        // Duplicate detected! This should NEVER happen within a consumer group
                        errors.incrementAndGet();
                        System.err.println("DUPLICATE MESSAGE DETECTED: " + msg.payload().getStoragePath());
                    }
                    
                    // Acknowledge message
                    reader.ack(msg);
                    latch.countDown();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore interrupt status
        } catch (Exception e) {
            errors.incrementAndGet();
            e.printStackTrace();
        }
    }
}

