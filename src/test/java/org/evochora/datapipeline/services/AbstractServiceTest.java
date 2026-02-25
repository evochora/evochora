package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.services.IService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

    /**
     * A service that stays in WAITING phase (default) and blocks on Thread.sleep().
     * stop() should interrupt it immediately.
     */
    private static class WaitingPhaseService extends AbstractService {
        private final CountDownLatch runningLatch = new CountDownLatch(1);
        private final CountDownLatch terminatedLatch = new CountDownLatch(1);
        volatile long stopDurationMs = -1;

        protected WaitingPhaseService(String name, Config options, Map<String, List<IResource>> resources) {
            super(name, options, resources);
        }

        @Override
        protected void run() throws InterruptedException {
            runningLatch.countDown();
            // Blocks indefinitely — relies on interrupt to exit
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    /**
     * A service that enters PROCESSING phase and stays there for a controlled duration.
     * stop() should wait for the grace period instead of interrupting immediately.
     */
    private static class ProcessingPhaseService extends AbstractService {
        private final CountDownLatch runningLatch = new CountDownLatch(1);
        private final AtomicBoolean wasInterruptedDuringProcessing = new AtomicBoolean(false);
        private final long processingDurationMs;

        protected ProcessingPhaseService(String name, Config options, Map<String, List<IResource>> resources,
                                         long processingDurationMs) {
            super(name, options, resources);
            this.processingDurationMs = processingDurationMs;
        }

        @Override
        protected void run() throws InterruptedException {
            runningLatch.countDown();
            setShutdownPhase(ShutdownPhase.PROCESSING);
            Thread.interrupted();

            // Simulate a long write operation that must not be interrupted
            long start = System.currentTimeMillis();
            while ((System.currentTimeMillis() - start) < processingDurationMs) {
                if (Thread.currentThread().isInterrupted()) {
                    wasInterruptedDuringProcessing.set(true);
                    return;
                }
                Thread.yield();
            }

            setShutdownPhase(ShutdownPhase.WAITING);

            // After processing, check for stop
            while (!isStopRequested() && !Thread.currentThread().isInterrupted()) {
                Thread.sleep(50);
            }
        }

        public boolean wasInterruptedDuringProcessing() {
            return wasInterruptedDuringProcessing.get();
        }
    }

    @Test
    void waitingPhaseServiceIsInterruptedImmediately() throws InterruptedException {
        WaitingPhaseService service = new WaitingPhaseService("waiting-svc", config, resources);
        service.start();
        assertTrue(service.runningLatch.await(2, TimeUnit.SECONDS), "Service should be running");

        long start = System.currentTimeMillis();
        service.stop();
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(IService.State.STOPPED, service.getCurrentState());
        // WAITING phase should be interrupted immediately, not wait for full timeout (5s default)
        assertTrue(elapsed < 2000, "WAITING service should stop quickly, took " + elapsed + "ms");
    }

    @Test
    void processingPhaseServiceGetsGracePeriod() throws InterruptedException {
        // Service will be in PROCESSING for 500ms, then return to WAITING
        ProcessingPhaseService service = new ProcessingPhaseService("processing-svc", config, resources, 500);
        service.start();
        assertTrue(service.runningLatch.await(2, TimeUnit.SECONDS), "Service should be running");

        // Small delay to ensure service has entered PROCESSING phase
        Thread.sleep(50);
        assertEquals(IService.ShutdownPhase.PROCESSING, service.getShutdownPhase());

        service.stop();

        assertEquals(IService.State.STOPPED, service.getCurrentState());
        // Service should NOT have been interrupted while in PROCESSING
        assertFalse(service.wasInterruptedDuringProcessing(),
            "Service should not be interrupted during PROCESSING phase");
    }

    @Test
    void defaultShutdownPhaseIsWaiting() {
        TestService service = new TestService("test-service", config, resources);
        assertEquals(IService.ShutdownPhase.WAITING, service.getShutdownPhase());
    }

    private interface TestResource extends IResource {}
}