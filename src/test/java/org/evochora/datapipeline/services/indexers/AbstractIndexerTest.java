package org.evochora.datapipeline.services.indexers;

import com.google.protobuf.Message;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.MetadataInfo;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.ISchemaAwareDatabase;
import org.evochora.datapipeline.api.resources.storage.IResourceBatchStorageRead;
import org.evochora.datapipeline.api.resources.topics.IResourceTopicReader;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.evochora.datapipeline.api.resources.storage.StoragePath;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@ExtendWith(LogWatchExtension.class)
class AbstractIndexerTest {

    @Mock
    private IResourceBatchStorageRead mockStorage;

    // Required by AbstractIndexer, but never reached: these tests stop before the topic loop.
    @Mock
    private IResourceTopicReader<MetadataInfo, ?> mockTopic;

    private Map<String, List<IResource>> resources;

    // A concrete implementation for testing the abstract class.
    private static class TestIndexer<T extends Message, ACK> extends AbstractIndexer<T, ACK> {
        private String indexedRunId = null;

        protected TestIndexer(String name, Config options, Map<String, List<IResource>> resources) {
            super(name, options, resources);
        }

        @Override
        protected void indexRun(String runId) {
            this.indexedRunId = runId;
        }
    }

    @BeforeEach
    void setUp() {
        resources = Map.of(
            "storage", List.of(mockStorage),
            "topic", List.of(mockTopic)
        );
    }

    @Test
    void discoverRunId_withConfiguredRunId_returnsConfiguredId() throws InterruptedException, TimeoutException, IOException {
        when(mockStorage.findMetadataPath("test-run-configured"))
            .thenReturn(Optional.of(StoragePath.of("test-run-configured/raw/metadata.pb")));

        Config config = ConfigFactory.parseString("runId = \"test-run-configured\"");
        TestIndexer<MetadataInfo, ?> indexer = new TestIndexer<>("test", config, resources);

        String runId = indexer.discoverRunId();

        assertEquals("test-run-configured", runId);
    }

