package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
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

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*")
class SimulationEngineIntegrationTest {

    private Map<String, List<IResource>> resources;
    private InMemoryBlockingQueue<TickData> tickDataQueue;
    private InMemoryBlockingQueue<SimulationMetadata> metadataQueue;
    private Config baseConfig;
    private Path programFile;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void beforeAll() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() throws IOException {
        tickDataQueue = new InMemoryBlockingQueue<>("tick-test", ConfigFactory.parseMap(Map.of("capacity", 1000)));
        metadataQueue = new InMemoryBlockingQueue<>("meta-test", ConfigFactory.parseMap(Map.of("capacity", 10)));

        resources = new HashMap<>();
        resources.put("tickData", Collections.singletonList(tickDataQueue));
        resources.put("metadataOutput", Collections.singletonList(metadataQueue));

        Path sourceProgram = Path.of("assembly/examples/simple.s");
        programFile = tempDir.resolve("simple.s");
        Files.copy(sourceProgram, programFile, StandardCopyOption.REPLACE_EXISTING);

        baseConfig = ConfigFactory.parseMap(Map.of(
                "samplingInterval", 1,
                "environment", Map.of(
                        "shape", List.of(10, 10),
                        "topology", "TORUS"
                ),
                "organisms", List.of(Map.of(
                        "program", programFile.toString(),
                        "initialEnergy", 1000,
                        "placement", Map.of("positions", List.of(5, 5))
                )),
                "energyStrategies", Collections.emptyList(),
                "seed", 12345
        ));
    }

    @Test
    void engineShouldProduceMetadataAndTickData() throws InterruptedException {
        SimulationEngine engine = new SimulationEngine("test-engine", baseConfig, resources);

        engine.start();
        Thread.sleep(200);
        engine.stop();
        Thread.sleep(100);

        assertEquals(1L, metadataQueue.getMetrics().get("current_size").longValue(), "Should have received exactly one metadata message.");
        Optional<SimulationMetadata> metadataOpt = metadataQueue.poll(0, TimeUnit.MILLISECONDS);
        assertTrue(metadataOpt.isPresent(), "Metadata should be present in the queue");
        SimulationMetadata metadata = metadataOpt.get();
        assertNotNull(metadata);
        assertFalse(metadata.getSimulationRunId().isEmpty());
        assertEquals(1, metadata.getProgramsCount());

        assertTrue(tickDataQueue.getMetrics().get("current_size").longValue() > 0, "Should have received at least one tick data message.");
        Optional<TickData> firstTickOpt = tickDataQueue.poll(0, TimeUnit.MILLISECONDS);
        assertTrue(firstTickOpt.isPresent(), "TickData should be present in the queue");
        TickData firstTick = firstTickOpt.get();
        assertNotNull(firstTick);
        assertEquals(metadata.getSimulationRunId(), firstTick.getSimulationRunId());
        assertTrue(firstTick.getTickNumber() > 0);
        assertEquals(1, firstTick.getOrganismsCount());
        assertFalse(firstTick.getRngState().isEmpty());
    }

    @Test
    void engineShouldRespectSamplingInterval() throws InterruptedException {
        Config sampledConfig = baseConfig.withValue("samplingInterval", ConfigValueFactory.fromAnyRef(10))
                                         .withValue("pauseTicks", ConfigValueFactory.fromAnyRef(List.of(100L)));
        SimulationEngine engine = new SimulationEngine("test-engine", sampledConfig, resources);

        engine.start();

        for (int i=0; i<50 && engine.getCurrentState() != AbstractService.State.PAUSED; i++) {
            Thread.sleep(100);
        }

        assertEquals(AbstractService.State.PAUSED, engine.getCurrentState(), "Engine should have paused at tick 100");

        Map<String, Number> metrics = engine.getMetrics();
        assertEquals(100L, metrics.get("current_tick").longValue());

        assertEquals(10L, tickDataQueue.getMetrics().get("current_size").longValue(), "Should have sent exactly 10 messages for 100 ticks with interval 10.");

        engine.stop();
    }

    @Test
    void engineShouldAutoPauseAtConfiguredTick() throws InterruptedException {
        Config pauseConfig = baseConfig.withValue("pauseTicks", ConfigValueFactory.fromAnyRef(List.of(20L)));
        SimulationEngine engine = new SimulationEngine("test-engine", pauseConfig, resources);

        engine.start();

        for (int i=0; i<50 && engine.getCurrentState() != AbstractService.State.PAUSED; i++) {
            Thread.sleep(100);
        }

        assertEquals(AbstractService.State.PAUSED, engine.getCurrentState(), "Engine should be in PAUSED state.");

        Map<String, Number> metrics = engine.getMetrics();
        assertEquals(20L, metrics.get("current_tick").longValue());

        assertEquals(20L, tickDataQueue.getMetrics().get("current_size").longValue(), "Should have sent messages for every tick up to the pause point.");

        engine.stop();
    }
}