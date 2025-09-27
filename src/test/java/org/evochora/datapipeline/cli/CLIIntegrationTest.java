package org.evochora.datapipeline.cli;

import org.awaitility.Awaitility;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.AllowLogs;
import org.evochora.junit.extensions.logging.LogLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Tag("integration")
@ExtendWith({MockitoExtension.class, LogWatchExtension.class})
class CLIIntegrationTest {

    @TempDir
    Path tempDir;
    private Path confFile;

    @Mock
    private ServiceManager mockServiceManager;

    @BeforeEach
    void setUp() throws IOException {
        confFile = tempDir.resolve("test.conf");
        List<String> lines = List.of(
                "pipeline {",
                "  startupSequence = [\"consumer\", \"producer\"]",
                "  resources {",
                "    test-queue { className = \"org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue\", options { capacity = 10 } }",
                "  }",
                "  services {",
                "    consumer { className = \"org.evochora.datapipeline.services.DummyConsumerService\", resources.input = \"queue-in:test-queue\" }",
                "    producer { className = \"org.evochora.datapipeline.services.DummyProducerService\", resources.output = \"queue-out:test-queue\", options.intervalMs = 10 }",
                "  }",
                "}",
                "logging { level = \"INFO\" }"
        );
        Files.write(confFile, lines);
    }

    private String executeAndCapture(CommandLine cmd, String... args) {
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));
        cmd.setErr(new PrintWriter(System.err)); // Redirect error stream to avoid polluting test output
        cmd.execute(args);
        return sw.toString();
    }

    @Test
    @AllowLogs({
            @AllowLog(level = LogLevel.INFO, loggerPattern = ".*", messagePattern = ".*")
    })
    void testFullPipelineLifecycle() {
        CommandLine cmd = new CommandLine(new CommandLineInterface());

        // Using real ServiceManager for this test
        executeAndCapture(cmd, "-c", confFile.toString(), "start");

        String statusOutput = executeAndCapture(cmd, "-c", confFile.toString(), "status");
        assertThat(statusOutput).contains("Service: producer, State: RUNNING");
        assertThat(statusOutput).contains("Service: consumer, State: RUNNING");

        executeAndCapture(cmd, "-c", confFile.toString(), "stop");
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            String stoppedStatus = executeAndCapture(cmd, "-c", confFile.toString(), "status");
            assertThat(stoppedStatus).contains("Service: producer, State: STOPPED");
            assertThat(stoppedStatus).contains("Service: consumer, State: STOPPED");
        });
    }

    @Test
    void testJsonStatusOutputWithMock() {
        // Arrange
        Map<String, ServiceStatus> mockStatus = new HashMap<>();
        mockStatus.put("mock-service", new ServiceStatus(IService.State.RUNNING, Collections.emptyMap(), Collections.emptyList(), Collections.emptyList()));
        when(mockServiceManager.getAllServiceStatus()).thenReturn(mockStatus);

        CommandLineInterface cli = new CommandLineInterface(mockServiceManager);
        CommandLine cmd = new CommandLine(cli);

        // Act
        String output = executeAndCapture(cmd, "status", "--json");

        // Assert
        assertThat(output.trim())
                .startsWith("{")
                .endsWith("}")
                .contains("\"mock-service\": {")
                .contains("\"state\": \"RUNNING\"");
    }
}