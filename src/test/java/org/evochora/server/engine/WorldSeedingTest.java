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

@Tag("integration")
class WorldSeedingTest {

    private ITickMessageQueue queue;
    private SimulationEngine engine;
    private final EnvironmentProperties testEnvProps = new EnvironmentProperties(new int[]{10, 10}, true);

    @BeforeEach
    void setUp() {
        queue = new InMemoryTickQueue();
        engine = new SimulationEngine(queue, testEnvProps,
             new ArrayList<>(), new ArrayList<>());
    }

    @AfterEach
    void tearDown() {
        if (engine != null && engine.isRunning()) {
            engine.shutdown();
            try {
                waitForShutdown(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean waitForCondition(BooleanSupplier condition, long timeoutMs, String description) {
        long startTime = System.currentTimeMillis();
        long checkInterval = 10;

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

    private void waitForShutdown(long timeoutMillis) throws InterruptedException {
        assertThat(waitForCondition(
            () -> !engine.isRunning(),
            timeoutMillis,
            "simulation engine to shutdown"
        )).isTrue();
    }

    @Test
    void testWorldSeedingWithOrganisms() throws Exception {
        Compiler compiler = new Compiler();
        List<String> lines = List.of(
            "NOP",
            "NOP",
            "NOP"
        );
        ProgramArtifact artifact = compiler.compile(lines, "test_program", testEnvProps);
        
        OrganismPlacement placement = OrganismPlacement.of(artifact, 1000, new int[]{0, 0});
        
        SimulationEngine testEngine = new SimulationEngine(queue, testEnvProps,
             List.of(placement), new ArrayList<>());
        
        testEngine.start();
        
        assertThat(waitForCondition(
            testEngine::isRunning,
            5000,
            "simulation engine to start"
        )).isTrue();
        
        assertThat(testEngine.isRunning()).isTrue();
        
        testEngine.shutdown();
        
        assertThat(waitForCondition(
            () -> !testEngine.isRunning(),
            5000,
            "simulation engine to shutdown"
        )).isTrue();
    }

    @Test
    void testMultipleOrganismSeeding() throws Exception {
        Compiler compiler = new Compiler();
        List<String> lines = List.of("NOP", "NOP", "NOP");
        
        ProgramArtifact artifact1 = compiler.compile(lines, "test_program_1", testEnvProps);
        ProgramArtifact artifact2 = compiler.compile(lines, "test_program_2", testEnvProps);
        
        OrganismPlacement placement1 = OrganismPlacement.of(artifact1, 1000, new int[]{0, 0});
        OrganismPlacement placement2 = OrganismPlacement.of(artifact2, 1000, new int[]{5, 5});
        
        SimulationEngine testEngine = new SimulationEngine(queue, testEnvProps,
             List.of(placement1, placement2), new ArrayList<>());
        
        testEngine.start();
        
        assertThat(waitForCondition(
            testEngine::isRunning,
            5000,
            "simulation engine to start"
        )).isTrue();
        
        assertThat(testEngine.isRunning()).isTrue();
        
        testEngine.shutdown();
        
        assertThat(waitForCondition(
            () -> !testEngine.isRunning(),
            5000,
            "simulation engine to shutdown"
        )).isTrue();
    }

    @Test
    void testEmptyWorldSeeding() throws Exception {
        engine.start();
        
        assertThat(waitForCondition(
            engine::isRunning,
            5000,
            "simulation engine to start"
        )).isTrue();
        
        assertThat(engine.isRunning()).isTrue();
        
        engine.shutdown();
        
        assertThat(waitForCondition(
            () -> !engine.isRunning(),
            5000,
            "simulation engine to shutdown"
        )).isTrue();
    }
}
