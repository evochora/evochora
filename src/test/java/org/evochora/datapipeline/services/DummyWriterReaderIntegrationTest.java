package org.evochora.datapipeline.services;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class DummyWriterReaderIntegrationTest {

    @TempDir
    Path tempDir;

    private ServiceManager serviceManager;

    @AfterEach
    void tearDown() {
        if (serviceManager != null) {
            serviceManager.stopAll();
        }
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*")
    void testWriterAndReaderWorkTogether() {
        // Each write produces chunksPerWrite chunks, each chunk containing ticksPerChunk ticks
        final int chunksPerWrite = 10;
        final int maxWrites = 5;
        final long totalChunks = (long) chunksPerWrite * maxWrites;

        String rootDirectory = tempDir.toAbsolutePath().toString().replace("\\", "\\\\");

        String configString = String.format("""
            pipeline {
              autoStart = false // We will start manually
              resources {
                "storage-main" {
                  className = "org.evochora.datapipeline.resources.storage.FileSystemStorageResource"
                  options { rootDirectory = "%s" }
                }
              }
              services {
                "dummy-writer" {
                  className = "org.evochora.datapipeline.services.DummyWriterService"
                  resources { storage = "storage-write:storage-main" }
                  options {
                    intervalMs = 1
                    messagesPerWrite = %d
                    ticksPerChunk = 10
                    maxWrites = %d
                    keyPrefix = "integration_test"
                  }
                }
                "dummy-reader" {
                  className = "org.evochora.datapipeline.services.DummyReaderService"
                  resources { storage = "storage-read:storage-main" }
                  options {
                    keyPrefix = "integration_test"
                    intervalMs = 10 // Polls frequently
                    validateData = true
                  }
                }
              }
              // Define explicit startup sequence for clarity
              startupSequence = ["dummy-writer", "dummy-reader"]
            }
            """, rootDirectory, chunksPerWrite, maxWrites);

        Config config = ConfigFactory.parseString(configString);
        serviceManager = new ServiceManager(config);

        // Start all services
        serviceManager.startAll();

        // 1. Wait for the writer to COMPLETELY finish its work.
        // This is the most robust guarantee that the filesystem is in a stable state.
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
            assertEquals(IService.State.STOPPED, serviceManager.getServiceStatus("dummy-writer").state())
        );

        // 2. NOW, wait for the reader to catch up and process all the stable files.
        // We wait until the 'chunks_read' metric matches the total number of chunks written.
        // Note: We don't fail on transient ERROR states during polling - the reader may encounter
        // temporary I/O issues (especially in CI environments) but recover and continue processing.
        // The final assertions will verify that all data was correctly processed.
        await().atMost(30, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS).until(() -> {
            var readerStatus = serviceManager.getServiceStatus("dummy-reader");
            Number chunksRead = (readerStatus != null) ? readerStatus.metrics().get("chunks_read") : 0;
            return totalChunks == (chunksRead != null ? chunksRead.longValue() : 0L);
        });
        
        // Final assertions - verify all data was correctly processed
        var writerStatus = serviceManager.getServiceStatus("dummy-writer");
        var readerStatus = serviceManager.getServiceStatus("dummy-reader");

        assertNotNull(writerStatus);
        assertNotNull(readerStatus);

        // Verify all chunks were written and read
        assertEquals(totalChunks, writerStatus.metrics().get("chunks_written").longValue(),
            "Writer should have written all chunks");
        assertEquals(totalChunks, readerStatus.metrics().get("chunks_read").longValue(),
            "Reader should have read all chunks");
        
        // Verify data integrity - no validation errors allowed
        assertEquals(0L, readerStatus.metrics().get("validation_errors").longValue(),
            "Reader should have no validation errors");
        
        // Verify all files were processed
        assertEquals(maxWrites, readerStatus.metrics().get("files_processed").longValue(),
            "Reader should have processed all batch files");
    }
}