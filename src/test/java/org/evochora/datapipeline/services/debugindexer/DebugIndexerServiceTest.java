package org.evochora.datapipeline.services.debugindexer;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.channels.InMemoryChannel;
import org.evochora.datapipeline.core.InputChannelBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DebugIndexerService.
 * Tests the service configuration and basic functionality.
 */
@Tag("unit")
class DebugIndexerServiceTest {

    private DebugIndexerService debugIndexer;

    @BeforeEach
    void setUp() {
        // Create a simple configuration
        Config config = ConfigFactory.parseString("""
            debugDbPath = "test-debug.sqlite"
            batchSize = 10
            enabled = true
            database {
              synchronous = "NORMAL"
              cacheSize = 1000
            }
            memoryOptimization {
              enabled = true
            }
            """);

        debugIndexer = new DebugIndexerService(config);
    }

    @Test
    void testServiceInitialization() {
        assertNotNull(debugIndexer);
        assertTrue(debugIndexer.isEnabled());
        assertEquals("test-debug.sqlite", debugIndexer.getDebugDbPath());
        assertEquals(10, debugIndexer.getBatchSize());
    }

    @Test
    @Disabled("Disabling test for now as the service is undergoing refactoring and will be replaced.")
    void testServiceLifecycle() {
        // Add a dummy channel to satisfy the startup check
        debugIndexer.addInputChannel("test-input", new InputChannelBinding<>("test", "test-input", "test-channel", new InMemoryChannel<>(ConfigFactory.empty())));
        
        // Test start
        debugIndexer.start();
        // Wait for service to actually start (polling)
        while (!debugIndexer.isRunning() && debugIndexer.getServiceStatus().state() != org.evochora.datapipeline.api.services.State.STOPPED) {
            Thread.yield();
        }
        assertTrue(debugIndexer.isRunning());

        // Test pause
        debugIndexer.pause();
        assertTrue(debugIndexer.isPaused());

        // Test resume
        debugIndexer.resume();
        // Wait for service to actually resume (polling)
        while (!debugIndexer.isRunning() && debugIndexer.getServiceStatus().state() != org.evochora.datapipeline.api.services.State.STOPPED) {
            Thread.yield();
        }
        assertTrue(debugIndexer.isRunning());

        // Test stop
        debugIndexer.stop();
        assertTrue(debugIndexer.isStopped());
    }
}
