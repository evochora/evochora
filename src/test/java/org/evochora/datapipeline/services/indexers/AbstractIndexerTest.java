package org.evochora.datapipeline.services.indexers;

import com.google.protobuf.Message;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.MetadataInfo;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.WARN, messagePattern = ".*initialized WITHOUT topic.*")
class AbstractIndexerTest {

    @Mock
    private IBatchStorageRead mockStorage;

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
            "storage", List.of(mockStorage)
        );
    }

    @Test
    void discoverRunId_withConfiguredRunId_returnsConfiguredId() throws InterruptedException, TimeoutException {
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
    void run_callsIndexRunWithDiscoveredId() {
        Config config = ConfigFactory.parseString("runId = \"test-run-configured\"");
        TestIndexer<MetadataInfo, ?> indexer = new TestIndexer<>("test", config, resources);

        indexer.start();

        assertDoesNotThrow(() -> await().atMost(1, java.util.concurrent.TimeUnit.SECONDS)
                .until(() -> indexer.getCurrentState() == IService.State.STOPPED));

        assertEquals("test-run-configured", indexer.indexedRunId);
    }
}