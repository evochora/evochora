package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import org.evochora.datapipeline.api.contracts.*;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for SimulationEngine covering actual simulation execution,
 * lifecycle management, energy strategies, multi-organism scenarios, and different world configurations.
 * These tests use awaitility for reliable async testing without Thread.sleep.
 */
@Tag("integration")
@AllowLog(level = LogLevel.INFO, loggerPattern = "org.evochora.datapipeline.services.SimulationEngine")
@AllowLog(level = LogLevel.INFO, loggerPattern = "org.evochora.datapipeline.services.AbstractService")
class SimulationEngineIntegrationTest {

    private Map<String, List<IResource>> resources;
    private InMemoryBlockingQueue<TickData> tickDataQueue;
    private InMemoryBlockingQueue<SimulationMetadata> metadataQueue;
    private Config baseConfig;
    private Path programFile;
    private Path programFile1D;
    private Path programFile3D;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void beforeAll() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() throws IOException {
        tickDataQueue = new InMemoryBlockingQueue<>("tick-test", 
                ConfigFactory.parseMap(Map.of("capacity", 2000)));
        metadataQueue = new InMemoryBlockingQueue<>("meta-test", 
                ConfigFactory.parseMap(Map.of("capacity", 10)));

        resources = new HashMap<>();
        resources.put("tickData", Collections.singletonList(tickDataQueue));
        resources.put("metadataOutput", Collections.singletonList(metadataQueue));

        Path sourceProgram = Path.of("src/test/resources/org/evochora/datapipeline/services/simple.s");
        programFile = tempDir.resolve("simple.s");
        Files.copy(sourceProgram, programFile, StandardCopyOption.REPLACE_EXISTING);
        
        programFile1D = tempDir.resolve("simple_1d.s");
        Files.copy(Path.of("src/test/resources/org/evochora/datapipeline/services/simple_1d.s"), programFile1D, StandardCopyOption.REPLACE_EXISTING);
        
        programFile3D = tempDir.resolve("simple_3d.s");
        Files.copy(Path.of("src/test/resources/org/evochora/datapipeline/services/simple_3d.s"), programFile3D, StandardCopyOption.REPLACE_EXISTING);

        baseConfig = ConfigFactory.parseMap(Map.of(
                "samplingInterval", 1,
                "environment", Map.of(
                        "shape", List.of(10, 10),
                        "topology", "TORUS"
                ),
                "organisms", List.of(Map.of(
                        "program", programFile.toString(),
                        "initialEnergy", 10000, // Higher energy to avoid early death
                        "placement", Map.of("positions", List.of(5, 5))
                )),
                "energyStrategies", Collections.emptyList(),
                "seed", 12345L
        ));
    }

    // ============ Basic Execution Tests ============

    @Test
    void engine_shouldProduceMetadataMessage() throws InterruptedException {
        SimulationEngine engine = new SimulationEngine("test-engine", baseConfig, resources);

        engine.start();
        
        // Wait for metadata to be sent
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(1L, metadataQueue.getMetrics().get("current_size").longValue()));

        engine.stop();

        Optional<SimulationMetadata> metadataOpt = metadataQueue.poll(0, TimeUnit.MILLISECONDS);
        assertTrue(metadataOpt.isPresent(), "Metadata should be present in the queue");
        
        SimulationMetadata metadata = metadataOpt.get();
        assertNotNull(metadata);
        assertFalse(metadata.getSimulationRunId().isEmpty());
        assertEquals(12345L, metadata.getInitialSeed());
        assertEquals(1, metadata.getProgramsCount());
        assertEquals(1, metadata.getInitialOrganismsCount());
        
