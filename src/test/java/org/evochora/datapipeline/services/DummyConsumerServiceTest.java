package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.SystemContracts.DummyMessage;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IDeadLetterQueueResource;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.resources.idempotency.InMemoryIdempotencyTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class DummyConsumerServiceTest {

    private IInputQueueResource<DummyMessage> mockInputQueue;
    private IDeadLetterQueueResource<DummyMessage> mockDLQ;
    private IIdempotencyTracker<Integer> idempotencyTracker;
    private Map<String, List<IResource>> resources;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        mockInputQueue = mock(IInputQueueResource.class);
        mockDLQ = mock(IDeadLetterQueueResource.class);
        when(mockDLQ.offer(any())).thenReturn(true); // DLQ accepts messages

        // Use real idempotency tracker for behavior testing
        idempotencyTracker = new InMemoryIdempotencyTracker<>(Duration.ofHours(1));

        resources = new HashMap<>();
        resources.put("input", Collections.singletonList(mockInputQueue));
        resources.put("dlq", Collections.singletonList(mockDLQ)); // Add DLQ to avoid warning
        resources.put("idempotencyTracker", Collections.singletonList(idempotencyTracker));
    }

    @Test
    void testConfiguration() {
        Config config = ConfigFactory.parseString("processingDelayMs=100, logReceivedMessages=true, maxMessages=50");
        DummyConsumerService service = new DummyConsumerService("test-consumer", config, resources);
        assertNotNull(service);
    }

    @Test
    void testMessageReceiving() throws InterruptedException {
        Config config = ConfigFactory.parseString("maxMessages=2");
        DummyConsumerService service = new DummyConsumerService("test-consumer", config, resources);

        // Use unique IDs for idempotency tracking
        when(mockInputQueue.take())
                .thenReturn(DummyMessage.newBuilder().setId(1).setContent("Msg1").build())
                .thenReturn(DummyMessage.newBuilder().setId(2).setContent("Msg2").build());

        service.start();

        long deadline = System.currentTimeMillis() + 1000;
        while (service.getCurrentState() != IService.State.STOPPED && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(IService.State.STOPPED, service.getCurrentState());

        verify(mockInputQueue, times(2)).take();
        Map<String, Number> metrics = service.getMetrics();
        assertEquals(2L, metrics.get("messages_received").longValue());
        assertEquals(0L, metrics.get("messages_duplicate").longValue()); // No duplicates
    }

    @Test
    void testLifecycle() throws InterruptedException {
        Config config = ConfigFactory.parseString("maxMessages=-1");
        DummyConsumerService service = new DummyConsumerService("test-consumer", config, resources);

        // Make the mock block in a way that's interruptible
        doAnswer(invocation -> {
            Thread.sleep(2000); // Block long enough for the test to run
            return null;
        }).when(mockInputQueue).take();

        assertEquals(IService.State.STOPPED, service.getCurrentState());
        service.start();
        // Give the service thread time to start and enter the run loop
        Thread.sleep(100);
        assertEquals(IService.State.RUNNING, service.getCurrentState());
        service.pause();
        assertEquals(IService.State.PAUSED, service.getCurrentState());
        service.resume();
        assertEquals(IService.State.RUNNING, service.getCurrentState());
        service.stop(); // This will interrupt the Thread.sleep in the mock
        Thread.sleep(100);
        assertEquals(IService.State.STOPPED, service.getCurrentState());
    }

    @Test
    void testMetrics() throws InterruptedException {
        Config config = ConfigFactory.parseString("maxMessages=3");
        DummyConsumerService service = new DummyConsumerService("test-consumer", config, resources);

        // Use unique IDs to avoid idempotency filtering
        when(mockInputQueue.take())
                .thenReturn(DummyMessage.newBuilder().setId(10).build())
                .thenReturn(DummyMessage.newBuilder().setId(11).build())
                .thenReturn(DummyMessage.newBuilder().setId(12).build());

        service.start();

        long deadline = System.currentTimeMillis() + 1000;
        while (service.getCurrentState() != IService.State.STOPPED && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(IService.State.STOPPED, service.getCurrentState());

        Map<String, Number> metrics = service.getMetrics();
        assertEquals(3L, metrics.get("messages_received").longValue());
        assertTrue(metrics.get("throughput_per_sec").doubleValue() >= 0);
        // Verify new metrics exist
        assertNotNull(metrics.get("messages_duplicate"));
        assertNotNull(metrics.get("idempotency_tracker_size"));
    }

    @Test
    void testMaxMessages() throws InterruptedException {
        Config config = ConfigFactory.parseString("maxMessages=2");
        DummyConsumerService service = new DummyConsumerService("test-consumer", config, resources);
        // Use unique IDs so messages aren't filtered as duplicates
        when(mockInputQueue.take())
                .thenReturn(DummyMessage.newBuilder().setId(20).build())
                .thenReturn(DummyMessage.newBuilder().setId(21).build());
        service.start();
        Thread.sleep(100); // Let service run and stop itself
        verify(mockInputQueue, times(2)).take();
        assertEquals(IService.State.STOPPED, service.getCurrentState());
    }

    @Test
    void testIdempotency_duplicatesAreFiltered() throws InterruptedException {
        Config config = ConfigFactory.parseString("maxMessages=3");
        DummyConsumerService service = new DummyConsumerService("test-consumer", config, resources);

        // Send same ID twice - second one should be filtered
        when(mockInputQueue.take())
                .thenReturn(DummyMessage.newBuilder().setId(100).setContent("First").build())
                .thenReturn(DummyMessage.newBuilder().setId(101).setContent("Second").build())
                .thenReturn(DummyMessage.newBuilder().setId(100).setContent("Duplicate of first").build()) // Duplicate!
                .thenReturn(DummyMessage.newBuilder().setId(102).setContent("Third").build());

        service.start();

        long deadline = System.currentTimeMillis() + 1000;
        while (service.getCurrentState() != IService.State.STOPPED && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        Map<String, Number> metrics = service.getMetrics();
        assertEquals(4L, metrics.get("messages_received").longValue()); // All 4 received from queue
        assertEquals(1L, metrics.get("messages_duplicate").longValue()); // One was duplicate
        // Idempotency tracker should have 3 unique IDs (100, 101, 102)
        assertEquals(3L, metrics.get("idempotency_tracker_size").longValue());
    }
}