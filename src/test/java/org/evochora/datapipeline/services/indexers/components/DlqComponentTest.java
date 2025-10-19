package org.evochora.datapipeline.services.indexers.components;

import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.SystemContracts;
import org.evochora.datapipeline.api.resources.IRetryTracker;
import org.evochora.datapipeline.api.resources.queues.IDeadLetterQueueResource;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DlqComponent.
 * <p>
 * Tests focus on error handling, validation, and safe defaults.
 * Integration tests verify end-to-end behavior with real indexers.
 */
@Tag("unit")
class DlqComponentTest {
    
    @Test
    void testShouldMoveToDlq_BelowLimit() throws Exception {
        // Given: maxRetries=3, current retries=2
        IRetryTracker tracker = mock(IRetryTracker.class);
        when(tracker.incrementAndGetRetryCount("batch-001")).thenReturn(2);
        
        @SuppressWarnings("unchecked")
        IDeadLetterQueueResource<BatchInfo> dlq = mock(IDeadLetterQueueResource.class);
        
        DlqComponent<BatchInfo, String> component = 
            new DlqComponent<>(tracker, dlq, 3, "DummyIndexer");
        
        // When: Check if should move to DLQ
        boolean should = component.shouldMoveToDlq("batch-001");
        
        // Then: Returns false (2 <= 3)
        assertThat(should).isFalse();
        verify(tracker).incrementAndGetRetryCount("batch-001");
    }
    
    @Test
    void testShouldMoveToDlq_AtLimit() throws Exception {
        // Given: maxRetries=3, current retries=4
        IRetryTracker tracker = mock(IRetryTracker.class);
        when(tracker.incrementAndGetRetryCount("batch-001")).thenReturn(4);
        
        @SuppressWarnings("unchecked")
        IDeadLetterQueueResource<BatchInfo> dlq = mock(IDeadLetterQueueResource.class);
        
        DlqComponent<BatchInfo, String> component = 
            new DlqComponent<>(tracker, dlq, 3, "DummyIndexer");
        
        // When: Check if should move to DLQ
        boolean should = component.shouldMoveToDlq("batch-001");
        
        // Then: Returns true (4 > 3)
        assertThat(should).isTrue();
    }
    
