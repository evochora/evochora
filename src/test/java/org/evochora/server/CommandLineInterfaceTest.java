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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

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
        config.simulation.organisms = new SimulationConfiguration.OrganismDefinition[0];
        config.simulation.energyStrategies = new java.util.ArrayList<>();
        
        // Initialize pipeline config
        config.pipeline = new SimulationConfiguration.PipelineConfig();
        config.pipeline.simulation = new SimulationConfiguration.SimulationServiceConfig();
        config.pipeline.persistence = new SimulationConfiguration.PersistenceServiceConfig();
        config.pipeline.indexer = new SimulationConfiguration.IndexerServiceConfig();
        config.pipeline.server = new SimulationConfiguration.ServerServiceConfig();
        
        // Set default values
        config.pipeline.persistence.batchSize = 1000;
        config.pipeline.indexer.batchSize = 1000;
        config.pipeline.server.port = 7070;
        
        cli = new CommandLineInterface();
        
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

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testCliCreation() {
        assertNotNull(cli);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
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

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
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

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
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

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
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

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
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

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
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

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
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

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
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

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
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

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
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

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
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

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
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