    @Test
    void discoverRunId_withConfiguredRunId_throwsWhenRunNotFound() throws IOException {
        when(mockStorage.findMetadataPath("nonexistent-run"))
            .thenReturn(Optional.empty());

        Config config = ConfigFactory.parseString("runId = \"nonexistent-run\"");
        TestIndexer<MetadataInfo, ?> indexer = new TestIndexer<>("test", config, resources);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, indexer::discoverRunId);
        assertTrue(ex.getMessage().contains("nonexistent-run"));
        assertTrue(ex.getMessage().contains("No simulation data found"));
    }

    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = ".*Could not validate runId.*")
    void discoverRunId_withConfiguredRunId_continuesOnStorageError() throws InterruptedException, TimeoutException, IOException {
        when(mockStorage.findMetadataPath("test-run-configured"))
            .thenThrow(new IOException("Storage unavailable"));

        Config config = ConfigFactory.parseString("runId = \"test-run-configured\"");
        TestIndexer<MetadataInfo, ?> indexer = new TestIndexer<>("test", config, resources);

        String runId = indexer.discoverRunId();

        assertEquals("test-run-configured", runId);
    }

    @Test
    void discoverRunId_timestampBased_discoversRun() throws IOException, InterruptedException, TimeoutException {
        Config config = ConfigFactory.empty();
        TestIndexer<MetadataInfo, ?> indexer = new TestIndexer<>("test", config, resources);

        when(mockStorage.listRunIds(any(Instant.class))).thenReturn(List.of("discovered-run-id"));

        String runId = indexer.discoverRunId();

        assertEquals("discovered-run-id", runId);
    }

    @Test
    void discoverRunId_timestampBased_pollsUntilRunAppears() throws IOException, InterruptedException, TimeoutException {
        Config config = ConfigFactory.parseString("pollIntervalMs = 10");
        TestIndexer<MetadataInfo, ?> indexer = new TestIndexer<>("test", config, resources);

        when(mockStorage.listRunIds(any(Instant.class)))
                .thenReturn(Collections.emptyList())
                .thenReturn(Collections.emptyList())
                .thenReturn(List.of("discovered-run-id"));

        String runId = indexer.discoverRunId();

        assertEquals("discovered-run-id", runId);
    }

    @Test
    void discoverRunId_timestampBased_throwsTimeoutException() throws IOException {
        Config config = ConfigFactory.parseString("maxPollDurationMs = 50, pollIntervalMs = 10");
        TestIndexer<MetadataInfo, ?> indexer = new TestIndexer<>("test", config, resources);

        when(mockStorage.listRunIds(any(Instant.class))).thenReturn(Collections.emptyList());

        assertThrows(TimeoutException.class, indexer::discoverRunId);
    }

    @Test
    void run_callsIndexRunWithDiscoveredId() throws IOException {
        when(mockStorage.findMetadataPath("test-run-configured"))
            .thenReturn(Optional.of(StoragePath.of("test-run-configured/raw/metadata.pb")));

        Config config = ConfigFactory.parseString("runId = \"test-run-configured\"");
        TestIndexer<MetadataInfo, ?> indexer = new TestIndexer<>("test", config, resources);

        indexer.start();

        assertDoesNotThrow(() -> await().atMost(1, java.util.concurrent.TimeUnit.SECONDS)
                .until(() -> indexer.getCurrentState() == IService.State.STOPPED));

        assertEquals("test-run-configured", indexer.indexedRunId);
    }

    @Test
    void aStopDoesNotInterruptTheIndexerWhileItSetsItsSchema() throws Exception {
        when(mockStorage.findMetadataPath("test-run-configured"))
            .thenReturn(Optional.of(StoragePath.of("test-run-configured/raw/metadata.pb")));
        CountDownLatch schemaEntered = new CountDownLatch(1);
        CountDownLatch schemaReleased = new CountDownLatch(1);
        AtomicReference<String> duringSchema = new AtomicReference<>();
        IResource database = mock(IResource.class, withSettings().extraInterfaces(ISchemaAwareDatabase.class));
        doAnswer(invocation -> {
            schemaEntered.countDown();
            try {
                schemaReleased.await(5, TimeUnit.SECONDS);
                duringSchema.set(Thread.currentThread().isInterrupted() ? "interrupt flag set" : "not interrupted");
            } catch (InterruptedException e) {
                duringSchema.set("interrupted");
            }
            return null;
        }).when((ISchemaAwareDatabase) database).setSimulationRun(anyString());
        Map<String, List<IResource>> withDatabase = Map.of(
            "storage", List.of(mockStorage),
            "topic", List.of(mockTopic),
            "database", List.of(database));
        TestIndexer<MetadataInfo, ?> indexer = new TestIndexer<>("test",
            ConfigFactory.parseString("runId = \"test-run-configured\""), withDatabase);

        indexer.start();
        assertTrue(schemaEntered.await(5, TimeUnit.SECONDS), "the indexer sets its schema");
        Thread stopper = new Thread(indexer::stop, "stopper");
        stopper.start();
        // Once the stopper waits for the service thread, or has already finished with it, it has
        // made its decision whether to interrupt
        await().pollDelay(0, TimeUnit.MILLISECONDS).pollInterval(5, TimeUnit.MILLISECONDS)
            .atMost(5, TimeUnit.SECONDS).until(() -> stopper.getState() == Thread.State.TIMED_WAITING
                || stopper.getState() == Thread.State.TERMINATED);
        schemaReleased.countDown();
        stopper.join(5000);

        assertEquals("not interrupted", duringSchema.get());
        assertEquals(IService.State.STOPPED, indexer.getCurrentState());
    }

    /** An indexer whose indexing waits without checking for a stop, as waiting for metadata does. */
    private static class WaitingIndexer extends AbstractIndexer<MetadataInfo, Object> {
        WaitingIndexer(Config options, Map<String, List<IResource>> resources) {
            super("waiting", options, resources);
        }

        @Override
        protected void indexRun(String runId) throws InterruptedException {
            new CountDownLatch(1).await();
        }
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStopDuringTheSchemaSetupEndsTheWaitThatFollows() throws Exception {
        when(mockStorage.findMetadataPath("test-run-configured"))
            .thenReturn(Optional.of(StoragePath.of("test-run-configured/raw/metadata.pb")));
        CountDownLatch schemaEntered = new CountDownLatch(1);
        CountDownLatch schemaReleased = new CountDownLatch(1);
        IResource database = mock(IResource.class, withSettings().extraInterfaces(ISchemaAwareDatabase.class));
        doAnswer(invocation -> {
            schemaEntered.countDown();
            schemaReleased.await(5, TimeUnit.SECONDS);
            return null;
        }).when((ISchemaAwareDatabase) database).setSimulationRun(anyString());
        Map<String, List<IResource>> withDatabase = Map.of(
            "storage", List.of(mockStorage),
            "topic", List.of(mockTopic),
            "database", List.of(database));
        // The shutdown timeout is an hour, so only an interrupt can end the indexer's wait
        WaitingIndexer indexer = new WaitingIndexer(
            ConfigFactory.parseString("runId = \"test-run-configured\"\nshutdownTimeout = 3600"), withDatabase);

        indexer.start();
        assertTrue(schemaEntered.await(5, TimeUnit.SECONDS), "the indexer sets its schema");
        Thread stopper = new Thread(indexer::stop, "stopper");
        stopper.start();
        await().pollDelay(0, TimeUnit.MILLISECONDS).pollInterval(5, TimeUnit.MILLISECONDS)
            .atMost(5, TimeUnit.SECONDS).until(() -> stopper.getState() == Thread.State.TIMED_WAITING
                || stopper.getState() == Thread.State.TERMINATED);
        schemaReleased.countDown();
        stopper.join();

        assertEquals(IService.State.STOPPED, indexer.getCurrentState());
    }
}