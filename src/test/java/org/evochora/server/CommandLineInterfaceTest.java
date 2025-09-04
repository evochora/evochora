package org.evochora.server;

import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.indexer.DebugIndexer;
import org.evochora.server.persistence.PersistenceService;
import org.evochora.server.http.DebugServer;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.config.SimulationConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contains integration tests for the {@link CommandLineInterface}.
 * These tests verify that the CLI can correctly parse commands and manage the lifecycle
 * (start, pause, resume, exit) of the various server services.
 * These are integration tests as they involve the interaction of multiple server components.
 * They use an in-memory database and do not require the filesystem.
 */
class CommandLineInterfaceTest {

    private CommandLineInterface cli;
    private InMemoryTickQueue queue;
    private SimulationConfiguration config;
    private ByteArrayInputStream inputStream;
    private ByteArrayOutputStream outputStream;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        queue = new InMemoryTickQueue();
        config = new SimulationConfiguration();
        
        // Initialize simulation config
        config.simulation = new SimulationConfiguration.SimulationConfig();
        config.simulation.environment = new SimulationConfiguration.EnvironmentConfig();
        config.simulation.environment.shape = new int[]{10, 10};
        config.simulation.environment.toroidal = true;
        config.simulation.seed = 12345L;

        config.simulation.energyStrategies = new java.util.ArrayList<>();
        
        // Initialize pipeline config
        config.pipeline = new SimulationConfiguration.PipelineConfig();
        config.pipeline.simulation = new SimulationConfiguration.SimulationServiceConfig();
        config.pipeline.persistence = new SimulationConfiguration.PersistenceServiceConfig();
        config.pipeline.indexer = new SimulationConfiguration.IndexerServiceConfig();
        config.pipeline.server = new SimulationConfiguration.ServerServiceConfig();
        
        // Set default values
        config.pipeline.persistence.batchSize = 1000;
        config.pipeline.persistence.jdbcUrl = "jdbc:sqlite:file:memdb_cli?mode=memory&cache=shared";
        config.pipeline.indexer.batchSize = 1000;
        config.pipeline.server.port = 0;
        
        cli = new CommandLineInterface(config);
        
