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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.extension.ExtendWith;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;

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

        String configString = String.format("""
            pipeline {
              autoStart = false
              resources {
                "storage-main" {
                  className = "org.evochora.datapipeline.resources.storage.FileSystemStorageResource"
                  options {
                    rootDirectory = "%s"
                  }
                }
              }
              services {
                "dummy-writer" {
                  className = "org.evochora.datapipeline.services.DummyWriterService"
                  resources {
                    storage = "storage-write:storage-main"
                  }
                  options {
                    intervalMs = 10
                    messagesPerWrite = %d
                    maxWrites = %d
                    keyPrefix = "integration_test"
                  }
                }
                "dummy-reader" {
                  className = "org.evochora.datapipeline.services.DummyReaderService"
                  resources {
                    storage = "storage-read:storage-main"
                  }
                  options {
                    keyPrefix = "integration_test"
                    intervalMs = 20
                    validateData = true
                  }
                }
              }
              startupSequence = ["dummy-writer", "dummy-reader"]
            }
            """, tempDir.toAbsolutePath().toString().replace("\\", "\\\\"), messagesPerWrite, maxWrites);

        Config config = ConfigFactory.parseString(configString);
        serviceManager = new ServiceManager(config);

        serviceManager.startAll();

        // Wait for services to be running
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(IService.State.RUNNING, serviceManager.getServiceStatus("dummy-writer").state());
            assertEquals(IService.State.RUNNING, serviceManager.getServiceStatus("dummy-reader").state());
        });

        // Wait for writer to finish
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertEquals(IService.State.STOPPED, serviceManager.getServiceStatus("dummy-writer").state())
        );

        // Wait for reader to process all messages
        await().pollInterval(250, TimeUnit.MILLISECONDS).atMost(30, TimeUnit.SECONDS).until(() -> {
            if (serviceManager.getServiceStatus("dummy-reader").state() == IService.State.ERROR) {
                fail("Reader service is in ERROR state");
            }
            Number messagesRead = serviceManager.getServiceStatus("dummy-reader").metrics().get("messages_read");
            return totalMessages == (messagesRead != null ? messagesRead.longValue() : 0L);
        });

        Map<String, Number> writerMetrics = serviceManager.getServiceStatus("dummy-writer").metrics();
        Map<String, Number> readerMetrics = serviceManager.getServiceStatus("dummy-reader").metrics();

        assertEquals(totalMessages, writerMetrics.get("messages_written").longValue());
        assertEquals(totalMessages, readerMetrics.get("messages_read").longValue());
        assertEquals(0L, readerMetrics.get("validation_errors").longValue());
        assertEquals(maxWrites, readerMetrics.get("files_processed").longValue());
    }
}