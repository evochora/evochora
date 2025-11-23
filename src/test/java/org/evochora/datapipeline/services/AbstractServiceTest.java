package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.services.IService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Tag("unit")
public class AbstractServiceTest {

    private Config config;
    private Map<String, List<IResource>> resources;

    @BeforeEach
    void setUp() {
        config = ConfigFactory.empty();
        resources = new HashMap<>();
    }

    // Test implementation of AbstractService
    private static class TestService extends AbstractService {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicBoolean wasInterrupted = new AtomicBoolean(false);
        volatile boolean isRunning = false;

        protected TestService(String name, Config options, Map<String, List<IResource>> resources) {
            super(name, options, resources);
        }

        @Override
        protected void run() throws InterruptedException {
            isRunning = true;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    checkPause();
                    // Simulate work
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
                isRunning = false;
            }
        }

        public boolean wasInterrupted() {
            return wasInterrupted.get();
        }

        public void awaitTermination() throws InterruptedException {
            latch.await(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void serviceStartsAndStopsCorrectly() throws InterruptedException {
        TestService service = new TestService("test-service", config, resources);
        assertEquals(IService.State.STOPPED, service.getCurrentState());

        service.start();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(IService.State.RUNNING, service.getCurrentState());
            assertTrue(service.isRunning);
        });

        service.stop();
        service.awaitTermination();
        assertEquals(IService.State.STOPPED, service.getCurrentState());
        assertFalse(service.isRunning);
        assertTrue(service.wasInterrupted());
    }

    @Test
    void servicePausesAndResumesCorrectly() throws InterruptedException {
        TestService service = new TestService("test-service", config, resources);
        service.start();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> 
            assertEquals(IService.State.RUNNING, service.getCurrentState()));

        service.pause();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> 
            assertEquals(IService.State.PAUSED, service.getCurrentState()));

        service.resume();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> 
            assertEquals(IService.State.RUNNING, service.getCurrentState()));

        service.stop();
        service.awaitTermination();
    }

    @Test
    void restartMethodWorksCorrectly() throws InterruptedException {
        TestService service = new TestService("test-service", config, resources);
        service.start();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> 
            assertEquals(IService.State.RUNNING, service.getCurrentState()));

        service.restart();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(IService.State.RUNNING, service.getCurrentState());
            assertTrue(service.isRunning);
        });

        service.stop();
        service.awaitTermination();
    }

    @Test
    void getRequiredResourceReturnsCorrectResource() {
        IResource mockResource = mock(IResource.class);
        resources.put("testPort", Collections.singletonList(mockResource));
        TestService service = new TestService("test-service", config, resources);

        IResource retrieved = service.getRequiredResource("testPort", IResource.class);
        assertSame(mockResource, retrieved);
    }

    @Test
    void getRequiredResourceThrowsWhenPortNotConfigured() {
        TestService service = new TestService("test-service", config, resources);
        assertThrows(IllegalStateException.class, () -> {
            service.getRequiredResource("nonExistent", IResource.class);
        });
    }

    @Test
    void getRequiredResourceThrowsWhenNoResources() {
        resources.put("emptyPort", Collections.emptyList());
        TestService service = new TestService("test-service", config, resources);
        assertThrows(IllegalStateException.class, () -> {
            service.getRequiredResource("emptyPort", IResource.class);
        });
    }

    @Test
    void getRequiredResourceThrowsWhenMultipleResources() {
        resources.put("multiPort", List.of(mock(IResource.class), mock(IResource.class)));
        TestService service = new TestService("test-service", config, resources);
        assertThrows(IllegalStateException.class, () -> {
            service.getRequiredResource("multiPort", IResource.class);
        });
    }

    @Test
    void getRequiredResourceThrowsWhenWrongType() {
        resources.put("wrongTypePort", Collections.singletonList(mock(IResource.class)));
        TestService service = new TestService("test-service", config, resources);
        assertThrows(IllegalStateException.class, () -> {
            service.getRequiredResource("wrongTypePort", TestResource.class);
        });
    }

    private interface TestResource extends IResource {}
}