    @Test
    void testShouldMoveToDlq_TrackerFailure() throws Exception {
        // Given: Tracker throws exception
        IRetryTracker tracker = mock(IRetryTracker.class);
        when(tracker.incrementAndGetRetryCount(anyString()))
            .thenThrow(new RuntimeException("Tracker error"));
        
        @SuppressWarnings("unchecked")
        IDeadLetterQueueResource<BatchInfo> dlq = mock(IDeadLetterQueueResource.class);
        
        DlqComponent<BatchInfo, String> component = 
            new DlqComponent<>(tracker, dlq, 3, "DummyIndexer");
        
        // When: Check if should move to DLQ (tracker fails)
        boolean should = component.shouldMoveToDlq("batch-001");
        
        // Then: Returns false (safe default - retry again)
        assertThat(should).isFalse();
    }
    
    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = "Moved message to DLQ after .* retries: .*")
    void testMoveToDlq_Success() throws Exception {
        // Given: Valid setup
        IRetryTracker tracker = mock(IRetryTracker.class);
        when(tracker.getRetryCount("batch-001")).thenReturn(3);
        
        @SuppressWarnings("unchecked")
        IDeadLetterQueueResource<BatchInfo> dlq = mock(IDeadLetterQueueResource.class);
        
        DlqComponent<BatchInfo, String> component = 
            new DlqComponent<>(tracker, dlq, 3, "DummyIndexer");
        
        BatchInfo batchInfo = BatchInfo.newBuilder()
            .setSimulationRunId("test-run")
            .setStoragePath("batch-001")
            .setTickStart(0)
            .setTickEnd(999)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-001", "test-consumer", "ack-001"
        );
        
        Exception error = new RuntimeException("Parse error");
        
        // When: Move to DLQ
        component.moveToDlq(message, error, "batch-001");
        
        // Then: DLQ message created and sent
        ArgumentCaptor<SystemContracts.DeadLetterMessage> captor = 
            ArgumentCaptor.forClass(SystemContracts.DeadLetterMessage.class);
        verify(dlq).put(captor.capture());
        
        SystemContracts.DeadLetterMessage dlqMsg = captor.getValue();
        assertThat(dlqMsg.getMessageType()).contains("BatchInfo");
        assertThat(dlqMsg.getFailureReason()).contains("RuntimeException");
        assertThat(dlqMsg.getFailureReason()).contains("Parse error");
        assertThat(dlqMsg.getRetryCount()).isEqualTo(3);
        assertThat(dlqMsg.getSourceService()).isEqualTo("DummyIndexer");
        
        // Verify tracker was marked as moved to DLQ
        verify(tracker).markMovedToDlq("batch-001");
    }
    
    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = "Moved message to DLQ after .* retries: .*")
    void testMoveToDlq_CapturesStackTrace() throws Exception {
        // Given: Valid setup
        IRetryTracker tracker = mock(IRetryTracker.class);
        when(tracker.getRetryCount("batch-001")).thenReturn(5);
        
        @SuppressWarnings("unchecked")
        IDeadLetterQueueResource<BatchInfo> dlq = mock(IDeadLetterQueueResource.class);
        
        DlqComponent<BatchInfo, String> component = 
            new DlqComponent<>(tracker, dlq, 3, "DummyIndexer");
        
        BatchInfo batchInfo = BatchInfo.newBuilder()
            .setSimulationRunId("test-run")
            .setStoragePath("batch-001")
            .setTickStart(0)
            .setTickEnd(999)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-001", "test-consumer", "ack-001"
        );
        
        Exception error = new RuntimeException("Test error");
        
        // When: Move to DLQ
        component.moveToDlq(message, error, "batch-001");
        
        // Then: Stack trace should be captured (limited to 10 lines)
        ArgumentCaptor<SystemContracts.DeadLetterMessage> captor = 
            ArgumentCaptor.forClass(SystemContracts.DeadLetterMessage.class);
        verify(dlq).put(captor.capture());
        
        SystemContracts.DeadLetterMessage dlqMsg = captor.getValue();
        assertThat(dlqMsg.getStackTraceLinesList()).isNotEmpty();
        assertThat(dlqMsg.getStackTraceLinesList().size()).isLessThanOrEqualTo(10);
    }
    
    @Test
    void testResetRetryCount_Success() throws Exception {
        // Given: Valid setup
        IRetryTracker tracker = mock(IRetryTracker.class);
        
        @SuppressWarnings("unchecked")
        IDeadLetterQueueResource<BatchInfo> dlq = mock(IDeadLetterQueueResource.class);
        
        DlqComponent<BatchInfo, String> component = 
            new DlqComponent<>(tracker, dlq, 3, "DummyIndexer");
        
        // When: Reset retry count
        component.resetRetryCount("batch-001");
        
        // Then: Tracker was called
        verify(tracker).resetRetryCount("batch-001");
    }
    
    @Test
    void testResetRetryCount_TrackerFailure() throws Exception {
        // Given: Tracker throws exception on reset
        IRetryTracker tracker = mock(IRetryTracker.class);
        doThrow(new RuntimeException("Tracker error")).when(tracker).resetRetryCount(anyString());
        
        @SuppressWarnings("unchecked")
        IDeadLetterQueueResource<BatchInfo> dlq = mock(IDeadLetterQueueResource.class);
        
        DlqComponent<BatchInfo, String> component = 
            new DlqComponent<>(tracker, dlq, 3, "DummyIndexer");
        
        // When: Reset retry count (tracker fails)
        // Then: Should NOT throw exception (reset is not critical)
        component.resetRetryCount("batch-001");
        
        // Verify tracker was called (even though it failed)
        verify(tracker).resetRetryCount("batch-001");
    }
    
    @Test
    void testConstructor_NullTracker() {
        // Given: Valid DLQ
        @SuppressWarnings("unchecked")
        IDeadLetterQueueResource<BatchInfo> dlq = mock(IDeadLetterQueueResource.class);
        
        // When/Then: Constructor rejects null tracker
        assertThatThrownBy(() -> new DlqComponent<>(null, dlq, 3, "DummyIndexer"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("RetryTracker must not be null");
    }
    
    @Test
    void testConstructor_NullDlq() {
        // Given: Valid tracker
        IRetryTracker tracker = mock(IRetryTracker.class);
        
        // When/Then: Constructor rejects null DLQ
        assertThatThrownBy(() -> new DlqComponent<BatchInfo, String>(tracker, null, 3, "DummyIndexer"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DLQ must not be null");
    }
    
    @Test
    void testConstructor_InvalidMaxRetries() {
        // Given: Valid tracker and DLQ
        IRetryTracker tracker = mock(IRetryTracker.class);
        
        @SuppressWarnings("unchecked")
        IDeadLetterQueueResource<BatchInfo> dlq = mock(IDeadLetterQueueResource.class);
        
        // When/Then: Constructor rejects zero/negative maxRetries
        assertThatThrownBy(() -> new DlqComponent<>(tracker, dlq, 0, "DummyIndexer"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("MaxRetries must be positive");
        
        assertThatThrownBy(() -> new DlqComponent<>(tracker, dlq, -1, "DummyIndexer"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("MaxRetries must be positive");
    }
    
    @Test
    void testConstructor_NullIndexerName() {
        // Given: Valid tracker and DLQ
        IRetryTracker tracker = mock(IRetryTracker.class);
        
        @SuppressWarnings("unchecked")
        IDeadLetterQueueResource<BatchInfo> dlq = mock(IDeadLetterQueueResource.class);
        
        // When/Then: Constructor rejects null indexer name
        assertThatThrownBy(() -> new DlqComponent<>(tracker, dlq, 3, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Indexer name must not be null or blank");
    }
    
    @Test
    void testConstructor_BlankIndexerName() {
        // Given: Valid tracker and DLQ
        IRetryTracker tracker = mock(IRetryTracker.class);
        
        @SuppressWarnings("unchecked")
        IDeadLetterQueueResource<BatchInfo> dlq = mock(IDeadLetterQueueResource.class);
        
        // When/Then: Constructor rejects blank indexer name
        assertThatThrownBy(() -> new DlqComponent<>(tracker, dlq, 3, "   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Indexer name must not be null or blank");
    }
}

