package org.evochora.datapipeline.resources.queues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.util.Comparator;
import java.nio.file.Path;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IResource.UsageState;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.queues.StreamingBatch;
import org.evochora.datapipeline.resources.broker.EmbeddedBrokerRegistry;
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
 * Tests verify end-to-end functionality including receiveBatch round-trips,
 * token-based drain lock, competing consumers, backpressure,
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
    static void setupBroker() throws IOException {
        testDir = Files.createTempDirectory("artemis-queue-test-").toFile();
        String testDirPath = testDir.getAbsolutePath();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                deleteDirectory(testDir);
            } catch (IOException e) {
                System.err.println("Could not remove " + testDir + ": " + e.getMessage());
            }
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
        EmbeddedBrokerRegistry.ensureStarted(brokerConfig);

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
        try {
            EmbeddedBrokerRegistry.resetForTesting();
        } finally {
            deleteDirectory(testDir);
        }
    }

    // =========================================================================
    // Basic Round-Trip Tests
    // =========================================================================

    @Test
    @DisplayName("Should put and receive a single message")
    void shouldPutAndReceive() throws Exception {
        queue = new ArtemisQueueResource<>("test-put-poll", baseConfig);

        BatchInfo msg = BatchInfo.newBuilder().setTickStart(100).setTickEnd(200).build();
        queue.put(msg);

        try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(1, 5, TimeUnit.SECONDS)) {
            assertThat(batch.size()).isEqualTo(1);
            BatchInfo received = batch.iterator().next();
            assertThat(received.getTickStart()).isEqualTo(100);
            assertThat(received.getTickEnd()).isEqualTo(200);
            batch.commit();
        }
    }

    @Test
    @DisplayName("Should put and receive a single message with blocking wait")
    void shouldPutAndReceiveBlocking() throws Exception {
        queue = new ArtemisQueueResource<>("test-put-take", baseConfig);

        BatchInfo msg = BatchInfo.newBuilder().setTickStart(300).build();
        queue.put(msg);

        try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(1, 5, TimeUnit.SECONDS)) {
            assertThat(batch.size()).isEqualTo(1);
            assertThat(batch.iterator().next().getTickStart()).isEqualTo(300);
            batch.commit();
        }
    }

    @Test
    @DisplayName("Should return empty batch when queue is empty")
    void shouldReturnEmptyOnEmptyReceive() throws Exception {
        queue = new ArtemisQueueResource<>("test-empty-poll", baseConfig);

        try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(1, 0, TimeUnit.MILLISECONDS)) {
            assertThat(batch.size()).isZero();
        }
    }

    @Test
    @DisplayName("Should respect receive timeout on empty queue")
    void shouldRespectReceiveTimeout() throws Exception {
        queue = new ArtemisQueueResource<>("test-poll-timeout", baseConfig);

        long start = System.currentTimeMillis();
        try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(1, 200, TimeUnit.MILLISECONDS)) {
            long elapsed = System.currentTimeMillis() - start;
            assertThat(batch.size()).isZero();
            assertThat(elapsed).isGreaterThanOrEqualTo(150); // Allow some slack
        }
    }

    @Test
    @DisplayName("Should put and receive multiple messages preserving order")
    void shouldPreserveOrder() throws Exception {
        queue = new ArtemisQueueResource<>("test-order", baseConfig);

        for (int i = 0; i < 5; i++) {
            queue.put(BatchInfo.newBuilder().setTickStart(i * 1000).build());
        }

        for (int i = 0; i < 5; i++) {
            try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(1, 5, TimeUnit.SECONDS)) {
                assertThat(batch.size()).isEqualTo(1);
                assertThat(batch.iterator().next().getTickStart()).isEqualTo(i * 1000);
                batch.commit();
            }
        }
    }

    // =========================================================================
    // Batch Receive Tests
    // =========================================================================

    @Test
    @DisplayName("Should receive batch non-blocking")
    void shouldReceiveBatchNonBlocking() throws Exception {
        queue = new ArtemisQueueResource<>("test-drain-nb", baseConfig);

        for (int i = 0; i < 5; i++) {
            queue.put(BatchInfo.newBuilder().setTickStart(i).build());
        }

        try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(10, 0, TimeUnit.MILLISECONDS)) {
            assertThat(batch.size()).isEqualTo(5);
            List<BatchInfo> items = new ArrayList<>();
            batch.iterator().forEachRemaining(items::add);
            for (int i = 0; i < 5; i++) {
                assertThat(items.get(i).getTickStart()).isEqualTo(i);
            }
            batch.commit();
        }
    }

    @Test
    @DisplayName("Should receive batch with timeout and token lock")
    void shouldReceiveBatchWithTimeout() throws Exception {
        queue = new ArtemisQueueResource<>("test-drain-timeout", baseConfig);

        for (int i = 0; i < 3; i++) {
            queue.put(BatchInfo.newBuilder().setTickStart(i).build());
        }

        try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(10, 1, TimeUnit.SECONDS)) {
            assertThat(batch.size()).isEqualTo(3);
            batch.commit();
        }
    }

    @Test
    @DisplayName("Should return empty batch after timeout on empty queue")
    void shouldReturnEmptyBatchOnEmptyTimeout() throws Exception {
        queue = new ArtemisQueueResource<>("test-drain-empty", baseConfig);

        long start = System.currentTimeMillis();
        try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(10, 200, TimeUnit.MILLISECONDS)) {
            long elapsed = System.currentTimeMillis() - start;
            assertThat(batch.size()).isZero();
            assertThat(elapsed).isGreaterThanOrEqualTo(150);
        }
    }

    // =========================================================================
    // Sequential Two-Step Receive Test
    // =========================================================================

    @Test
    @DisplayName("Should receive all messages in two sequential batch calls")
    void shouldReceiveAllInTwoSteps() throws Exception {
        queue = new ArtemisQueueResource<>("test-two-step", baseConfig);

        for (int i = 0; i < 10; i++) {
            queue.put(BatchInfo.newBuilder().setTickStart(i).build());
        }

        int totalCount = 0;

        // First receive: get 5
        try (StreamingBatch<BatchInfo> batch1 = queue.receiveBatch(5, 1, TimeUnit.SECONDS)) {
            assertThat(batch1.size()).isEqualTo(5);
            totalCount += batch1.size();
            batch1.commit();
        }

        // Second receive: get remaining 5
        try (StreamingBatch<BatchInfo> batch2 = queue.receiveBatch(5, 1, TimeUnit.SECONDS)) {
            assertThat(batch2.size()).isEqualTo(5);
            totalCount += batch2.size();
            batch2.commit();
        }

        assertThat(totalCount).isEqualTo(10);
    }

    // =========================================================================
    // Competing Consumers — Token Lock Guarantee
    // =========================================================================

    @Test
    @DisplayName("Competing consumers should receive concurrently while processing, with consecutive ranges")
    void shouldGuaranteeNonOverlappingRanges() throws Exception {
        queue = new ArtemisQueueResource<>("test-competing", baseConfig);

        // Use 10 small messages — well within the 100 KB byte limit.
        int totalMessages = 10;
        for (int i = 0; i < totalMessages; i++) {
            queue.put(BatchInfo.newBuilder().setTickStart(i).build());
        }

        // Each consumer receives a batch, then simulates processing time.
        // During processing (outside drainLock), the other consumer can enter receiveBatch.
        // Only wide enough for the overlap to be unmistakable: the two consumers start within a
        // few milliseconds of each other, so this leaves room for a machine an order of magnitude
        // slower before the windows could come apart.
        long processingTimeMs = 200;
        long receiveTimeoutMs = 100; // Short timeout so empty-queue exits quickly

        List<List<Long>> allBatches = new ArrayList<>();
        // Start and end of each simulated processing. Two of these overlapping is what "the other
        // consumer can receive while this one works" means, stated directly instead of inferred
        // from how long everything took.
        List<long[]> processingWindows = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);
        // submit() says nothing about when a task actually starts. Were the second consumer to
        // begin only after the first had finished a batch, the first would take both and the
        // windows could not overlap — the test would report serialisation that never happened.
        // Both wait here until the other has arrived, so they enter receiveBatch together.
        CyclicBarrier bothReady = new CyclicBarrier(2);

        Runnable consumer = () -> {
            try {
                bothReady.await(10, TimeUnit.SECONDS);
                while (true) {
                    try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(5, receiveTimeoutMs, TimeUnit.MILLISECONDS)) {
                        if (batch.size() > 0) {
                            List<Long> batchTicks = new ArrayList<>();
                            for (BatchInfo msg : batch) {
                                batchTicks.add(msg.getTickStart());
                            }
                            synchronized (allBatches) {
                                allBatches.add(batchTicks);
                            }
                            batch.commit();
                            // Simulate processing time — the OTHER consumer should be able
                            // to receive the next batch during this sleep.
                            long processingStart = System.nanoTime();
                            Thread.sleep(processingTimeMs);
                            synchronized (processingWindows) {
                                processingWindows.add(
                                    new long[] { processingStart, System.nanoTime() });
                            }
                        } else {
                            break;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // A broken or timed-out barrier leaves this consumer without work to do; the
                // latch below then reports that not both of them finished.
                throw new IllegalStateException("Consumer failed before or during receive", e);
            } finally {
                done.countDown();
            }
        };

        // nanoTime throughout: these are durations, and a clock correction during the run
        // would otherwise be able to turn an interval negative or make windows that lie one after
        // another look as if they overlapped.
        long wallStart = System.nanoTime();
        // The futures are kept because submit() files an exception in them instead of raising it.
        // countDown() runs in a finally block, so a consumer that died still counts as finished,
        // and every assertion below would go on to describe a run that never happened.
        Future<?> first = executor.submit(consumer);
        Future<?> second = executor.submit(consumer);

        boolean bothFinished = done.await(10, TimeUnit.SECONDS);
        long wallElapsedMs = (System.nanoTime() - wallStart) / 1_000_000;
        // shutdown() alone would only refuse new work and leave a stuck consumer running into
        // @AfterEach, which closes the queue underneath it. The JMS receive turns an interrupt
        // into InterruptedException, so shutdownNow() gets them out of it, and waiting for
        // termination keeps cleanup behind them.
        executor.shutdownNow();
        boolean consumersStopped = executor.awaitTermination(10, TimeUnit.SECONDS);

        for (Future<?> consumerResult : List.of(first, second)) {
            try {
                consumerResult.get();
            } catch (ExecutionException e) {
                throw new AssertionError("A consumer failed; the measurements below describe a run "
                    + "that did not happen as intended", e.getCause());
            }
        }

        // Checked before the wall clock is read as evidence: if a consumer never left its loop,
        // the elapsed time is the timeout above and says nothing about whether the two ran in
        // parallel. Reporting that as a parallelism failure would name the wrong cause.
        assertThat(bothFinished)
            .describedAs("Both consumers should finish within 10s")
            .isTrue();
        assertThat(consumersStopped)
            .describedAs("Consumers should have stopped before the queue is closed")
            .isTrue();

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

        // Verify: processing happened concurrently, not sequentially. Two windows that overlap
        // show it directly; a wall-clock bound would have to sit between the parallel and the
        // sequential total and would move with the speed of the machine.
        List<long[]> windows;
        synchronized (processingWindows) {
            windows = new ArrayList<>(processingWindows);
        }
        // receiveBatch returns up to maxSize, stopping as soon as no message is immediately
        // available, so ten messages need not split into exactly two batches. Any two windows
        // that overlap show the property; how many there are does not matter.
        assertThat(windows)
            .describedAs("Concurrency needs at least two processing windows to compare")
            .hasSizeGreaterThanOrEqualTo(2);
        long widestOverlapNanos = Long.MIN_VALUE;
        for (int i = 0; i < windows.size(); i++) {
            for (int j = i + 1; j < windows.size(); j++) {
                widestOverlapNanos = Math.max(widestOverlapNanos,
                    Math.min(windows.get(i)[1], windows.get(j)[1])
                        - Math.max(windows.get(i)[0], windows.get(j)[0]));
            }
        }
        assertThat(widestOverlapNanos)
            .describedAs("Two processing windows should overlap, proving one consumer works while "
                + "the other receives; none of the %d windows did (closest pair %dms apart), so "
                + "processing was serialised. Elapsed time was %dms.",
                windows.size(), -widestOverlapNanos / 1_000_000, wallElapsedMs)
            .isPositive();
    }

    // =========================================================================
    // Offer Tests
    // =========================================================================

    @Test
    @DisplayName("Should offer and receive successfully")
    void shouldOfferAndReceive() throws Exception {
        queue = new ArtemisQueueResource<>("test-offer", baseConfig);

        boolean offered = queue.offer(BatchInfo.newBuilder().setTickStart(42).build());
        assertThat(offered).isTrue();

        try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(1, 5, TimeUnit.SECONDS)) {
            assertThat(batch.size()).isEqualTo(1);
            assertThat(batch.iterator().next().getTickStart()).isEqualTo(42);
            batch.commit();
        }
    }

    @Test
    @DisplayName("Should offerAll and receive all")
    void shouldOfferAllAndReceiveAll() throws Exception {
        queue = new ArtemisQueueResource<>("test-offer-all", baseConfig);

        List<BatchInfo> messages = List.of(
            BatchInfo.newBuilder().setTickStart(1).build(),
            BatchInfo.newBuilder().setTickStart(2).build(),
            BatchInfo.newBuilder().setTickStart(3).build()
        );

        int offered = queue.offerAll(messages);
        assertThat(offered).isEqualTo(3);

        try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(10, 5, TimeUnit.SECONDS)) {
            assertThat(batch.size()).isEqualTo(3);
            batch.commit();
        }
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
    @AllowLog(level = LogLevel.WARN, loggerPattern = "org\\.apache\\.activemq\\.artemis.*")
    void shouldBlockPutWhenFull() throws Exception {
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

        // Receive one item to free space
        try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(1, 0, TimeUnit.MILLISECONDS)) {
            assertThat(batch.size()).isGreaterThan(0);
            batch.commit();
        }

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

        try (StreamingBatch<BatchInfo> batch = consumer.receiveBatch(1, 5, TimeUnit.SECONDS)) {
            assertThat(batch.size()).isEqualTo(1);
            assertThat(batch.iterator().next().getTickStart()).isEqualTo(555);
            batch.commit();
        }
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
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(queue.getUsageState("queue-in")).isEqualTo(UsageState.ACTIVE);
            assertThat(queue.getUsageState("queue-out")).isEqualTo(UsageState.ACTIVE);
        });
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
    @DisplayName("Should putAll and receive all")
    void shouldPutAllAndReceiveAll2() throws Exception {
        queue = new ArtemisQueueResource<>("test-putall", baseConfig);

        List<BatchInfo> messages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            messages.add(BatchInfo.newBuilder().setTickStart(i * 100).build());
        }

        queue.putAll(messages);

        try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(10, 5, TimeUnit.SECONDS)) {
            assertThat(batch.size()).isEqualTo(5);
            List<BatchInfo> items = new ArrayList<>();
            batch.iterator().forEachRemaining(items::add);
            assertThat(items.get(0).getTickStart()).isEqualTo(0);
            assertThat(items.get(4).getTickStart()).isEqualTo(400);
            batch.commit();
        }
    }

    // =========================================================================
    // Large Message Tests (>100KB triggers Artemis large message handling)
    // =========================================================================

    @Test
    @DisplayName("Should put and receive a large message (>100KB) via streaming batch")
    void shouldPutAndReceiveLargeMessage() throws Exception {
        Config largeConfig = ConfigFactory.parseString("maxSizeBytes = 200000000")
            .withFallback(baseConfig);
        queue = new ArtemisQueueResource<>("test-large-msg", largeConfig);

        // Create a message well above 100KB (Artemis default minLargeMessageSize)
        // Use 10MB to ensure large message handling is triggered
        String largePath = "x".repeat(10_000_000); // 10MB string
        BatchInfo msg = BatchInfo.newBuilder()
            .setTickStart(42)
            .setTickEnd(84)
            .setStoragePath(largePath)
            .build();

        queue.put(msg);

        try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(1, 5, TimeUnit.SECONDS)) {
            assertThat(batch.size()).isEqualTo(1);
            BatchInfo received = batch.iterator().next();
            assertThat(received.getTickStart()).isEqualTo(42);
            assertThat(received.getTickEnd()).isEqualTo(84);
            assertThat(received.getStoragePath()).hasSize(10_000_000);
            batch.commit();
        }
    }

    // =========================================================================
    // Startup Purge Tests
    // =========================================================================

    @Test
    @DisplayName("Should purge stale messages from previous run on startup")
    void shouldPurgeStaleMessagesOnStartup() throws Exception {
        String queueName = "test-purge-startup";

        // Phase 1: Create queue and send a message, then close (message persists in journal)
        var firstQueue = new ArtemisQueueResource<BatchInfo>(queueName, baseConfig);
        firstQueue.put(BatchInfo.newBuilder().setTickStart(999).build());
        firstQueue.close();

        // Phase 2: Recreate — stale message should be purged automatically
        queue = new ArtemisQueueResource<>(queueName, baseConfig);

        // Verify queue is empty (stale message was purged)
        try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(1, 0, TimeUnit.MILLISECONDS)) {
            assertThat(batch.size()).isZero();
        }

        // Verify normal operation: new messages work fine
        queue.put(BatchInfo.newBuilder().setTickStart(1000).build());
        try (StreamingBatch<BatchInfo> batch = queue.receiveBatch(1, 5, TimeUnit.SECONDS)) {
            assertThat(batch.size()).isEqualTo(1);
            assertThat(batch.iterator().next().getTickStart()).isEqualTo(1000);
            batch.commit();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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
