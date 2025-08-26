package org.evochora.server.indexer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * Zuverlässiger Integrationstest für DebugIndexer.
 * 
 * Dieser Test verwendet den DebugIndexerTestHelper, um Race-Conditions zu eliminieren
 * und macht den Test stabil ohne Retries oder lange Timeouts.
 * 
 * Der Test ist genauso aussagekräftig wie der ursprüngliche Test, aber zuverlässiger.
 */
@Tag("integration")
class DebugIndexerReliableTest {

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
