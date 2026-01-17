package org.evochora.datapipeline.services.indexers.components;

import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ChunkBufferingComponent.
 * <p>
 * Tests buffering logic, flush triggers, and cross-batch ACK tracking.
 * After delta compression, the component buffers chunks (not individual ticks).
 */
@Tag("unit")
class ChunkBufferingComponentTest {
    
    private ChunkBufferingComponent component;
    
    @BeforeEach
    void setup() {
        component = new ChunkBufferingComponent(5, 5000);  // insertBatchSize=5 chunks, flushTimeout=5s
    }
    
    @Test
    void testSizeTriggeredFlush() {
        // Given: Buffer with chunks approaching insertBatchSize
        List<TickDataChunk> chunks = createTestChunks("run-001", 0, 4);
        TopicMessage<BatchInfo, String> msg = createTestMessage("batch-001");
        
        component.addChunksFromBatch(chunks, "batch-001", msg);
        
        // Then: Should not flush yet (4 < 5)
        assertFalse(component.shouldFlush(), "Should not flush at 4 chunks");
        assertEquals(4, component.getBufferSize());
        
        // When: Add more chunks to reach insertBatchSize
        List<TickDataChunk> moreChunks = createTestChunks("run-001", 400, 1);
        component.addChunksFromBatch(moreChunks, "batch-002", createTestMessage("batch-002"));
        
        // Then: Should trigger flush (5 >= 5)
        assertTrue(component.shouldFlush(), "Should flush at 5 chunks");
        assertEquals(5, component.getBufferSize());
    }
    
