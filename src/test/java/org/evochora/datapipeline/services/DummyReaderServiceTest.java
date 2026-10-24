package org.evochora.datapipeline.services;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Tests how the reader treats a batch file it lists but cannot read: once is a file not readable
 * yet, which it reads again in the next round without a warning; a second failure is a read error.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class DummyReaderServiceTest {

    private static final StoragePath BATCH = StoragePath.of("test_run/raw/000/000/batch_0000000000000000000_0000000000000000099.pb");

    private IBatchStorageRead storage;
    private Map<String, List<IResource>> resources;

    @BeforeEach
    void setUp() throws IOException {
        storage = mock(IBatchStorageRead.class);
        when(storage.listBatchFiles(anyString(), any(), anyInt()))
                .thenReturn(new BatchFileListResult(List.of(BATCH), null, false));
        resources = Map.of("storage", List.of(storage));
    }

    /**
     * A file that fails once and is read in the next round counts as processed, with no read error
     * and no warning.
     */
    @Test
    void aFileThatFailsOnceIsReadAgainWithoutAnError() throws Exception {
        doThrow(new IOException("not readable yet")).doNothing().when(storage).forEachChunk(eq(BATCH), any());
        DummyReaderService reader = new DummyReaderService("reader", config(1), resources);

        reader.start();
        await().atMost(5, TimeUnit.SECONDS).until(() -> reader.getCurrentState() == IService.State.STOPPED);

        assertEquals(1L, reader.getMetrics().get("files_processed").longValue());
        assertEquals(0L, reader.getMetrics().get("read_errors").longValue());
    }

    /**
     * A file that fails again in a later round is a read error and is warned about.
     */
    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*DummyReaderService.*",
               messagePattern = "Failed to read chunk batch .*: broken")
    void aFileThatFailsAgainIsAReadError() throws Exception {
        doThrow(new IOException("broken")).when(storage).forEachChunk(eq(BATCH), any());
        DummyReaderService reader = new DummyReaderService("reader", config(1), resources);

        reader.start();
        await().atMost(5, TimeUnit.SECONDS).until(() -> reader.getMetrics().get("read_errors").longValue() >= 1);
        reader.stop();

        assertEquals(0L, reader.getMetrics().get("files_processed").longValue());
    }

    private static Config config(int maxFiles) {
        return ConfigFactory.parseString("keyPrefix = test\nintervalMs = 10\nmaxFiles = " + maxFiles);
    }
}
