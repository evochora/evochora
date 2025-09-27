package org.evochora.datapipeline.cli;

import org.evochora.datapipeline.ServiceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class CommandLineInterfaceTest {

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    @TempDir
    Path tempDir;
    private Path confFile;

    @Mock
    private ServiceManager mockServiceManager;

    @BeforeEach
    void setUp() throws IOException {
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        confFile = tempDir.resolve("test.conf");
        List<String> lines = List.of(
                "pipeline {",
                "  startupSequence = [\"consumer\", \"producer\"]",
                "  resources {",
                "    test-queue {",
                "      className = \"org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue\"",
                "    }",
                "  }",
                "  services {",
                "    consumer {",
                "      className = \"org.evochora.datapipeline.services.DummyConsumerService\"",
                "      resources.input = \"queue-in:test-queue\"",
                "    }",
                "    producer {",
                "      className = \"org.evochora.datapipeline.services.DummyProducerService\"",
                "      resources.output = \"queue-out:test-queue\"",
                "    }",
                "  }",
                "}",
                "logging {",
                "  format = \"JSON\"",
                "  level = \"WARN\"",
                "  loggers {",
                "    \"org.evochora.datapipeline.ServiceManager\" = \"INFO\"",
                "    \"org.evochora.datapipeline.cli.commands.StartCommand\" = \"INFO\"",
                "    \"org.evochora.datapipeline.cli.commands.StopCommand\" = \"INFO\"",
                "    \"org.evochora.datapipeline.services\" = \"ERROR\"",
                "  }",
                "}"
        );
        Files.write(confFile, lines);
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void testStatusCommand() {
        int exitCode = new CommandLine(new CommandLineInterface()).execute("-c", confFile.toString(), "status");
        assertThat(exitCode).isEqualTo(0);
        String output = outContent.toString();
        assertThat(output).contains("Service: producer, State: STOPPED");
        assertThat(output).contains("Service: consumer, State: STOPPED");
        assertThat(output).contains("\"message\":\"Initializing ServiceManager...\"");
    }

    @Test
    void testStartAndStopCommandsWithMock() {
        CommandLineInterface cli = new CommandLineInterface(mockServiceManager);
        CommandLine cmd = new CommandLine(cli);

        // Test start
        int startExitCode = cmd.execute("start", "producer");
        assertThat(startExitCode).isEqualTo(0);
        verify(mockServiceManager).startService("producer");

        // Test stop
        int stopExitCode = cmd.execute("stop", "producer");
        assertThat(stopExitCode).isEqualTo(0);
        verify(mockServiceManager).stopService("producer");
    }

    @Test
    void testLoggingConfiguration() throws IOException {
        // Create a new config file with DEBUG level for a specific logger
        Path debugConfFile = tempDir.resolve("debug.conf");
        List<String> lines = List.of(
                "pipeline { services {} }", // Empty pipeline to avoid other logs
                "logging {",
                "  format = \"JSON\"",
                "  level = \"WARN\"",
                "  loggers {",
                "    \"org.evochora.datapipeline.ServiceManager\" = \"DEBUG\"",
                "  }",
                "}"
        );
        Files.write(debugConfFile, lines);

        int exitCode = new CommandLine(new CommandLineInterface()).execute("-c", debugConfFile.toString(), "status");
        assertThat(exitCode).isEqualTo(0);

        String output = outContent.toString();
        // ServiceManager logs "No resources configured." at DEBUG level if the resources section is missing
        assertThat(output).contains("\"message\":\"No resources configured.\"")
                         .contains("\"level\":\"DEBUG\"");
    }

    @Test
    void testInvalidCommand() {
        int exitCode = new CommandLine(new CommandLineInterface()).execute("-c", confFile.toString(), "nonexistent-command");
        assertThat(exitCode).isNotEqualTo(0);
        assertThat(errContent.toString()).contains("Unmatched argument at index");
    }
}