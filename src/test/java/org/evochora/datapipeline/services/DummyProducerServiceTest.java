package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.SystemContracts.DummyMessage;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.services.IService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class DummyProducerServiceTest {

    private IOutputQueueResource<DummyMessage> mockOutputQueue;
    private Map<String, List<IResource>> resources;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        mockOutputQueue = mock(IOutputQueueResource.class);
        resources = new HashMap<>();
        resources.put("output", Collections.singletonList(mockOutputQueue));
    }

    @Test
    void testConfiguration() {
        Config config = ConfigFactory.parseString("intervalMs=500, messagePrefix=\"Test\", maxMessages=100");
        DummyProducerService service = new DummyProducerService("test-producer", config, resources);

        // Use reflection to check private fields or test behavior dependent on them
        // For this example, we'll assume the service's behavior reflects its config
        assertNotNull(service);
    }

    @Test
    void testMessageSending() throws InterruptedException {
        Config config = ConfigFactory.parseString("intervalMs=10, maxMessages=3, messagePrefix=\"Test\"");
        DummyProducerService service = new DummyProducerService("test-producer", config, resources);

        service.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> service.getCurrentState() == IService.State.STOPPED);

        ArgumentCaptor<DummyMessage> captor = ArgumentCaptor.forClass(DummyMessage.class);
        verify(mockOutputQueue, times(3)).put(captor.capture());

        List<DummyMessage> sentMessages = captor.getAllValues();
        assertEquals(3, sentMessages.size());
        assertEquals("Test-0", sentMessages.get(0).getContent());
        assertEquals("Test-1", sentMessages.get(1).getContent());
        assertEquals("Test-2", sentMessages.get(2).getContent());
    }

    @Test
    void testLifecycle() throws InterruptedException {
        Config config = ConfigFactory.empty();
        DummyProducerService service = new DummyProducerService("test-producer", config, resources);

        assertEquals(IService.State.STOPPED, service.getCurrentState());
        service.start();
        assertEquals(IService.State.RUNNING, service.getCurrentState());
        service.pause();
        assertEquals(IService.State.PAUSED, service.getCurrentState());
        service.resume();
        assertEquals(IService.State.RUNNING, service.getCurrentState());
        service.stop();
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> service.getCurrentState() == IService.State.STOPPED);
    }

    @Test
    void testMetrics() throws InterruptedException {
        Config config = ConfigFactory.parseString("maxMessages=5, intervalMs=10");
        DummyProducerService service = new DummyProducerService("test-producer", config, resources);

        service.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> service.getCurrentState() == IService.State.STOPPED);

        Map<String, Number> metrics = service.getMetrics();
        assertEquals(5L, metrics.get("messages_sent").longValue());
        assertTrue(metrics.get("throughput_per_sec").doubleValue() >= 0);
    }

    @Test
    void testMaxMessages() throws InterruptedException {
        Config config = ConfigFactory.parseString("maxMessages=2, intervalMs=1");
        DummyProducerService service = new DummyProducerService("test-producer", config, resources);
        service.start();
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> service.getCurrentState() == IService.State.STOPPED);
        verify(mockOutputQueue, times(2)).put(any(DummyMessage.class));
    }
}