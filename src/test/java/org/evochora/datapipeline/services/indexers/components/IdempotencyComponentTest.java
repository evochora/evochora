package org.evochora.datapipeline.services.indexers.components;

import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for IdempotencyComponent.
 * <p>
 * Tests focus on error handling, validation, and safe defaults.
 * Integration tests verify end-to-end behavior with real indexers.
 */
@Tag("unit")
class IdempotencyComponentTest {
    
    @Test
    void testIsProcessed_NotProcessed() {
        // Given: Tracker returns false (not processed)
        @SuppressWarnings("unchecked")
        IIdempotencyTracker<String> tracker = mock(IIdempotencyTracker.class);
        when(tracker.isProcessed("DummyIndexer:batch_001.pb")).thenReturn(false);
        
        IdempotencyComponent component = new IdempotencyComponent(tracker, "DummyIndexer");
        
        // When: Check if batch is processed
        boolean isProcessed = component.isProcessed("batch_001.pb");
        
        // Then: Returns false (safe to process)
        assertThat(isProcessed).isFalse();
        verify(tracker).isProcessed("DummyIndexer:batch_001.pb");
    }
    
    @Test
    void testIsProcessed_AlreadyProcessed() {
        // Given: Tracker returns true (already processed)
        @SuppressWarnings("unchecked")
        IIdempotencyTracker<String> tracker = mock(IIdempotencyTracker.class);
        when(tracker.isProcessed("DummyIndexer:batch_001.pb")).thenReturn(true);
        
        IdempotencyComponent component = new IdempotencyComponent(tracker, "DummyIndexer");
        
        // When: Check if batch is processed
        boolean isProcessed = component.isProcessed("batch_001.pb");
        
        // Then: Returns true (can skip)
        assertThat(isProcessed).isTrue();
        verify(tracker).isProcessed("DummyIndexer:batch_001.pb");
    }
    
    @Test
    void testMarkProcessed_Success() {
        // Given: Tracker works normally
        @SuppressWarnings("unchecked")
        IIdempotencyTracker<String> tracker = mock(IIdempotencyTracker.class);
        IdempotencyComponent component = new IdempotencyComponent(tracker, "DummyIndexer");
        
        // When: Mark batch as processed
        component.markProcessed("batch_001.pb");
        
        // Then: Tracker was called with scoped key
        verify(tracker).markProcessed("DummyIndexer:batch_001.pb");
    }
    
    @Test
    void testIsProcessed_TrackerFailure() {
        // Given: Tracker throws exception
        @SuppressWarnings("unchecked")
        IIdempotencyTracker<String> tracker = mock(IIdempotencyTracker.class);
        when(tracker.isProcessed(anyString())).thenThrow(new RuntimeException("Database error"));
        
        IdempotencyComponent component = new IdempotencyComponent(tracker, "DummyIndexer");
        
        // When: Check if batch is processed (tracker fails)
        boolean isProcessed = component.isProcessed("batch_001.pb");
        
        // Then: Returns false (safe default - retry processing)
        // MERGE will handle duplicates anyway
        assertThat(isProcessed).isFalse();
    }
    
    @Test
    void testMarkProcessed_TrackerFailure() {
        // Given: Tracker throws exception on mark
        @SuppressWarnings("unchecked")
        IIdempotencyTracker<String> tracker = mock(IIdempotencyTracker.class);
        doThrow(new RuntimeException("Database error")).when(tracker).markProcessed(anyString());
        
        IdempotencyComponent component = new IdempotencyComponent(tracker, "DummyIndexer");
        
        // When: Mark batch as processed (tracker fails)
        // Then: Should NOT throw exception (marking is not critical)
        component.markProcessed("batch_001.pb");
        
        // Verify tracker was called (even though it failed)
        verify(tracker).markProcessed("DummyIndexer:batch_001.pb");
    }
    
    @Test
    void testConstructor_NullTracker() {
        // When/Then: Constructor rejects null tracker
        assertThatThrownBy(() -> new IdempotencyComponent(null, "DummyIndexer"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tracker must not be null");
    }
    
    @Test
    void testConstructor_NullIndexerClass() {
        // Given: Valid tracker
        @SuppressWarnings("unchecked")
        IIdempotencyTracker<String> tracker = mock(IIdempotencyTracker.class);
        
        // When/Then: Constructor rejects null indexer class
        assertThatThrownBy(() -> new IdempotencyComponent(tracker, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Indexer class must not be null or blank");
    }
    
    @Test
    void testConstructor_BlankIndexerClass() {
        // Given: Valid tracker
        @SuppressWarnings("unchecked")
        IIdempotencyTracker<String> tracker = mock(IIdempotencyTracker.class);
        
        // When/Then: Constructor rejects blank indexer class
        assertThatThrownBy(() -> new IdempotencyComponent(tracker, "   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Indexer class must not be null or blank");
    }
    
    @Test
    void testIsProcessed_NullBatchId() {
        // Given: Valid component
        @SuppressWarnings("unchecked")
        IIdempotencyTracker<String> tracker = mock(IIdempotencyTracker.class);
        IdempotencyComponent component = new IdempotencyComponent(tracker, "DummyIndexer");
        
        // When/Then: isProcessed rejects null batchId
        assertThatThrownBy(() -> component.isProcessed(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("batchId must not be null");
    }
    
    @Test
    void testMarkProcessed_NullBatchId() {
        // Given: Valid component
        @SuppressWarnings("unchecked")
        IIdempotencyTracker<String> tracker = mock(IIdempotencyTracker.class);
        IdempotencyComponent component = new IdempotencyComponent(tracker, "DummyIndexer");
        
        // When/Then: markProcessed rejects null batchId
        assertThatThrownBy(() -> component.markProcessed(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("batchId must not be null");
    }
}

