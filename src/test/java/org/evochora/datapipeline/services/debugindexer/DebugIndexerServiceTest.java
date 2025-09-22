package org.evochora.datapipeline.services.debugindexer;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.BeforeEach;
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
    void testServiceLifecycle() {
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
