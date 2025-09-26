package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.PipelineContracts.DummyMessage;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.wrappers.queues.IInputQueueResource;
import org.evochora.datapipeline.api.services.IService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.OngoingStubbing;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class DummyConsumerServiceTest {

    private IInputQueueResource<DummyMessage> mockInputQueue;
    private Map<String, List<IResource>> resources;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        mockInputQueue = mock(IInputQueueResource.class);
        resources = new HashMap<>();
        resources.put("input", Collections.singletonList(mockInputQueue));
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

        when(mockInputQueue.take())
                .thenReturn(DummyMessage.newBuilder().setContent("Msg1").build())
                .thenReturn(DummyMessage.newBuilder().setContent("Msg2").build());

        service.start();

        long deadline = System.currentTimeMillis() + 1000;
        while (service.getCurrentState() != IService.State.STOPPED && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(IService.State.STOPPED, service.getCurrentState());

        verify(mockInputQueue, times(2)).take();
        Map<String, Number> metrics = service.getMetrics();
        assertEquals(2L, metrics.get("messages_received").longValue());
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

        when(mockInputQueue.take())
                .thenReturn(DummyMessage.getDefaultInstance())
                .thenReturn(DummyMessage.getDefaultInstance())
                .thenReturn(DummyMessage.getDefaultInstance());

        service.start();

        long deadline = System.currentTimeMillis() + 1000;
        while (service.getCurrentState() != IService.State.STOPPED && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(IService.State.STOPPED, service.getCurrentState());

        Map<String, Number> metrics = service.getMetrics();
        assertEquals(3L, metrics.get("messages_received").longValue());
        assertTrue(metrics.get("throughput_per_sec").doubleValue() >= 0);
    }

    @Test
    void testMaxMessages() throws InterruptedException {
        Config config = ConfigFactory.parseString("maxMessages=2");
        DummyConsumerService service = new DummyConsumerService("test-consumer", config, resources);
        when(mockInputQueue.take()).thenReturn(DummyMessage.getDefaultInstance());
        service.start();
        Thread.sleep(100); // Let service run and stop itself
        verify(mockInputQueue, times(2)).take();
        assertEquals(IService.State.STOPPED, service.getCurrentState());
    }
}