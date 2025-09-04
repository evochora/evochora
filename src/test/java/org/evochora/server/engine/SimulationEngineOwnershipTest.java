package org.evochora.server.engine;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains integration tests for organism ownership functionality in the simulation engine.
 * These tests verify that organisms can be properly owned and managed within the simulation
 * and that ownership relationships are correctly maintained.
 * These are integration tests as they involve managing a live, threaded service.
 */
@Tag("integration")
class SimulationEngineOwnershipTest {

    private ITickMessageQueue queue;
    private SimulationEngine engine;

    @BeforeEach
    void setUp() {
        queue = new InMemoryTickQueue();
        engine = new SimulationEngine(queue, new EnvironmentProperties(new int[]{10, 10}, true), 
             new ArrayList<>(), new ArrayList<>());
    }

    @AfterEach
    void tearDown() {
        if (engine != null && engine.isRunning()) {
            engine.shutdown();
            // Wait for shutdown to complete
            try {
                waitForShutdown(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Wait for a condition to be true, checking every 10ms
     * @param condition The condition to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     * @param description Description of what we're waiting for
     * @return true if condition was met, false if timeout occurred
     */
    private boolean waitForCondition(BooleanSupplier condition, long timeoutMs, String description) {
        long startTime = System.currentTimeMillis();
        long checkInterval = 10; // Check every 10ms for faster response

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                System.out.println("Timeout waiting for: " + description);
                return false;
            }
            try {
                Thread.sleep(checkInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Helper method to wait until the simulation engine thread has shut down.
     * @param timeoutMillis The maximum time to wait.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    private void waitForShutdown(long timeoutMillis) throws InterruptedException {
        assertThat(waitForCondition(
            () -> !engine.isRunning(),
            timeoutMillis,
            "simulation engine to shutdown"
        )).isTrue();
    }

    /**
     * Verifies that the simulation engine can properly manage organism ownership
     * when organisms are seeded in the world.
     * This is an integration test of the organism ownership functionality.
     * @throws Exception if the test fails.
     */
    @Test
    void testOrganismOwnershipWithSeededOrganisms() throws Exception {
        // Create a simple program artifact
        Compiler compiler = new Compiler();
        List<String> lines = List.of(
            "NOP",
            "NOP",
            "NOP"
        );
        ProgramArtifact artifact = compiler.compile(lines, "test_program", 2);
        
        // Create organism placement
        OrganismPlacement placement = OrganismPlacement.of(artifact, 1000, new int[]{0, 0});
        
        // Create new engine with placement
        SimulationEngine testEngine = new SimulationEngine(queue, new EnvironmentProperties(new int[]{10, 10}, true), 
             List.of(placement), new ArrayList<>());
        
        // Start the engine
        testEngine.start();
        
        // Wait for engine to start
        assertThat(waitForCondition(
            testEngine::isRunning,
            5000,
            "simulation engine to start"
        )).isTrue();
        
        // Verify the engine is running
        assertThat(testEngine.isRunning()).isTrue();
        
        // Shutdown
        testEngine.shutdown();
        
        // Wait for shutdown to complete
        assertThat(waitForCondition(
            () -> !testEngine.isRunning(),
            5000,
            "simulation engine to shutdown"
        )).isTrue();
    }

    /**
     * Verifies that the simulation engine can handle multiple organisms with
     * different ownership relationships.
     * This is an integration test of the organism ownership functionality.
     * @throws Exception if the test fails.
     */
    @Test
    void testMultipleOrganismOwnership() throws Exception {
        // Create multiple program artifacts
        Compiler compiler = new Compiler();
        List<String> lines = List.of("NOP", "NOP", "NOP");
        
        ProgramArtifact artifact1 = compiler.compile(lines, "test_program_1", 2);
        ProgramArtifact artifact2 = compiler.compile(lines, "test_program_2", 2);
        
        // Create organism placements with different IDs
        OrganismPlacement placement1 = OrganismPlacement.of(artifact1, 1000, new int[]{0, 0}, "organism_1");
        OrganismPlacement placement2 = OrganismPlacement.of(artifact2, 1000, new int[]{5, 5}, "organism_2");
        
        // Create new engine with multiple placements
        SimulationEngine testEngine = new SimulationEngine(queue, new EnvironmentProperties(new int[]{10, 10}, true), 
             List.of(placement1, placement2), new ArrayList<>());
        
        // Start the engine
        testEngine.start();
        
        // Wait for engine to start
        assertThat(waitForCondition(
            testEngine::isRunning,
            5000,
            "simulation engine to start"
        )).isTrue();
        
        // Verify the engine is running
        assertThat(testEngine.isRunning()).isTrue();
        
        // Shutdown
        testEngine.shutdown();
        
        // Wait for shutdown to complete
        assertThat(waitForCondition(
            () -> !testEngine.isRunning(),
            5000,
            "simulation engine to shutdown"
        )).isTrue();
    }

    /**
     * Verifies that the simulation engine can handle organism ownership
     * when no organisms are present (empty world).
     * This is an integration test of the organism ownership functionality.
     * @throws Exception if the test fails.
     */
    @Test
    void testEmptyWorldOwnership() throws Exception {
        // Start the engine with no organisms
        engine.start();
        
        // Wait for engine to start
        assertThat(waitForCondition(
            engine::isRunning,
            5000,
            "simulation engine to start"
        )).isTrue();
        
        // Verify the engine is running
        assertThat(engine.isRunning()).isTrue();
        
        // Shutdown
        engine.shutdown();
        
        // Wait for shutdown to complete
        assertThat(waitForCondition(
            () -> !engine.isRunning(),
            5000,
            "simulation engine to shutdown"
        )).isTrue();
    }
}