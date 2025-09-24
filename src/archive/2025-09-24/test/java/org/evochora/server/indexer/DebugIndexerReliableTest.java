package org.evochora.server.indexer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * Contains reliable integration tests for the {@link DebugIndexer}.
 * <p>
 * These tests use the {@link DebugIndexerTestHelper} to encapsulate database setup,
 * teardown, and processing logic. This approach avoids race conditions and eliminates
 * the need for arbitrary `Thread.sleep()` calls, making the tests more stable and reliable.
 * <p>
 * The tests verify the core functionality of the indexer: reading data from a raw database,
 * processing it, and writing the prepared data to a debug database.
 */
@Tag("integration")
class DebugIndexerReliableTest {

    /**
     * Verifies that the indexer can reliably process a single tick. It sets up the
     * necessary database state, processes one tick, and then verifies the output
     * in the prepared database.
     * This is an integration test using in-memory databases.
     * @throws Exception if database or helper operations fail.
     */
    @Test
    void indexer_readsRawDbAnd_writesPreparedDb_reliable() throws Exception {
        // Verwende den Test-Helper für zuverlässiges Setup und Teardown
        try (DebugIndexerTestHelper helper = new DebugIndexerTestHelper()) {
            
            // 1. Arrange: Setup der Datenbanken
            helper.setupRawDatabase();
            helper.setupDebugDatabase();
            
            // 2. Act: Erstelle Indexer und verarbeite einen Tick
            DebugIndexer indexer = helper.createIndexer();
            helper.processTick(indexer, 1L);
            
            // 3. Assert: Überprüfe das Ergebnis
            helper.verifyPreparedTick(1L);
            
            // Cleanup: Indexer herunterfahren
            indexer.shutdown();
        }
        // Helper wird automatisch aufgeräumt durch try-with-resources
    }

    /**
     * Verifies that the indexer can reliably process multiple ticks in sequence.
     * This is an integration test using in-memory databases.
     * @throws Exception if database or helper operations fail.
     */
    @Test
    void indexer_processesMultipleTicks_reliable() throws Exception {
        // Teste die Verarbeitung mehrerer Ticks
        try (DebugIndexerTestHelper helper = new DebugIndexerTestHelper()) {
            
            // Setup
            helper.setupRawDatabase();
            helper.addAdditionalTicks(2); // Füge Tick 2 hinzu
            helper.setupDebugDatabase();
            
            // Erstelle Indexer
            DebugIndexer indexer = helper.createIndexer();
            
            // Verarbeite mehrere Ticks
            helper.processTick(indexer, 1L);
            helper.processTick(indexer, 2L);
            
            // Überprüfe beide Ticks
            helper.verifyPreparedTick(1L);
            helper.verifyPreparedTick(2L);
            
            // Cleanup
            indexer.shutdown();
        }
    }

    /**
     * Verifies that the indexer correctly processes a standard tick, which may not have
     * organisms or cells. The name implies testing an "empty" tick.
     * This is an integration test using in-memory databases.
     * @throws Exception if database or helper operations fail.
     */
    @Test
    void indexer_handlesEmptyTick_reliable() throws Exception {
        // Teste das Verhalten bei leeren Ticks
        try (DebugIndexerTestHelper helper = new DebugIndexerTestHelper()) {
            
            // Setup
            helper.setupRawDatabase();
            helper.setupDebugDatabase();
            
            // Erstelle Indexer
            DebugIndexer indexer = helper.createIndexer();
            
            // Verarbeite einen Tick
            helper.processTick(indexer, 1L);
            
            // Überprüfe das Ergebnis
            helper.verifyPreparedTick(1L);
            
            // Cleanup
            indexer.shutdown();
        }
    }
}