    @Test
    void testTimeoutTriggeredFlush() {
        // Given: Component with very short timeout
        ChunkBufferingComponent shortTimeoutComponent = new ChunkBufferingComponent(100, 1);  // 1ms timeout
        
        List<TickDataChunk> chunks = createTestChunks("run-001", 0, 2);
        TopicMessage<BatchInfo, String> msg = createTestMessage("batch-001");
        
        shortTimeoutComponent.addChunksFromBatch(chunks, "batch-001", msg);
        
        // When: Wait using Awaitility for timeout to trigger
        org.awaitility.Awaitility.await()
            .atMost(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            .pollInterval(5, java.util.concurrent.TimeUnit.MILLISECONDS)
            .until(shortTimeoutComponent::shouldFlush);
        
        // Then: Flush should be triggered by timeout
        assertTrue(shortTimeoutComponent.shouldFlush(), "Should flush after timeout");
        assertEquals(2, shortTimeoutComponent.getBufferSize(), "Buffer should still contain chunks");
    }
    
    @Test
    void testCrossBatchAckTracking() {
        // Given: 3 storage batches of 2 chunks each, insertBatchSize=5
        List<TickDataChunk> batch1 = createTestChunks("run-001", 0, 2);
        List<TickDataChunk> batch2 = createTestChunks("run-001", 200, 2);
        List<TickDataChunk> batch3 = createTestChunks("run-001", 400, 2);
        
        TopicMessage<BatchInfo, String> msg1 = createTestMessage("batch-001");
        TopicMessage<BatchInfo, String> msg2 = createTestMessage("batch-002");
        TopicMessage<BatchInfo, String> msg3 = createTestMessage("batch-003");
        
        component.addChunksFromBatch(batch1, "batch-001", msg1);
        component.addChunksFromBatch(batch2, "batch-002", msg2);
        component.addChunksFromBatch(batch3, "batch-003", msg3);
        
        // When: Flush (buffer: 6 chunks, will flush 5)
        ChunkBufferingComponent.FlushResult<String> result = component.flush();
        
        // Then: Verify flush result
        assertEquals(5, result.chunks().size(), "Should flush 5 chunks");
        assertEquals(1, component.getBufferSize(), "Should have 1 chunk remaining");
        
        // CRITICAL: Only batch_001 and batch_002 should be ACKed (fully flushed)
        assertEquals(2, result.completedMessages().size(), 
            "Only 2 batches should be complete");
        
        // Verify the messages (can't check exact identity, but verify count is correct)
        // batch_001: all 2 chunks flushed ✅
        // batch_002: all 2 chunks flushed ✅
        // batch_003: only 1/2 chunks flushed ❌
    }
    
    @Test
    void testPartialBatchNotAcked() {
        // Given: One batch partially flushed
        List<TickDataChunk> chunks = createTestChunks("run-001", 0, 4);
        TopicMessage<BatchInfo, String> msg = createTestMessage("batch-001");
        
        component.addChunksFromBatch(chunks, "batch-001", msg);
        assertEquals(4, component.getBufferSize());
        
        // When: Flush only 2 chunks (component with insertBatchSize=2)
        component = new ChunkBufferingComponent(2, 5000);  // insertBatchSize=2
        component.addChunksFromBatch(chunks, "batch-001", msg);
        
        ChunkBufferingComponent.FlushResult<String> result = component.flush();
        
        // Then: Verify partial flush
        assertEquals(2, result.chunks().size(), "Should flush 2 chunks");
        assertEquals(2, component.getBufferSize(), "Should have 2 chunks remaining");
        
        // CRITICAL: Batch should NOT be ACKed (only 2/4 flushed)
        assertTrue(result.completedMessages().isEmpty(), 
            "Partial batch should NOT be ACKed");
        
        // When: Flush remaining chunks
        ChunkBufferingComponent.FlushResult<String> secondFlush = component.flush();
        
        // Then: Now batch should be complete
        assertEquals(2, secondFlush.chunks().size());
        assertEquals(0, component.getBufferSize());
        assertEquals(1, secondFlush.completedMessages().size(), 
            "Batch should be ACKed after all chunks flushed");
    }
    
    @Test
    void testEmptyFlush() {
        // Given: Empty buffer
        assertEquals(0, component.getBufferSize());
        assertFalse(component.shouldFlush(), "Empty buffer should not trigger flush");
        
        // When: Flush empty buffer
        ChunkBufferingComponent.FlushResult<String> result = component.flush();
        
        // Then: Returns empty result
        assertTrue(result.chunks().isEmpty(), "Should return empty chunks list");
        assertTrue(result.completedMessages().isEmpty(), "Should return empty messages list");
        assertTrue(result.completedBatchIds().isEmpty(), "Should return empty batch IDs list");
        assertEquals(0, component.getBufferSize(), "Buffer should still be empty");
    }
    
    @Test
    void testFlush_ReturnsCompletedBatchIds() {
        // Given: 3 storage batches of 2 chunks each, insertBatchSize=5 → first 2 batches fully flushed
        List<TickDataChunk> batch1 = createTestChunks("run-001", 0, 2);
        List<TickDataChunk> batch2 = createTestChunks("run-001", 200, 2);
        List<TickDataChunk> batch3 = createTestChunks("run-001", 400, 2);
        
        TopicMessage<BatchInfo, String> msg1 = createTestMessage("batch-001");
        TopicMessage<BatchInfo, String> msg2 = createTestMessage("batch-002");
        TopicMessage<BatchInfo, String> msg3 = createTestMessage("batch-003");
        
        component.addChunksFromBatch(batch1, "batch-001", msg1);
        component.addChunksFromBatch(batch2, "batch-002", msg2);
        component.addChunksFromBatch(batch3, "batch-003", msg3);
        
        // When: Flush
        ChunkBufferingComponent.FlushResult<String> result = component.flush();
        
        // Then: completedBatchIds should match completedMessages
        assertEquals(2, result.completedMessages().size(), 
            "Should have 2 completed messages");
        assertEquals(2, result.completedBatchIds().size(), 
            "Should have 2 completed batch IDs");
        
        // Verify batch IDs are correct
        assertTrue(result.completedBatchIds().contains("batch-001"), 
            "Should contain batch-001 ID");
        assertTrue(result.completedBatchIds().contains("batch-002"), 
            "Should contain batch-002 ID");
        assertFalse(result.completedBatchIds().contains("batch-003"), 
            "Should NOT contain batch-003 ID (partial)");
    }
    
    @Test
    void testFlush_PartialBatch_NoCompletedBatchIds() {
        // Given: One batch partially flushed
        List<TickDataChunk> chunks = createTestChunks("run-001", 0, 4);
        TopicMessage<BatchInfo, String> msg = createTestMessage("batch-001");
        
        component = new ChunkBufferingComponent(2, 5000);  // insertBatchSize=2
        component.addChunksFromBatch(chunks, "batch-001", msg);
        
        // When: Flush only 2 chunks (partial)
        ChunkBufferingComponent.FlushResult<String> firstResult = component.flush();
        
        // Then: completedBatchIds should be empty
        assertEquals(2, firstResult.chunks().size(), "Should flush 2 chunks");
        assertTrue(firstResult.completedMessages().isEmpty(), 
            "Partial batch should NOT have completed messages");
        assertTrue(firstResult.completedBatchIds().isEmpty(), 
            "Partial batch should NOT have completed batch IDs");
        
        // When: Flush remaining chunks
        ChunkBufferingComponent.FlushResult<String> secondFlush = component.flush();
        
        // Then: Now batch ID should be in completedBatchIds
        assertEquals(1, secondFlush.completedMessages().size());
        assertEquals(1, secondFlush.completedBatchIds().size(), 
            "Completed batch should have batch ID");
        assertTrue(secondFlush.completedBatchIds().contains("batch-001"), 
            "Should contain batch-001 ID after completion");
    }
    
    // ========== Helper Methods ==========
    
    private List<TickDataChunk> createTestChunks(String runId, long startTick, int count) {
        List<TickDataChunk> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long chunkStartTick = startTick + (i * 100L);
            // Create a minimal chunk with a snapshot
            TickData snapshot = TickData.newBuilder()
                .setSimulationRunId(runId)
                .setTickNumber(chunkStartTick)
                .setCaptureTimeMs(System.currentTimeMillis())
                .build();
            
            TickDataChunk chunk = TickDataChunk.newBuilder()
                .setSimulationRunId(runId)
                .setFirstTick(chunkStartTick)
                .setLastTick(chunkStartTick + 99)
                .setTickCount(100)  // Each chunk contains 100 ticks
                .setSnapshot(snapshot)
                .build();
            
            chunks.add(chunk);
        }
        return chunks;
    }
    
    private TopicMessage<BatchInfo, String> createTestMessage(String batchId) {
        BatchInfo batchInfo = BatchInfo.newBuilder()
            .setSimulationRunId("test-run")
            .setStoragePath(batchId)
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
