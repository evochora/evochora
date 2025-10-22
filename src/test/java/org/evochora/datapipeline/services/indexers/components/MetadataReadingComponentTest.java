package org.evochora.datapipeline.services.indexers.components;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataReader;
import org.evochora.datapipeline.api.resources.database.MetadataNotFoundException;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MetadataReadingComponent.
 * <p>
 * Tests metadata polling, caching, and timeout behavior.
 */
@ExtendWith(LogWatchExtension.class)
@Tag("unit")
class MetadataReadingComponentTest {
    
    @Mock
    private IResourceSchemaAwareMetadataReader mockReader;
    
    private MetadataReadingComponent component;
    private SimulationMetadata testMetadata;
    
    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        
        // Create test metadata
        testMetadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setSamplingInterval(10)
            .setInitialSeed(12345L)
            .build();
        
        // Create component with short timeouts for testing
        component = new MetadataReadingComponent(mockReader, 10, 1000);
    }
    
    @Test
    void loadMetadata_success() throws Exception {
        // Given: Mock returns metadata immediately
        when(mockReader.getMetadata(eq("test-run")))
            .thenReturn(testMetadata);
        
        // When: Load metadata
        component.loadMetadata("test-run");
        
        // Then: Metadata cached
        assertNotNull(component.getMetadata());
        assertEquals("test-run", component.getMetadata().getSimulationRunId());
        assertEquals(10, component.getSamplingInterval());
        
        // Verify only one call (no polling needed)
        verify(mockReader, times(1)).getMetadata(eq("test-run"));
        verify(mockReader, never()).releaseConnection();
    }
    
    @Test
    void loadMetadata_pollsUntilAvailable() throws Exception {
        // Given: First 3 calls fail, 4th succeeds
        AtomicInteger callCount = new AtomicInteger(0);
        when(mockReader.getMetadata(eq("test-run")))
            .thenAnswer(invocation -> {
                int count = callCount.incrementAndGet();
                if (count < 4) {
                    throw new MetadataNotFoundException("Not yet available");
                }
                return testMetadata;
            });
        
        // When: Load metadata (will poll)
        component.loadMetadata("test-run");
        
        // Then: Metadata eventually loaded
        assertNotNull(component.getMetadata());
        assertEquals("test-run", component.getMetadata().getSimulationRunId());
        
        // Verify 4 attempts
        verify(mockReader, times(4)).getMetadata(eq("test-run"));
        
        // Verify connection released 3 times (before each sleep)
        verify(mockReader, times(3)).releaseConnection();
    }
    
    @Test
    void loadMetadata_timeout() throws Exception {
        // Given: Mock always throws MetadataNotFoundException
        when(mockReader.getMetadata(eq("missing-run")))
            .thenThrow(new MetadataNotFoundException("Not found"));
        
        // When/Then: Timeout after maxPollDurationMs
        TimeoutException ex = assertThrows(TimeoutException.class, () -> 
            component.loadMetadata("missing-run")
        );
        
        assertTrue(ex.getMessage().contains("Metadata not indexed within 1000ms"));
        assertTrue(ex.getMessage().contains("missing-run"));
        
        // Verify multiple attempts were made
        verify(mockReader, atLeast(2)).getMetadata(eq("missing-run"));
        verify(mockReader, atLeast(1)).releaseConnection();
    }
    
    @Test
    void loadMetadata_interrupted() throws Exception {
        // Given: Mock throws MetadataNotFoundException
        when(mockReader.getMetadata(eq("test-run")))
            .thenThrow(new MetadataNotFoundException("Not found"));
        
        // Given: Create component with longer poll interval to ensure interrupt happens during sleep
        component = new MetadataReadingComponent(mockReader, 500, 10000);
        
        // When: Start load in separate thread and interrupt it
        Thread loadThread = new Thread(() -> {
            assertThrows(InterruptedException.class, () -> 
                component.loadMetadata("test-run")
            );
        });
        
        loadThread.start();
        
        // Wait a bit to ensure polling has started
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // Ignore
        }
        
        // Interrupt the thread
        loadThread.interrupt();
        
        // Wait for thread to complete
        try {
            loadThread.join(2000);
        } catch (InterruptedException e) {
            // Ignore
        }
        
        assertFalse(loadThread.isAlive(), "Thread should have terminated after interruption");
    }
    
    @Test
    void getSamplingInterval_beforeLoad_throwsException() {
        // Given: Metadata not loaded
        
        // When/Then: Exception thrown
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> 
            component.getSamplingInterval()
        );
        
        assertTrue(ex.getMessage().contains("Metadata not loaded"));
        assertTrue(ex.getMessage().contains("call loadMetadata() first"));
    }
    
    @Test
    void getMetadata_beforeLoad_throwsException() {
        // Given: Metadata not loaded
        
        // When/Then: Exception thrown
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> 
            component.getMetadata()
        );
        
        assertTrue(ex.getMessage().contains("Metadata not loaded"));
    }
    
    @Test
    void loadMetadata_multipleCalls_usesCache() throws Exception {
        // Given: Mock returns metadata
        when(mockReader.getMetadata(eq("test-run")))
            .thenReturn(testMetadata);
        
        // When: Load metadata once
        component.loadMetadata("test-run");
        
        // Then: Multiple getSamplingInterval calls use cached value (no additional DB calls)
        assertEquals(10, component.getSamplingInterval());
        assertEquals(10, component.getSamplingInterval());
        assertEquals(10, component.getSamplingInterval());
        
        // Verify only one getMetadata call
        verify(mockReader, times(1)).getMetadata(eq("test-run"));
    }
}

