package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
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

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        final int messagesPerWrite = 50;
        final int maxWrites = 10;
        final long totalMessages = (long) messagesPerWrite * maxWrites;

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
            """, rootDirectory, messagesPerWrite, maxWrites);

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
        // We wait until the 'messages_read' metric matches the total number of messages written.
        await().atMost(30, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS).until(() -> {
            var readerStatus = serviceManager.getServiceStatus("dummy-reader");
            if (readerStatus != null && readerStatus.state() == IService.State.ERROR) {
                fail("Reader service entered ERROR state.");
            }
            Number messagesRead = (readerStatus != null) ? readerStatus.metrics().get("messages_read") : 0;
            return totalMessages == (messagesRead != null ? messagesRead.longValue() : 0L);
        });
        
        // Final assertions
        var writerStatus = serviceManager.getServiceStatus("dummy-writer");
        var readerStatus = serviceManager.getServiceStatus("dummy-reader");

        assertNotNull(writerStatus);
        assertNotNull(readerStatus);

        assertEquals(totalMessages, writerStatus.metrics().get("messages_written").longValue());
        assertEquals(totalMessages, readerStatus.metrics().get("messages_read").longValue());
        assertEquals(0L, readerStatus.metrics().get("validation_errors").longValue());
        assertEquals(maxWrites, readerStatus.metrics().get("files_processed").longValue());
    }
}