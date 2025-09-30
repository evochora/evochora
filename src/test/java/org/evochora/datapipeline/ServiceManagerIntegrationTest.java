package org.evochora.datapipeline;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*")
public class ServiceManagerIntegrationTest {

    private Config createIntegrationTestConfig() {
        return ConfigFactory.parseString("""
            pipeline {
              startupSequence = ["consumer", "producer"]
              resources {
                "message-queue" {
                  className = "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue"
                  options { capacity = 100 }
                }
              }
              services {
                producer {
                  className = "org.evochora.datapipeline.services.DummyProducerService"
                  resources { output = "queue-out:message-queue" }
                  options { intervalMs = 10, maxMessages = 50 }
                }
                consumer {
                  className = "org.evochora.datapipeline.services.DummyConsumerService"
                  resources { input = "queue-in:message-queue" }
                  options { processingDelayMs = 5, maxMessages = 50 }
                }
              }
            }
        """);
    }

    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = ".* is not running or paused\\. Stop command ignored")
    void testEndToEndPipelineFlow() {
        Config config = createIntegrationTestConfig();
        ServiceManager serviceManager = new ServiceManager(config);

        serviceManager.startAll();

        await().atMost(5, TimeUnit.SECONDS).until(() ->
            serviceManager.getServiceStatus("producer").state() == IService.State.STOPPED &&
            serviceManager.getServiceStatus("consumer").state() == IService.State.STOPPED
        );

        long messagesReceived = (long) serviceManager.getServiceStatus("consumer").metrics().get("messages_received");
        assertEquals(50, messagesReceived, "Consumer should have received all messages.");

        long messagesSent = (long) serviceManager.getServiceStatus("producer").metrics().get("messages_sent");
        assertEquals(50, messagesSent, "Producer should have sent all messages.");

        assertTrue(serviceManager.isHealthy(), "Pipeline should be healthy after a successful run.");

        // The services stop on their own after reaching maxMessages.
        // Calling stopAll() again would (and should) throw an exception with the new strict lifecycle rules.
        // The await block above has already confirmed they are stopped.
    }
}