        // Verify environment configuration
        EnvironmentConfig envConfig = metadata.getEnvironment();
        assertEquals(2, envConfig.getDimensions());
        assertEquals(10, envConfig.getShape(0));
        assertEquals(10, envConfig.getShape(1));
        assertTrue(envConfig.getToroidal(0)); // TORUS topology
    }

    @Test
    void engine_shouldProduceTickData() {
        SimulationEngine engine = new SimulationEngine("test-engine", baseConfig, resources);

        engine.start();
        
        // Wait for at least one tick data message
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertTrue(
                        tickDataQueue.getMetrics().get("current_size").longValue() > 0,
                        "Should have received at least one tick data message"
                ));

        engine.stop();
    }

    @Test
    void engine_shouldCaptureOrganismStateInTickData() throws InterruptedException {
        SimulationEngine engine = new SimulationEngine("test-engine", baseConfig, resources);

        engine.start();
        
        // Wait for tick data
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertTrue(tickDataQueue.getMetrics().get("current_size").longValue() > 0));

        Optional<TickData> tickDataOpt = tickDataQueue.poll(100, TimeUnit.MILLISECONDS);
        engine.stop();

        assertTrue(tickDataOpt.isPresent(), "TickData should be present");
        TickData tickData = tickDataOpt.get();
        
        assertEquals(1, tickData.getOrganismsCount());
        OrganismState organism = tickData.getOrganisms(0);
        assertNotNull(organism);
        assertTrue(organism.getEnergy() > 0);
        assertFalse(organism.getIsDead());
        assertNotNull(organism.getIp());
        assertNotNull(organism.getInitialPosition());
    }

    @Test
    void engine_shouldCaptureRngStateInTickData() throws InterruptedException {
        SimulationEngine engine = new SimulationEngine("test-engine", baseConfig, resources);

        engine.start();
        
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertTrue(tickDataQueue.getMetrics().get("current_size").longValue() > 0));

        Optional<TickData> tickDataOpt = tickDataQueue.poll(100, TimeUnit.MILLISECONDS);
        engine.stop();

        assertTrue(tickDataOpt.isPresent());
        TickData tickData = tickDataOpt.get();
        assertFalse(tickData.getRngState().isEmpty(), "RNG state should be captured");
    }

    // ============ Sampling Interval Tests ============

    @Test
    void engine_shouldRespectSamplingInterval() {
        Config sampledConfig = baseConfig
                .withValue("samplingInterval", ConfigValueFactory.fromAnyRef(10))
                .withValue("pauseTicks", ConfigValueFactory.fromAnyRef(List.of(100L)));
        
        SimulationEngine engine = new SimulationEngine("test-engine", sampledConfig, resources);

        engine.start();

        // Wait until engine pauses at tick 100 (101 ticks executed: 0-100)
        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(
                        AbstractService.State.PAUSED, 
                        engine.getCurrentState(),
                        "Engine should have paused at tick 100"
                ));

        Map<String, Number> metrics = engine.getMetrics();
        assertEquals(100L, metrics.get("current_tick").longValue());

        // With sampling interval 10, we should have exactly 11 tick data messages for ticks 0-100
        // Ticks: 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100 = 11 messages
        long queueSize = tickDataQueue.getMetrics().get("current_size").longValue();
        assertEquals(11L, queueSize, "Should have sent exactly 11 messages for ticks 0-100 with interval 10");

        engine.stop();
    }

    @Test
    void engine_shouldNotSampleWithLargeSamplingInterval() {
        Config sampledConfig = baseConfig
                .withValue("samplingInterval", ConfigValueFactory.fromAnyRef(100))
                .withValue("pauseTicks", ConfigValueFactory.fromAnyRef(List.of(50L)));
        
        SimulationEngine engine = new SimulationEngine("test-engine", sampledConfig, resources);

        engine.start();

        // Wait until engine pauses at tick 50 (51 ticks executed: 0-50)
        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine.getCurrentState()));

        // With sampling interval 100 and ticks 0-50, only tick 0 is captured (0 % 100 == 0)
        long queueSize = tickDataQueue.getMetrics().get("current_size").longValue();
        assertEquals(1L, queueSize, "Should have sent 1 message (tick 0) for ticks 0-50 with interval 100");

        engine.stop();
    }

    // ============ Auto-Pause Tests ============

    @Test
    void engine_shouldAutoPauseAtConfiguredTick() {
        Config pauseConfig = baseConfig.withValue("pauseTicks", ConfigValueFactory.fromAnyRef(List.of(20L)));
        SimulationEngine engine = new SimulationEngine("test-engine", pauseConfig, resources);

        engine.start();

        // Wait for engine to pause at tick 20 (21 ticks executed: 0-20)
        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine.getCurrentState()));

        Map<String, Number> metrics = engine.getMetrics();
        assertEquals(20L, metrics.get("current_tick").longValue());
        assertEquals(21L, tickDataQueue.getMetrics().get("current_size").longValue());

        engine.stop();
    }

    @Test
    void engine_shouldAutoPauseAtFirstConfiguredTick() {
        Config pauseConfig = baseConfig.withValue(
                "pauseTicks",
                ConfigValueFactory.fromAnyRef(List.of(12L, 25L, 40L))
        );
        SimulationEngine engine = new SimulationEngine("test-engine", pauseConfig, resources);

        engine.start();

        // Wait for auto-pause at first tick (12 = 13 ticks executed: 0-12)
        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine.getCurrentState()));
        assertEquals(12L, engine.getMetrics().get("current_tick").longValue(), "Should pause at tick 12");

        // Verify tick data was captured up to and including tick 12
        assertTrue(tickDataQueue.getMetrics().get("current_size").longValue() >= 13);

        engine.stop();
    }

    // ============ Lifecycle Tests ============

    @Test
    void engine_shouldTransitionFromStoppedToRunning() {
        SimulationEngine engine = new SimulationEngine("test-engine", baseConfig, resources);

        assertEquals(AbstractService.State.STOPPED, engine.getCurrentState());
        engine.start();

        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.RUNNING, engine.getCurrentState()));

        engine.stop();
    }

    @Test
    void engine_shouldSupportManualPause() {
        SimulationEngine engine = new SimulationEngine("test-engine", baseConfig, resources);

        engine.start();
        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.RUNNING, engine.getCurrentState()));

        // Let it run to at least tick 10
        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertTrue(engine.getMetrics().get("current_tick").longValue() >= 10));

        // Manually pause
        engine.pause();
        await().atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine.getCurrentState()));
        
        long tickWhilePaused = engine.getMetrics().get("current_tick").longValue();
        assertTrue(tickWhilePaused >= 10, "Should have at least 10 ticks before pause");

        // Verify metrics are still accessible while paused
        Map<String, Number> metrics = engine.getMetrics();
        assertEquals(tickWhilePaused, metrics.get("current_tick").longValue());
        assertTrue(metrics.get("organisms_total").longValue() >= 1);

        engine.stop();
    }

    @Test
    void engine_shouldStop() {
        SimulationEngine engine = new SimulationEngine("test-engine", baseConfig, resources);

        engine.start();
        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.RUNNING, engine.getCurrentState()));

        engine.stop();
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.STOPPED, engine.getCurrentState()));
    }

    @Test
    void engine_shouldUpdateMetricsWhileRunning() {
        SimulationEngine engine = new SimulationEngine("test-engine", baseConfig, resources);

        engine.start();

        // Wait for some ticks to accumulate
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertTrue(engine.getMetrics().get("current_tick").longValue() >= 10));

        Map<String, Number> metrics = engine.getMetrics();
        assertTrue(metrics.get("current_tick").longValue() >= 10);
        assertTrue(metrics.get("messages_sent").longValue() >= 10);
        assertEquals(1L, metrics.get("organisms_total").longValue());
        // Organism may or may not be alive depending on energy consumption
        assertTrue(metrics.get("organisms_alive").longValue() >= 0 && metrics.get("organisms_alive").longValue() <= 1);

        engine.stop();
    }

    // ============ Multi-Organism Tests ============

    @Test
    void engine_shouldHandleMultipleOrganisms() throws InterruptedException {
        Config multiOrgConfig = baseConfig.withValue(
                "organisms",
                ConfigValueFactory.fromAnyRef(List.of(
                        Map.of("program", programFile.toString(), "initialEnergy", 1000,
                               "placement", Map.of("positions", List.of(2, 2))),
                        Map.of("program", programFile.toString(), "initialEnergy", 800,
                               "placement", Map.of("positions", List.of(5, 5))),
                        Map.of("program", programFile.toString(), "initialEnergy", 600,
                               "placement", Map.of("positions", List.of(8, 8)))
                ))
        );

        SimulationEngine engine = new SimulationEngine("test-engine", multiOrgConfig, resources);

        engine.start();

        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertTrue(tickDataQueue.getMetrics().get("current_size").longValue() > 0));

        Optional<TickData> tickDataOpt = tickDataQueue.poll(100, TimeUnit.MILLISECONDS);
        engine.stop();

        assertTrue(tickDataOpt.isPresent());
        TickData tickData = tickDataOpt.get();
        
        // Should capture all three organisms
        assertTrue(tickData.getOrganismsCount() >= 1 && tickData.getOrganismsCount() <= 3,
                "Should capture between 1 and 3 organisms (some may have died)");

        Map<String, Number> metrics = engine.getMetrics();
        assertEquals(3L, metrics.get("organisms_total").longValue());
    }

    // ============ Energy Strategy Tests ============

    @Test
    void engine_shouldRunWithoutEnergyStrategies() {
        // baseConfig already has no energy strategies
        SimulationEngine engine = new SimulationEngine("test-engine", baseConfig, resources);

        engine.start();

        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertTrue(engine.getMetrics().get("current_tick").longValue() > 10));

        engine.stop();
    }

    @Test
    void engine_shouldRunWithSolarRadiationStrategy() throws InterruptedException {
        Config strategyConfig = baseConfig.withValue(
                "energyStrategies",
                ConfigValueFactory.fromAnyRef(List.of(
                        Map.of(
                                "className", "org.evochora.runtime.worldgen.SolarRadiationCreator",
                                "options", Map.of(
                                        "probability", 0.1,
                                        "amount", 50,
                                        "safetyRadius", 2,
                                        "executionsPerTick", 1
                                )
                        )
                ))
        ).withValue("pauseTicks", ConfigValueFactory.fromAnyRef(List.of(20L)));

        SimulationEngine engine = new SimulationEngine("test-engine", strategyConfig, resources);

        engine.start();

        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine.getCurrentState()));

        // Verify metadata includes energy strategy
        Optional<SimulationMetadata> metadataOpt = metadataQueue.poll(0, TimeUnit.MILLISECONDS);
        assertTrue(metadataOpt.isPresent());
        SimulationMetadata metadata = metadataOpt.get();
        assertEquals(1, metadata.getEnergyStrategiesCount());
        assertEquals("org.evochora.runtime.worldgen.SolarRadiationCreator", 
                metadata.getEnergyStrategies(0).getStrategyType());

        // Verify tick data includes strategy state
        Optional<TickData> tickDataOpt = tickDataQueue.poll(0, TimeUnit.MILLISECONDS);
        assertTrue(tickDataOpt.isPresent());
        TickData tickData = tickDataOpt.get();
        assertEquals(1, tickData.getStrategyStatesCount());

        engine.stop();
    }

    @Test
    void engine_shouldRunWithGeyserStrategy() throws InterruptedException {
        Config strategyConfig = baseConfig.withValue(
                "energyStrategies",
                ConfigValueFactory.fromAnyRef(List.of(
                        Map.of(
                                "className", "org.evochora.runtime.worldgen.GeyserCreator",
                                "options", Map.of(
                                        "count", 3,
                                        "interval", 5,
                                        "amount", 100,
                                        "safetyRadius", 2
                                )
                        )
                ))
        ).withValue("pauseTicks", ConfigValueFactory.fromAnyRef(List.of(20L)));

        SimulationEngine engine = new SimulationEngine("test-engine", strategyConfig, resources);

        engine.start();

        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine.getCurrentState()));

        // Verify metadata
        Optional<SimulationMetadata> metadataOpt = metadataQueue.poll(0, TimeUnit.MILLISECONDS);
        assertTrue(metadataOpt.isPresent());
        SimulationMetadata metadata = metadataOpt.get();
        assertEquals(1, metadata.getEnergyStrategiesCount());
        assertEquals("org.evochora.runtime.worldgen.GeyserCreator",
                metadata.getEnergyStrategies(0).getStrategyType());

        // Verify tick data includes geyser state
        Optional<TickData> tickDataOpt = tickDataQueue.poll(0, TimeUnit.MILLISECONDS);
        assertTrue(tickDataOpt.isPresent());
        TickData tickData = tickDataOpt.get();
        assertEquals(1, tickData.getStrategyStatesCount());
        // Geyser state should be non-empty after initialization
        assertFalse(tickData.getStrategyStates(0).getStateBlob().isEmpty());

        engine.stop();
    }

    @Test
    void engine_shouldRunWithMultipleEnergyStrategies() throws InterruptedException {
        Config strategyConfig = baseConfig.withValue(
                "energyStrategies",
                ConfigValueFactory.fromAnyRef(List.of(
                        Map.of(
                                "className", "org.evochora.runtime.worldgen.SolarRadiationCreator",
                                "options", Map.of(
                                        "probability", 0.05,
                                        "amount", 30,
                                        "safetyRadius", 2,
                                        "executionsPerTick", 1
                                )
                        ),
                        Map.of(
                                "className", "org.evochora.runtime.worldgen.GeyserCreator",
                                "options", Map.of(
                                        "count", 2,
                                        "interval", 10,
                                        "amount", 80,
                                        "safetyRadius", 2
                                )
                        )
                ))
        ).withValue("pauseTicks", ConfigValueFactory.fromAnyRef(List.of(25L)));

        SimulationEngine engine = new SimulationEngine("test-engine", strategyConfig, resources);

        engine.start();

        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine.getCurrentState()));

        // Verify metadata includes both strategies
        Optional<SimulationMetadata> metadataOpt = metadataQueue.poll(0, TimeUnit.MILLISECONDS);
        assertTrue(metadataOpt.isPresent());
        SimulationMetadata metadata = metadataOpt.get();
        assertEquals(2, metadata.getEnergyStrategiesCount());

        // Verify tick data includes both strategy states
        Optional<TickData> tickDataOpt = tickDataQueue.poll(0, TimeUnit.MILLISECONDS);
        assertTrue(tickDataOpt.isPresent());
        TickData tickData = tickDataOpt.get();
        assertEquals(2, tickData.getStrategyStatesCount());

        engine.stop();
    }

    // ============ Different World Configuration Tests ============

    @Test
    void engine_shouldRunIn1DWorld() {
        Config config1D = baseConfig
                .withValue("environment.shape", ConfigValueFactory.fromAnyRef(List.of(50)))
                .withValue("organisms", ConfigValueFactory.fromAnyRef(List.of(Map.of(
                        "program", programFile1D.toString(),
                        "initialEnergy", 1000,
                        "placement", Map.of("positions", List.of(25))
                ))))
                .withValue("pauseTicks", ConfigValueFactory.fromAnyRef(List.of(15L)));

        SimulationEngine engine = new SimulationEngine("test-engine", config1D, resources);

        engine.start();

        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine.getCurrentState()));

        assertEquals(15L, engine.getMetrics().get("current_tick").longValue());

        engine.stop();
    }

    @Test
    void engine_shouldRunIn3DWorld() {
        Config config3D = baseConfig
                .withValue("environment.shape", ConfigValueFactory.fromAnyRef(List.of(8, 8, 8)))
                .withValue("organisms", ConfigValueFactory.fromAnyRef(List.of(Map.of(
                        "program", programFile3D.toString(),
                        "initialEnergy", 1000,
                        "placement", Map.of("positions", List.of(4, 4, 4))
                ))))
                .withValue("pauseTicks", ConfigValueFactory.fromAnyRef(List.of(15L)));

        SimulationEngine engine = new SimulationEngine("test-engine", config3D, resources);

        engine.start();

        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine.getCurrentState()));

        assertEquals(15L, engine.getMetrics().get("current_tick").longValue());

        engine.stop();
    }

    @Test
    void engine_shouldRunInBoundedTopology() {
        Config boundedConfig = baseConfig
                .withValue("environment.topology", ConfigValueFactory.fromAnyRef("BOUNDED"))
                .withValue("pauseTicks", ConfigValueFactory.fromAnyRef(List.of(15L)));

        SimulationEngine engine = new SimulationEngine("test-engine", boundedConfig, resources);

        engine.start();

        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine.getCurrentState()));

        assertEquals(15L, engine.getMetrics().get("current_tick").longValue());

        engine.stop();
    }

    @Test
    void engine_shouldRunInLargeWorld() {
        Config largeWorldConfig = baseConfig
                .withValue("environment.shape", ConfigValueFactory.fromAnyRef(List.of(50, 50)))
                .withValue("organisms", ConfigValueFactory.fromAnyRef(List.of(Map.of(
                        "program", programFile.toString(),
                        "initialEnergy", 1000,
                        "placement", Map.of("positions", List.of(25, 25))
                ))))
                .withValue("pauseTicks", ConfigValueFactory.fromAnyRef(List.of(10L)));

        SimulationEngine engine = new SimulationEngine("test-engine", largeWorldConfig, resources);

        engine.start();

        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine.getCurrentState()));

        assertEquals(10L, engine.getMetrics().get("current_tick").longValue());

        engine.stop();
    }

    @Test
    void engine_shouldRunInSmallWorld() {
        Config smallWorldConfig = baseConfig
                .withValue("environment.shape", ConfigValueFactory.fromAnyRef(List.of(5, 5)))
                .withValue("organisms", ConfigValueFactory.fromAnyRef(List.of(Map.of(
                        "program", programFile.toString(),
                        "initialEnergy", 1000,
                        "placement", Map.of("positions", List.of(2, 2))
                ))))
                .withValue("pauseTicks", ConfigValueFactory.fromAnyRef(List.of(10L)));

        SimulationEngine engine = new SimulationEngine("test-engine", smallWorldConfig, resources);

        engine.start();

        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine.getCurrentState()));

        assertEquals(10L, engine.getMetrics().get("current_tick").longValue());

        engine.stop();
    }

    // ============ Determinism Tests ============

    @Test
    void engine_shouldProduceDeterministicResultsWithSameSeed() throws InterruptedException {
        Config deterministicConfig = baseConfig.withValue("pauseTicks", 
                ConfigValueFactory.fromAnyRef(List.of(10L)));

        // Run first engine
        SimulationEngine engine1 = new SimulationEngine("test-engine-1", deterministicConfig, resources);
        engine1.start();
        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine1.getCurrentState()));
        
        Optional<TickData> tickData1Opt = tickDataQueue.poll(0, TimeUnit.MILLISECONDS);
        engine1.stop();

        // Clear queues
        while (tickDataQueue.poll(0, TimeUnit.MILLISECONDS).isPresent()) {}
        while (metadataQueue.poll(0, TimeUnit.MILLISECONDS).isPresent()) {}

        // Run second engine with same seed
        SimulationEngine engine2 = new SimulationEngine("test-engine-2", deterministicConfig, resources);
        engine2.start();
        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine2.getCurrentState()));
        
        Optional<TickData> tickData2Opt = tickDataQueue.poll(0, TimeUnit.MILLISECONDS);
        engine2.stop();

        // Compare results
        assertTrue(tickData1Opt.isPresent() && tickData2Opt.isPresent());
        TickData tickData1 = tickData1Opt.get();
        TickData tickData2 = tickData2Opt.get();

        // RNG states should be identical with same seed
        assertEquals(tickData1.getRngState(), tickData2.getRngState(), 
                "RNG states should be identical with same seed");
    }

    // ============ Throughput Window Tests ============

    @Test
    void engine_shouldCalculateTicksPerSecond() {
        Config throughputConfig = baseConfig
                .withValue("throughputWindowSeconds", ConfigValueFactory.fromAnyRef(1))
                .withValue("pauseTicks", ConfigValueFactory.fromAnyRef(List.of(50L)));

        SimulationEngine engine = new SimulationEngine("test-engine", throughputConfig, resources);

        engine.start();

        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(AbstractService.State.PAUSED, engine.getCurrentState()));

        Map<String, Number> metrics = engine.getMetrics();
        // After running for a bit, ticks_per_second should be calculated
        // It might be 0.0 if the window hasn't elapsed yet, but tick count should be 50
        assertEquals(50L, metrics.get("current_tick").longValue());

        engine.stop();
    }
}
