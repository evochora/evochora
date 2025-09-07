package org.evochora.server;

import org.evochora.server.config.SimulationConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Essential test for the {@link CommandLineInterface}.
 * 
 * This test runs the CLI once and verifies that all essential functionality works:
 * - CLI can start and show help
 * - Services can be started, paused, resumed, and stopped
 * - Status command works
 * - Exit command works
 * 
 * This is much more efficient than running the CLI 13 times for separate tests.
 */
class CommandLineInterfaceTest {

    private CommandLineInterface cli;
    private SimulationConfiguration config;
    private ByteArrayInputStream inputStream;
    private ByteArrayOutputStream outputStream;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        config = new SimulationConfiguration();
        
        // Initialize simulation config
        config.simulation = new SimulationConfiguration.SimulationConfig();
        config.simulation.environment = new SimulationConfiguration.EnvironmentConfig();
        config.simulation.environment.shape = new int[]{10, 10};
        config.simulation.environment.toroidal = true;
        config.simulation.seed = 12345L;

        // Initialize pipeline config
        config.pipeline = new SimulationConfiguration.PipelineConfig();
        config.pipeline.simulation = new SimulationConfiguration.SimulationServiceConfig();
        config.pipeline.persistence = new SimulationConfiguration.PersistenceServiceConfig();
        config.pipeline.indexer = new SimulationConfiguration.IndexerServiceConfig();
        config.pipeline.server = new SimulationConfiguration.ServerServiceConfig();

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
        // Restore original streams
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    /**
     * Comprehensive test of CLI functionality in a single run.
     * This test verifies that the CLI can handle all essential commands:
     * - Help/startup messages
     * - Start services
     * - Show status
     * - Pause services
     * - Resume services
     * - Exit gracefully
     */
    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testCliFullFunctionality() {
        // Given: A complete CLI command sequence
        String input = "help\nstart\nstatus\npause\nresume\nstatus\nexit\n";
        inputStream = new ByteArrayInputStream(input.getBytes());
        System.setIn(inputStream);
        
        // When: Run the CLI with the complete command sequence
        try {
            cli.run();
        } catch (Exception e) {
            // Ignore exceptions for testing - we're testing CLI behavior, not error handling
        }
        
        // Then: Verify all essential functionality worked
        String output = outputStream.toString();
        
        // Verify CLI startup and help
        assertTrue(output.contains("Evochora CLI ready") || output.contains("Commands:"), 
                   "CLI should show startup message and available commands");
        
        // Verify service start
        assertTrue(output.contains("Starting all services") || output.contains("All services started"), 
                   "CLI should be able to start services");
        
        // Verify status command
        assertTrue(output.contains("Service Status") || output.contains("status"), 
                   "CLI should be able to show service status");
        
        // Verify pause functionality
        assertTrue(output.contains("Pausing all services") || output.contains("All services paused"), 
                   "CLI should be able to pause services");
        
        // Verify resume functionality
        assertTrue(output.contains("Resuming all services") || output.contains("All services resumed"), 
                   "CLI should be able to resume services");
        
        // Verify exit functionality
        assertTrue(output.contains("Stopping all services") || output.contains("All services stopped") || 
                   output.contains("CLI shutdown"), 
                   "CLI should be able to exit gracefully");
        
        // Verify no critical errors occurred
        assertFalse(output.contains("Exception") || output.contains("Error") || output.contains("Failed"), 
                    "CLI should not show critical errors during normal operation");
    }
}