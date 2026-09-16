package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.services.IService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
        /** Counted down once the service has entered PROCESSING, which it then stays in. */
        private final CountDownLatch processingLatch = new CountDownLatch(1);
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
            processingLatch.countDown();
            Thread.interrupted();

            // Hold the phase until a stop is requested, so that a test observes it without having
            // to catch a window of its own duration.
            while (!isStopRequested()) {
                if (Thread.currentThread().isInterrupted()) {
                    wasInterruptedDuringProcessing.set(true);
                    return;
                }
                Thread.yield();
            }

            // Simulate a long write operation that must run to its end although a stop was requested
            long start = System.currentTimeMillis();
            while ((System.currentTimeMillis() - start) < processingDurationMs) {
                if (Thread.currentThread().isInterrupted()) {
                    wasInterruptedDuringProcessing.set(true);
                    return;
                }
                Thread.yield();
            }

            setShutdownPhase(ShutdownPhase.WAITING);
        }

        public boolean wasInterruptedDuringProcessing() {
            return wasInterruptedDuringProcessing.get();
        }
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void waitingPhaseServiceIsInterruptedImmediately() throws InterruptedException {
        // The service blocks forever and its shutdown timeout is an hour, so only the immediate
        // interrupt of the WAITING phase can end it: that stop() returns at all is the proof, and
        // the timeout of this test turns its absence into a failure rather than a hanging build.
        // A stopwatch would say the same and would fail on a stalled machine.
        Config longTimeout = config.withValue("shutdownTimeout", ConfigValueFactory.fromAnyRef(3600));
        WaitingPhaseService service = new WaitingPhaseService("waiting-svc", longTimeout, resources);
        service.start();
        assertTrue(service.runningLatch.await(2, TimeUnit.SECONDS), "Service should be running");

        service.stop();

        assertEquals(IService.State.STOPPED, service.getCurrentState());
    }

    @Test
    void processingPhaseServiceGetsGracePeriod() throws InterruptedException {
        // The service enters PROCESSING, stays there until the stop request, and then writes for
        // 500ms; stop() must let that writing finish instead of interrupting it.
        ProcessingPhaseService service = new ProcessingPhaseService("processing-svc", config, resources, 500);
        service.start();
        assertTrue(service.processingLatch.await(2, TimeUnit.SECONDS),
            "Service should have entered the PROCESSING phase");

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