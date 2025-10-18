package org.evochora.datapipeline.services.indexers.components;

import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TickBufferingComponent.
 * <p>
 * Tests buffering logic, flush triggers, and cross-batch ACK tracking.
 */
@Tag("unit")
class TickBufferingComponentTest {
    
    private TickBufferingComponent component;
    
    @BeforeEach
    void setup() {
        component = new TickBufferingComponent(250, 5000);  // insertBatchSize=250, flushTimeout=5s
    }
    
    @Test
    void testSizeTriggeredFlush() {
        // Given: Buffer with ticks approaching insertBatchSize
        List<TickData> ticks = createTestTicks("run-001", 0, 200);
        TopicMessage<BatchInfo, String> msg = createTestMessage("batch-001");
        
        component.addTicksFromBatch(ticks, "batch-001", msg);
        
        // Then: Should not flush yet (200 < 250)
        assertFalse(component.shouldFlush(), "Should not flush at 200 ticks");
        assertEquals(200, component.getBufferSize());
        
        // When: Add more ticks to reach insertBatchSize
        List<TickData> moreTicks = createTestTicks("run-001", 200, 50);
        component.addTicksFromBatch(moreTicks, "batch-002", createTestMessage("batch-002"));
        
        // Then: Should trigger flush (250 >= 250)
        assertTrue(component.shouldFlush(), "Should flush at 250 ticks");
        assertEquals(250, component.getBufferSize());
    }
    
    @Test
    void testTimeoutTriggeredFlush() {
        // Given: Component with very short timeout
        TickBufferingComponent shortTimeoutComponent = new TickBufferingComponent(1000, 1);  // 1ms timeout
        
        List<TickData> ticks = createTestTicks("run-001", 0, 50);
        TopicMessage<BatchInfo, String> msg = createTestMessage("batch-001");
        
        shortTimeoutComponent.addTicksFromBatch(ticks, "batch-001", msg);
        
        // When: Wait using Awaitility for timeout to trigger
        org.awaitility.Awaitility.await()
            .atMost(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            .pollInterval(5, java.util.concurrent.TimeUnit.MILLISECONDS)
            .until(shortTimeoutComponent::shouldFlush);
        
        // Then: Flush should be triggered by timeout
        assertTrue(shortTimeoutComponent.shouldFlush(), "Should flush after timeout");
        assertEquals(50, shortTimeoutComponent.getBufferSize(), "Buffer should still contain ticks");
    }
    
    @Test
    void testCrossBatchAckTracking() {
        // Given: 3 batches of 100 ticks each, insertBatchSize=250
        List<TickData> batch1 = createTestTicks("run-001", 0, 100);
        List<TickData> batch2 = createTestTicks("run-001", 100, 100);
        List<TickData> batch3 = createTestTicks("run-001", 200, 100);
        
        TopicMessage<BatchInfo, String> msg1 = createTestMessage("batch-001");
        TopicMessage<BatchInfo, String> msg2 = createTestMessage("batch-002");
        TopicMessage<BatchInfo, String> msg3 = createTestMessage("batch-003");
        
        component.addTicksFromBatch(batch1, "batch-001", msg1);
        component.addTicksFromBatch(batch2, "batch-002", msg2);
        component.addTicksFromBatch(batch3, "batch-003", msg3);
        
        // When: Flush (buffer: 300, will flush 250)
        TickBufferingComponent.FlushResult<String> result = component.flush();
        
        // Then: Verify flush result
        assertEquals(250, result.ticks().size(), "Should flush 250 ticks");
        assertEquals(50, component.getBufferSize(), "Should have 50 ticks remaining");
        
        // CRITICAL: Only batch_001 and batch_002 should be ACKed (fully flushed)
        assertEquals(2, result.completedMessages().size(), 
            "Only 2 batches should be complete");
        
        // Verify the messages (can't check exact identity, but verify count is correct)
        // batch_001: all 100 ticks flushed ✅
        // batch_002: all 100 ticks flushed ✅
        // batch_003: only 50/100 ticks flushed ❌
    }
    
    @Test
    void testPartialBatchNotAcked() {
        // Given: One batch partially flushed
        List<TickData> ticks = createTestTicks("run-001", 0, 100);
        TopicMessage<BatchInfo, String> msg = createTestMessage("batch-001");
        
        component.addTicksFromBatch(ticks, "batch-001", msg);
        assertEquals(100, component.getBufferSize());
        
        // When: Flush only 50 ticks
        component = new TickBufferingComponent(50, 5000);  // insertBatchSize=50
        component.addTicksFromBatch(ticks, "batch-001", msg);
        
        TickBufferingComponent.FlushResult<String> result = component.flush();
        
        // Then: Verify partial flush
        assertEquals(50, result.ticks().size(), "Should flush 50 ticks");
        assertEquals(50, component.getBufferSize(), "Should have 50 ticks remaining");
        
        // CRITICAL: Batch should NOT be ACKed (only 50/100 flushed)
        assertTrue(result.completedMessages().isEmpty(), 
            "Partial batch should NOT be ACKed");
        
        // When: Flush remaining ticks
        TickBufferingComponent.FlushResult<String> secondFlush = component.flush();
        
        // Then: Now batch should be complete
        assertEquals(50, secondFlush.ticks().size());
        assertEquals(0, component.getBufferSize());
        assertEquals(1, secondFlush.completedMessages().size(), 
            "Batch should be ACKed after all ticks flushed");
    }
    
    @Test
    void testEmptyFlush() {
        // Given: Empty buffer
        assertEquals(0, component.getBufferSize());
        assertFalse(component.shouldFlush(), "Empty buffer should not trigger flush");
        
        // When: Flush empty buffer
        TickBufferingComponent.FlushResult<String> result = component.flush();
        
        // Then: Returns empty result
        assertTrue(result.ticks().isEmpty(), "Should return empty ticks list");
        assertTrue(result.completedMessages().isEmpty(), "Should return empty messages list");
        assertEquals(0, component.getBufferSize(), "Buffer should still be empty");
    }
    
    // ========== Helper Methods ==========
    
    private List<TickData> createTestTicks(String runId, long startTick, int count) {
        List<TickData> ticks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ticks.add(TickData.newBuilder()
                .setSimulationRunId(runId)
                .setTickNumber(startTick + i)
                .setCaptureTimeMs(System.currentTimeMillis())
                .build());
        }
        return ticks;
    }
    
    private TopicMessage<BatchInfo, String> createTestMessage(String batchId) {
        BatchInfo batchInfo = BatchInfo.newBuilder()
            .setSimulationRunId("test-run")
            .setStorageKey(batchId)
            .setTickStart(0)
            .setTickEnd(99)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        return new TopicMessage<>(
            batchInfo,
            System.currentTimeMillis(),
            "msg-" + batchId,
            "test-consumer",
            "ack-" + batchId
        );
    }
}

