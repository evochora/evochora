package org.evochora.datapipeline.resources.queues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IResource.UsageState;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
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

import java.util.Map;

/**
 * Integration tests for ArtemisQueueResource.
 * <p>
 * Tests verify end-to-end functionality including put/poll/take round-trips,
 * drainTo with token-based drain lock, competing consumers, backpressure,
 * adaptive coalescing, and memory estimation.
 * <p>
 * <b>Note:</b> All tests share the singleton embedded broker instance.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.ERROR, loggerPattern = "io\\.netty\\.util\\.ResourceLeakDetector")
class ArtemisQueueResourceTest {

    private static File testDir;
    private static Config baseConfig;

    private ArtemisQueueResource<BatchInfo> queue;

    @BeforeAll
    static void setupBroker() {
        String testDirPath = System.getProperty("java.io.tmpdir") + "/artemis-queue-test";
        testDir = new File(testDirPath);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            deleteDirectory(testDir);
        }, "artemis-queue-test-cleanup"));

        String configPath = testDirPath.replace("\\", "/");

        // Start queue broker (serverId=1) — matches production config
        Config brokerConfig = ConfigFactory.parseString("""
            enabled = true
            serverId = 1
            dataDirectory = "%s"
            persistenceEnabled = true
            journalRetention {
                enabled = false
            }
            """.formatted(configPath));
        EmbeddedBrokerProcess.ensureStarted(brokerConfig);

        // Resource config: only queue-specific settings, no broker config
        baseConfig = ConfigFactory.parseString("""
            brokerUrl = "vm://1"
            maxSizeBytes = 100000
            coalescingDelayMs = 0
            """);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (queue != null) {
            queue.close();
            queue = null;
        }
    }

    @AfterAll
    static void teardownBroker() throws Exception {
        EmbeddedBrokerProcess.resetForTesting();
        if (testDir != null) {
            deleteDirectory(testDir);
        }
    }

    // =========================================================================
    // Basic Round-Trip Tests
    // =========================================================================

    @Test
    @DisplayName("Should put and poll a single message")
    void shouldPutAndPoll() throws Exception {
        queue = new ArtemisQueueResource<>("test-put-poll", baseConfig);

        BatchInfo msg = BatchInfo.newBuilder().setTickStart(100).setTickEnd(200).build();
        queue.put(msg);

        Optional<BatchInfo> received = queue.poll();
        assertThat(received).isPresent();
        assertThat(received.get().getTickStart()).isEqualTo(100);
        assertThat(received.get().getTickEnd()).isEqualTo(200);
    }

    @Test
    @DisplayName("Should put and take a single message")

    void shouldPutAndTake() throws Exception {
        queue = new ArtemisQueueResource<>("test-put-take", baseConfig);

        BatchInfo msg = BatchInfo.newBuilder().setTickStart(300).build();
        queue.put(msg);

        BatchInfo received = queue.take();
        assertThat(received.getTickStart()).isEqualTo(300);
    }

    @Test
    @DisplayName("Should return empty on poll when queue is empty")

    void shouldReturnEmptyOnEmptyPoll() throws Exception {
        queue = new ArtemisQueueResource<>("test-empty-poll", baseConfig);

        Optional<BatchInfo> received = queue.poll();
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("Should respect poll timeout on empty queue")

    void shouldRespectPollTimeout() throws Exception {
        queue = new ArtemisQueueResource<>("test-poll-timeout", baseConfig);

        long start = System.currentTimeMillis();
        Optional<BatchInfo> received = queue.poll(200, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(received).isEmpty();
        assertThat(elapsed).isGreaterThanOrEqualTo(150); // Allow some slack
    }

    @Test
    @DisplayName("Should put and poll multiple messages preserving order")

    void shouldPreserveOrder() throws Exception {
        queue = new ArtemisQueueResource<>("test-order", baseConfig);

        for (int i = 0; i < 5; i++) {
            queue.put(BatchInfo.newBuilder().setTickStart(i * 1000).build());
        }

        for (int i = 0; i < 5; i++) {
            Optional<BatchInfo> received = queue.poll();
            assertThat(received).isPresent();
            assertThat(received.get().getTickStart()).isEqualTo(i * 1000);
        }
    }

    // =========================================================================
    // DrainTo Tests
    // =========================================================================

    @Test
    @DisplayName("Should drainTo non-blocking")

    void shouldDrainToNonBlocking() throws Exception {
        queue = new ArtemisQueueResource<>("test-drain-nb", baseConfig);

        for (int i = 0; i < 5; i++) {
            queue.put(BatchInfo.newBuilder().setTickStart(i).build());
        }

        List<BatchInfo> drained = new ArrayList<>();
        int count = queue.drainTo(drained, 10);

        assertThat(count).isEqualTo(5);
        assertThat(drained).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(drained.get(i).getTickStart()).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("Should drainTo with timeout and token lock")

    void shouldDrainToWithTimeout() throws Exception {
        queue = new ArtemisQueueResource<>("test-drain-timeout", baseConfig);

        for (int i = 0; i < 3; i++) {
            queue.put(BatchInfo.newBuilder().setTickStart(i).build());
        }

        List<BatchInfo> drained = new ArrayList<>();
        int count = queue.drainTo(drained, 10, 1, TimeUnit.SECONDS);

        assertThat(count).isEqualTo(3);
        assertThat(drained).hasSize(3);
    }

    @Test
    @DisplayName("Should drainTo return 0 on empty queue after timeout")

    void shouldDrainToReturnZeroOnEmptyTimeout() throws Exception {
        queue = new ArtemisQueueResource<>("test-drain-empty", baseConfig);

        List<BatchInfo> drained = new ArrayList<>();
        long start = System.currentTimeMillis();
        int count = queue.drainTo(drained, 10, 200, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(count).isZero();
        assertThat(drained).isEmpty();
        assertThat(elapsed).isGreaterThanOrEqualTo(150);
    }

    // =========================================================================
    // Sequential Two-Step Drain Test
    // =========================================================================

    @Test
    @DisplayName("Should drain all messages in two sequential drain calls")

    void shouldDrainAllInTwoSteps() throws Exception {
        queue = new ArtemisQueueResource<>("test-two-step", baseConfig);

        for (int i = 0; i < 10; i++) {
            queue.put(BatchInfo.newBuilder().setTickStart(i).build());
        }

        // First drain: get 5
        List<BatchInfo> batch1 = new ArrayList<>();
        int count1 = queue.drainTo(batch1, 5, 1, TimeUnit.SECONDS);

        // Second drain: get remaining 5
        List<BatchInfo> batch2 = new ArrayList<>();
        int count2 = queue.drainTo(batch2, 5, 1, TimeUnit.SECONDS);

        assertThat(count1).isEqualTo(5);
        assertThat(count2).isEqualTo(5);
        assertThat(batch1.size() + batch2.size()).isEqualTo(10);
    }

    // =========================================================================
    // Competing Consumers — Token Lock Guarantee
    // =========================================================================

    @Test
    @DisplayName("Competing consumers should drain concurrently while processing, with consecutive ranges")

    void shouldGuaranteeNonOverlappingRanges() throws Exception {
        queue = new ArtemisQueueResource<>("test-competing", baseConfig);

        // Use 10 small messages — well within the 100 KB byte limit.
        int totalMessages = 10;
        for (int i = 0; i < totalMessages; i++) {
            queue.put(BatchInfo.newBuilder().setTickStart(i).build());
        }

        // Each consumer drains a batch, then simulates 500ms processing time.
        // During processing (outside drainLock), the other consumer can enter drainTo.
        //
        // Timeline (parallel):
        //   ~0ms: A drains 5, B blocked on drainLock
        //   ~50ms: A releases drainLock, starts 500ms processing. B drains 5.
        //   ~100ms: B releases drainLock, starts 500ms processing.
        //   ~550ms: A finishes processing, enters drainTo (empty queue, 100ms timeout)
        //   ~600ms: B finishes processing, blocked on drainLock
        //   ~650ms: A's drainTo times out, releases drainLock, exits. B enters drainTo.
        //   ~750ms: B's drainTo times out, exits.
        // Total parallel: ~750ms
        //
        // If sequential (drainLock held during processing):
        //   A drain + process + timeout + B drain + process + timeout = ~1300ms
        long processingTimeMs = 500;
        long drainTimeoutMs = 100; // Short timeout so empty-queue exits quickly

        List<List<Long>> allBatches = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);

        Runnable consumer = () -> {
            try {
                while (true) {
                    List<BatchInfo> batch = new ArrayList<>();
                    int count = queue.drainTo(batch, 5, drainTimeoutMs, TimeUnit.MILLISECONDS);
                    if (count > 0) {
                        List<Long> batchTicks = new ArrayList<>();
                        for (BatchInfo msg : batch) {
                            batchTicks.add(msg.getTickStart());
                        }
                        synchronized (allBatches) {
                            allBatches.add(batchTicks);
                        }
                        // Simulate processing time — the OTHER consumer should be able
                        // to drain the next batch during this sleep.
                        Thread.sleep(processingTimeMs);
                    } else {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };

        long wallStart = System.currentTimeMillis();
        executor.submit(consumer);
        executor.submit(consumer);

        done.await(10, TimeUnit.SECONDS);
        long wallElapsed = System.currentTimeMillis() - wallStart;
        executor.shutdown();

        // Verify: all messages received
        Set<Long> allReceived = new java.util.HashSet<>();
        synchronized (allBatches) {
            for (List<Long> batch : allBatches) {
                allReceived.addAll(batch);
            }
        }
        assertThat(allReceived).hasSize(totalMessages);

        // Verify: each batch is consecutive (monotonically increasing)
        synchronized (allBatches) {
            for (List<Long> batch : allBatches) {
                for (int i = 1; i < batch.size(); i++) {
                    assertThat(batch.get(i))
                        .describedAs("Batch elements should be consecutive")
                        .isEqualTo(batch.get(i - 1) + 1);
                }
            }
        }

        // Verify: processing happened concurrently, not sequentially.
        // Parallel: ~500ms processing + overhead ≈ 700-1000ms (varies by CI environment)
        // Sequential: 2 × (500ms processing + drain + timeout) ≈ 1400ms+
        // Threshold set conservatively for slow CI environments (e.g. Windows GitHub Actions).
        long sequentialMinimum = 2 * (processingTimeMs + drainTimeoutMs); // 1200ms
        assertThat(wallElapsed)
            .describedAs("Wall clock (%dms) should be well below sequential minimum (%dms), "
                + "proving parallel processing", wallElapsed, sequentialMinimum)
            .isLessThan(sequentialMinimum);
    }

    // =========================================================================
    // Offer Tests
    // =========================================================================

    @Test
    @DisplayName("Should offer and poll successfully")

    void shouldOfferAndPoll() throws Exception {
        queue = new ArtemisQueueResource<>("test-offer", baseConfig);

        boolean offered = queue.offer(BatchInfo.newBuilder().setTickStart(42).build());
        assertThat(offered).isTrue();

        Optional<BatchInfo> received = queue.poll();
        assertThat(received).isPresent();
        assertThat(received.get().getTickStart()).isEqualTo(42);
    }

    @Test
    @DisplayName("Should offerAll and drain successfully")

    void shouldOfferAllAndDrain() throws Exception {
        queue = new ArtemisQueueResource<>("test-offer-all", baseConfig);

        List<BatchInfo> messages = List.of(
            BatchInfo.newBuilder().setTickStart(1).build(),
            BatchInfo.newBuilder().setTickStart(2).build(),
            BatchInfo.newBuilder().setTickStart(3).build()
        );

        int offered = queue.offerAll(messages);
        assertThat(offered).isEqualTo(3);

        List<BatchInfo> drained = new ArrayList<>();
        int count = queue.drainTo(drained, 10);
        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("Should return false from offer when byte limit is reached")

    void shouldOfferReturnFalseWhenFull() throws Exception {
        Config smallConfig = ConfigFactory.parseString("maxSizeBytes = 1500")
            .withFallback(baseConfig);
        queue = new ArtemisQueueResource<>("test-offer-full", smallConfig);

        // Fill the queue until the byte limit is reached
        int accepted = 0;
        while (queue.offer(BatchInfo.newBuilder().setTickStart(accepted).build())) {
            accepted++;
        }

        assertThat(accepted).describedAs("At least one message should fit").isGreaterThan(0);

        // Next offer should return false immediately (non-blocking)
        long start = System.currentTimeMillis();
        boolean result = queue.offer(BatchInfo.newBuilder().setTickStart(99).build());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isFalse();
        assertThat(elapsed).isLessThan(100); // Should be near-instant
    }

    // =========================================================================
    // Backpressure Test
    // =========================================================================

    @Test
    @DisplayName("Should block put when byte limit is reached")
    // Filling the queue to capacity triggers an Artemis-internal WARN about address-full.
    // The message arrives with null format via SLF4J 2.0's fluent logging path, so no
    // messagePattern can be matched. See: https://jira.qos.ch/browse/LOGBACK-1737
    @AllowLog(level = LogLevel.WARN, loggerPattern = "org\\.apache\\.activemq\\.artemis.*")
    void shouldBlockPutWhenFull() throws Exception {
        // Small byte limit + minimal producerWindowSize ensures per-message credit checks.
        // With producerWindowSize=1, the producer must request credits from the broker for
        // every byte, so the broker can enforce BLOCK even for tiny test messages.
        Config smallConfig = ConfigFactory.parseString("""
            maxSizeBytes = 1500
            producerWindowSize = 1
            """).withFallback(baseConfig);
        queue = new ArtemisQueueResource<>("test-backpressure", smallConfig);

        // Fill the queue until the byte limit is reached (using offer to avoid blocking)
        while (queue.offer(BatchInfo.newBuilder().setTickStart(0).build())) {
            // keep filling
        }

        // Next put should block — verify it blocks for at least 200ms
        AtomicBoolean putCompleted = new AtomicBoolean(false);
        Thread putThread = new Thread(() -> {
            try {
                queue.put(BatchInfo.newBuilder().setTickStart(99).build());
                putCompleted.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        putThread.start();

        // Verify put doesn't complete within 200ms (pollDelay defers first check)
        await().pollDelay(200, TimeUnit.MILLISECONDS)
               .atMost(500, TimeUnit.MILLISECONDS)
               .until(() -> !putCompleted.get());

        // Drain one item to free space
        queue.poll();

        // Now put should complete
        await().atMost(5, TimeUnit.SECONDS).untilTrue(putCompleted);
        putThread.join(5000);
    }

    // =========================================================================
    // Wrapper Tests
    // =========================================================================

    @Test
    @DisplayName("Should provide monitored queue consumer and producer wrappers")

    void shouldProvideWrappedResources() throws Exception {
        queue = new ArtemisQueueResource<>("test-wrappers", baseConfig);

        @SuppressWarnings("unchecked")
        IOutputQueueResource<BatchInfo> producer = (IOutputQueueResource<BatchInfo>) queue.getWrappedResource(
            new ResourceContext("engine", "port", "queue-out", "test-wrappers", Map.of()));

        @SuppressWarnings("unchecked")
        IInputQueueResource<BatchInfo> consumer = (IInputQueueResource<BatchInfo>) queue.getWrappedResource(
            new ResourceContext("persistence", "port", "queue-in", "test-wrappers", Map.of()));

        producer.put(BatchInfo.newBuilder().setTickStart(555).build());

        BatchInfo received = consumer.take();
        assertThat(received.getTickStart()).isEqualTo(555);
    }

    @Test
    @DisplayName("Should return WAITING on empty queue for input, ACTIVE for output")

    void shouldReturnCorrectUsageState() throws Exception {
        queue = new ArtemisQueueResource<>("test-usage", baseConfig);

        // Empty queue: input waits for data, output has space
        assertThat(queue.getUsageState("queue-in")).isEqualTo(UsageState.WAITING);
        assertThat(queue.getUsageState("queue-out")).isEqualTo(UsageState.ACTIVE);
        assertThat(queue.getUsageState("queue-in-direct")).isEqualTo(UsageState.WAITING);
        assertThat(queue.getUsageState("queue-out-direct")).isEqualTo(UsageState.ACTIVE);

        // Add a message: input is active, output still has space
        queue.put(BatchInfo.newBuilder().setTickStart(1).build());
        assertThat(queue.getUsageState("queue-in")).isEqualTo(UsageState.ACTIVE);
        assertThat(queue.getUsageState("queue-out")).isEqualTo(UsageState.ACTIVE);
    }

    @Test
    @DisplayName("Should return WAITING for output when byte limit is reached")

    void shouldReturnWaitingWhenFull() throws Exception {
        Config smallConfig = ConfigFactory.parseString("maxSizeBytes = 1500")
            .withFallback(baseConfig);
        queue = new ArtemisQueueResource<>("test-usage-full", smallConfig);

        // Fill queue until byte limit is reached
        while (queue.offer(BatchInfo.newBuilder().setTickStart(0).build())) {
            // keep filling
        }

        assertThat(queue.getUsageState("queue-out")).isEqualTo(UsageState.WAITING);
        assertThat(queue.getUsageState("queue-in")).isEqualTo(UsageState.ACTIVE);
    }

    // =========================================================================
    // Memory Estimation Tests
    // =========================================================================

    @Test
    @DisplayName("Should estimate minimal heap usage for off-heap queue")

    void shouldEstimateMinimalHeap() throws Exception {
        queue = new ArtemisQueueResource<>("test-memory", baseConfig);

        SimulationParameters params = SimulationParameters.of(new int[]{800, 600}, 1000);
        List<MemoryEstimate> estimates = queue.estimateWorstCaseMemory(params);

        assertThat(estimates).hasSize(1);
        MemoryEstimate estimate = estimates.get(0);
        assertThat(estimate.componentName()).isEqualTo("test-memory");
        assertThat(estimate.category()).isEqualTo(MemoryEstimate.Category.QUEUE);

        // Off-heap queue should estimate much less than an in-memory queue holding 10 chunks
        long inMemoryEstimate = 10L * params.estimateBytesPerChunk();
        assertThat(estimate.estimatedBytes()).isLessThan(inMemoryEstimate);

        // Should be roughly 2 × chunkSize + 64KB sessions
        long expectedApprox = 2 * params.estimateBytesPerChunk() + 64 * 1024;
        assertThat(estimate.estimatedBytes()).isEqualTo(expectedApprox);
    }

    // =========================================================================
    // PutAll Test
    // =========================================================================

    @Test
    @DisplayName("Should putAll and drain all")

    void shouldPutAllAndDrain() throws Exception {
        queue = new ArtemisQueueResource<>("test-putall", baseConfig);

        List<BatchInfo> messages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            messages.add(BatchInfo.newBuilder().setTickStart(i * 100).build());
        }

        queue.putAll(messages);

        List<BatchInfo> drained = new ArrayList<>();
        int count = queue.drainTo(drained, 10);
        assertThat(count).isEqualTo(5);
        assertThat(drained.get(0).getTickStart()).isEqualTo(0);
        assertThat(drained.get(4).getTickStart()).isEqualTo(400);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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