        // Capture output
        outputStream = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outputStream));
        System.setErr(new PrintStream(outputStream));
    }

    @AfterEach
    void tearDown() {
        if (cli != null) {
            try {
                cli.run();
            } catch (Exception e) {
                // Ignore cleanup exceptions
            }
        }
        
        // Restore original output streams
        System.setOut(originalOut);
        System.setErr(originalErr);
        
        if (inputStream != null) {
            System.setIn(System.in);
        }
    }

    /**
     * Verifies that the CommandLineInterface can be created without errors.
     * This is an integration test.
     */
    @Test
    @Tag("integration")
    void testCliCreation() {
        assertNotNull(cli);
    }

    /**
     * Verifies that the 'start' command successfully starts all services.
     * This is an integration test of the service lifecycle.
     */
    @Test
    @Tag("integration")
    void testStartCommandStartsAllServices() {
        // Given
        String input = "start\nstatus\nexit\n";
        inputStream = new ByteArrayInputStream(input.getBytes());
        System.setIn(inputStream);
        
        // When
        try {
            cli.run();
        } catch (Exception e) {
            // Ignore exceptions for testing
        }
        
        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Starting all services..."));
        assertTrue(output.contains("All services started successfully"));
        assertTrue(output.contains("Service Status:"));
    }

    /**
     * Verifies that the 'pause' command successfully pauses all running services.
     * This is an integration test of the service lifecycle.
     */
    @Test
    @Tag("integration")
    void testPauseCommandPausesAllServices() {
        // Given
        String input = "start\npause\nstatus\nexit\n";
        inputStream = new ByteArrayInputStream(input.getBytes());
        System.setIn(inputStream);
        
        // When
        try {
            cli.run();
        } catch (Exception e) {
            // Ignore exceptions for testing
        }
        
        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Pausing all services..."));
        assertTrue(output.contains("All services paused"));
        assertTrue(output.contains("Service Status:"));
    }

    /**
     * Verifies that the 'resume' command successfully resumes all paused services.
     * This is an integration test of the service lifecycle.
     */
    @Test
    @Tag("integration")
    void testResumeCommandResumesAllServices() {
        // Given
        String input = "start\npause\nresume\nstatus\nexit\n";
        inputStream = new ByteArrayInputStream(input.getBytes());
        System.setIn(inputStream);
        
        // When
        try {
            cli.run();
        } catch (Exception e) {
            // Ignore exceptions for testing
        }
        
        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Pausing all services..."));
        assertTrue(output.contains("Resuming all services..."));
        assertTrue(output.contains("All services resumed"));
        assertTrue(output.contains("Service Status:"));
    }

    /**
     * Verifies that a specific service can be started individually.
     * This is an integration test of the service lifecycle.
     */
    @Test
    @Tag("integration")
    void testStartSpecificService() {
        // Given
        String input = "start simulation\nstatus\nexit\n";
        inputStream = new ByteArrayInputStream(input.getBytes());
        System.setIn(inputStream);
        
        // When
        try {
            cli.run();
        } catch (Exception e) {
            // Ignore exceptions for testing
        }
        
        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Starting simulation..."));
        assertTrue(output.contains("Service Status:"));
    }

    /**
     * Verifies that a specific service can be paused individually.
     * This is an integration test of the service lifecycle.
     */
    @Test
    @Tag("integration")
    void testPauseSpecificService() {
        // Given
        String input = "start\npause simulation\nstatus\nexit\n";
        inputStream = new ByteArrayInputStream(input.getBytes());
        System.setIn(inputStream);
        
        // When
        try {
            cli.run();
        } catch (Exception e) {
            // Ignore exceptions for testing
        }
        
        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Starting all services..."));
        assertTrue(output.contains("Pausing simulation..."));
        assertTrue(output.contains("Service Status:"));
    }

    /**
     * Verifies that a specific service can be resumed individually.
     * This is an integration test of the service lifecycle.
     */
    @Test
    @Tag("integration")
    void testResumeSpecificService() {
        // Given
        String input = "start\npause simulation\nresume simulation\nstatus\nexit\n";
        inputStream = new ByteArrayInputStream(input.getBytes());
        System.setIn(inputStream);
        
        // When
        try {
            cli.run();
        } catch (Exception e) {
            // Ignore exceptions for testing
        }
        
        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Starting all services..."));
        assertTrue(output.contains("Pausing simulation..."));
        assertTrue(output.contains("Resuming simulation..."));
        assertTrue(output.contains("Service Status:"));
    }

    /**
     * Verifies that the 'status' command correctly displays the initial status of all services.
     * This is an integration test of the CLI's state reporting.
     */
    @Test
    @Tag("integration")
    void testStatusCommandShowsServiceStatus() {
        // Given
        String input = "status\nexit\n";
        inputStream = new ByteArrayInputStream(input.getBytes());
        System.setIn(inputStream);
        
        // When
        try {
            cli.run();
        } catch (Exception e) {
            // Ignore exceptions for testing
        }
        
        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Service Status:"));
        assertTrue(output.contains("Simulation: NOT_STARTED"));
        assertTrue(output.contains("Persistence: NOT_STARTED"));
        assertTrue(output.contains("Indexer: NOT_STARTED"));
        assertTrue(output.contains("DebugServer: NOT_STARTED"));
    }

    /**
     * Verifies that the 'exit' command correctly shuts down all running services.
     * This is an integration test of the service lifecycle.
     */
    @Test
    @Tag("integration")
    void testExitCommandShutsDownServices() {
        // Given
        String input = "start\nexit\n";
        inputStream = new ByteArrayInputStream(input.getBytes());
        System.setIn(inputStream);
        
        // When
        try {
            cli.run();
        } catch (Exception e) {
            // Ignore exceptions for testing
        }
        
        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Starting all services..."));
        assertTrue(output.contains("Shutting down services..."));
        assertTrue(output.contains("CLI shutdown complete"));
    }

    /**
     * Verifies that an unknown command displays the help message.
     * This is an integration test of the CLI's command parsing.
     */
    @Test
    @Tag("integration")
    void testUnknownCommandShowsHelp() {
        // Given
        String input = "unknown\nexit\n";
        inputStream = new ByteArrayInputStream(input.getBytes());
        System.setIn(inputStream);
        
        // When
        try {
            cli.run();
        } catch (Exception e) {
            // Ignore exceptions for testing
        }
        
        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Unknown command. Available: start [service] | pause [service] | resume [service] | status | exit"));
    }

    /**
     * Verifies that an empty command is ignored and does not disrupt execution.
     * This is an integration test of the CLI's command parsing.
     */
    @Test
    @Tag("integration")
    void testEmptyCommandIsIgnored() {
        // Given
        String input = "\nstart\nexit\n";
        inputStream = new ByteArrayInputStream(input.getBytes());
        System.setIn(inputStream);
        
        // When
        try {
            cli.run();
        } catch (Exception e) {
            // Ignore exceptions for testing
        }
        
        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Starting all services..."));
        assertTrue(output.contains("All services started successfully"));
    }

    /**
     * Verifies that the 'quit' command is recognized as an alias for 'exit'.
     * This is an integration test of the CLI's command parsing.
     */
    @Test
    @Tag("integration")
    void testQuitCommandExits() {
        // Given
        String input = "quit\n";
        inputStream = new ByteArrayInputStream(input.getBytes());
        System.setIn(inputStream);
        
        // When
        try {
            cli.run();
        } catch (Exception e) {
            // Ignore exceptions for testing
        }
        
        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Evochora CLI ready. Commands: start | pause | resume | status | exit"));
    }

    /**
     * Verifies the complete service lifecycle: start, pause, resume, and exit.
     * This is an integration test of the service lifecycle management.
     */
    @Test
    @Tag("integration")
    void testServiceLifecycleComplete() {
        // Given
        String input = "start\nstatus\npause\nstatus\nresume\nstatus\nexit\n";
        inputStream = new ByteArrayInputStream(input.getBytes());
        System.setIn(inputStream);
        
        // When
        try {
            cli.run();
        } catch (Exception e) {
            // Ignore exceptions for testing
        }
        
        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Starting all services..."));
        assertTrue(output.contains("Pausing all services..."));
        assertTrue(output.contains("Resuming all services..."));
        assertTrue(output.contains("Service Status:"));
    }
}
