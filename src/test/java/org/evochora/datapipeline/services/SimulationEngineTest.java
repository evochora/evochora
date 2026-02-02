package org.evochora.datapipeline.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

/**
 * Comprehensive unit tests for SimulationEngine focusing on constructor validation,
 * configuration parsing, metadata generation, and metrics calculation.
 * These tests are fast and isolated from actual simulation execution.
 */
@Tag("unit")
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*")
class SimulationEngineTest {

    private Map<String, List<IResource>> resources;
    private IOutputQueueResource<TickData> mockTickDataOutput;
    private IOutputQueueResource<SimulationMetadata> mockMetadataOutput;
    private Path programFile;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void beforeAll() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() throws IOException {
        @SuppressWarnings("unchecked")
        IOutputQueueResource<TickData> tickQueue = mock(IOutputQueueResource.class);
        mockTickDataOutput = tickQueue;
        
        @SuppressWarnings("unchecked")
        IOutputQueueResource<SimulationMetadata> metadataQueue = mock(IOutputQueueResource.class);
        mockMetadataOutput = metadataQueue;

        resources = new HashMap<>();
        resources.put("tickData", Collections.singletonList(mockTickDataOutput));
        resources.put("metadataOutput", Collections.singletonList(mockMetadataOutput));

        Path sourceProgram = Path.of("src/test/resources/org/evochora/datapipeline/services/simple.evo");
        programFile = tempDir.resolve("simple.evo");
        Files.copy(sourceProgram, programFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private Config createValidConfig() {
        // Create runtime configuration with default thermodynamic policy
        Map<String, Object> runtimeConfig = Map.of(
                "organism", Map.of(
                        "max-energy", 32767,
                        "max-entropy", 8191,
                        "error-penalty-cost", 500
                ),
                "thermodynamics", Map.of(
                        "default", Map.of(
                                "className", "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy",
                                "options", Map.of(
                                        "base-energy", 1,
                                        "base-entropy", 1
                                )
                        ),
                        "overrides", Map.of(
                                "instructions", Map.of(),
                                "families", Map.of()
                        )
                )
        );

        return ConfigFactory.parseMap(Map.of(
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
                "plugins", Collections.emptyList(),
                "seed", 12345L,
                "runtime", runtimeConfig
        ));
    }

    // ============ Constructor Validation Tests ============

    @Test
    void constructor_shouldThrowException_whenOrganismsAreMissing() {
        Config emptyConfig = ConfigFactory.parseMap(Map.of(
                "organisms", Collections.emptyList(),
                "environment", Map.of("shape", List.of(10, 10), "topology", "TORUS"),
                "plugins", Collections.emptyList()
        ));
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new SimulationEngine("test", emptyConfig, resources)
        );
        assertEquals("At least one organism must be configured.", exception.getMessage());
    }

    @Test
    void constructor_shouldThrowException_whenProgramFileIsMissing() {
        Config config = createValidConfig().withValue(
                "organisms.0.program",
                ConfigValueFactory.fromAnyRef("nonexistent/path/to/program.evo")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimulationEngine("test", config, resources)
        );
    }

    @Test
    void constructor_shouldThrowException_whenProgramIsInvalid() throws IOException {
        Path invalidProgram = tempDir.resolve("invalid.evo");
        Files.writeString(invalidProgram, "INVALID SYNTAX HERE");
        
        Config config = createValidConfig().withValue(
                "organisms.0.program",
                ConfigValueFactory.fromAnyRef(invalidProgram.toString())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimulationEngine("test", config, resources)
        );
    }

    @Test
    void constructor_shouldThrowException_whenSamplingIntervalIsZero() {
        Config config = createValidConfig().withValue("samplingInterval", ConfigValueFactory.fromAnyRef(0));
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new SimulationEngine("test", config, resources)
        );
        assertEquals("samplingInterval must be >= 1", exception.getMessage());
    }

    @Test
    void constructor_shouldThrowException_whenSamplingIntervalIsNegative() {
        Config config = createValidConfig().withValue("samplingInterval", ConfigValueFactory.fromAnyRef(-1));
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new SimulationEngine("test", config, resources)
        );
        assertEquals("samplingInterval must be >= 1", exception.getMessage());
    }

    @Test
    void constructor_shouldThrowException_whenRequiredResourceIsMissing() {
        resources.remove("tickData");
        Config config = createValidConfig();
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new SimulationEngine("test", config, resources)
        );
        assertEquals("Resource port 'tickData' is not configured.", exception.getMessage());
    }

    @Test
    void constructor_shouldThrowException_whenMetadataOutputIsMissing() {
        resources.remove("metadataOutput");
        Config config = createValidConfig();
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new SimulationEngine("test", config, resources)
        );
        assertEquals("Resource port 'metadataOutput' is not configured.", exception.getMessage());
    }

    @Test
    void constructor_shouldThrowException_whenOrganismPlacementDimensionMismatch() {
        Config config = createValidConfig().withValue(
                "organisms",
                ConfigValueFactory.fromAnyRef(List.of(Map.of(
                        "program", programFile.toString(),
                        "initialEnergy", 1000,
                        "placement", Map.of("positions", List.of(5)) // 1D position for 2D world
                )))
        );
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new SimulationEngine("test", config, resources)
        );
        assertTrue(exception.getMessage().contains("placement mismatch"));
        assertTrue(exception.getMessage().contains("2 dimensions"));
    }

    @Test
    void constructor_shouldSucceedWithValidConfiguration() {
        Config config = createValidConfig();
        assertDoesNotThrow(() -> new SimulationEngine("test-engine", config, resources));
    }

    @Test
    void constructor_shouldSucceedWithMultipleOrganisms() {
        Config config = createValidConfig().withValue(
                "organisms",
                ConfigValueFactory.fromAnyRef(List.of(
                        Map.of("program", programFile.toString(), "initialEnergy", 1000, 
                               "placement", Map.of("positions", List.of(2, 2))),
                        Map.of("program", programFile.toString(), "initialEnergy", 500,
                               "placement", Map.of("positions", List.of(7, 7)))
                ))
        );
        assertDoesNotThrow(() -> new SimulationEngine("test-engine", config, resources));
    }

    @Test
    void constructor_shouldUseDeterministicSeedWhenProvided() {
        Config config = createValidConfig().withValue("seed", ConfigValueFactory.fromAnyRef(42L));
        assertDoesNotThrow(() -> new SimulationEngine("test-engine", config, resources));
    }

    @Test
    void constructor_shouldAcceptBoundedTopology() {
        Config config = createValidConfig().withValue(
                "environment.topology",
                ConfigValueFactory.fromAnyRef("BOUNDED")
        );
        assertDoesNotThrow(() -> new SimulationEngine("test-engine", config, resources));
    }

    @Test
    void constructor_shouldAccept1DWorld() throws IOException {
        Path program1D = tempDir.resolve("simple_1d.evo");
        Files.copy(Path.of("src/test/resources/org/evochora/datapipeline/services/simple_1d.evo"), program1D, StandardCopyOption.REPLACE_EXISTING);
        
        Config config = createValidConfig()
                .withValue("environment.shape", ConfigValueFactory.fromAnyRef(List.of(20)))
                .withValue("organisms", ConfigValueFactory.fromAnyRef(List.of(Map.of(
                        "program", program1D.toString(),
                        "initialEnergy", 1000,
                        "placement", Map.of("positions", List.of(10))
                ))));
        assertDoesNotThrow(() -> new SimulationEngine("test-engine", config, resources));
    }

    @Test
    void constructor_shouldAccept3DWorld() throws IOException {
        Path program3D = tempDir.resolve("simple_3d.evo");
        Files.copy(Path.of("src/test/resources/org/evochora/datapipeline/services/simple_3d.evo"), program3D, StandardCopyOption.REPLACE_EXISTING);
        
        Config config = createValidConfig()
                .withValue("environment.shape", ConfigValueFactory.fromAnyRef(List.of(10, 10, 10)))
                .withValue("organisms", ConfigValueFactory.fromAnyRef(List.of(Map.of(
                        "program", program3D.toString(),
                        "initialEnergy", 1000,
                        "placement", Map.of("positions", List.of(5, 5, 5))
                ))));
        assertDoesNotThrow(() -> new SimulationEngine("test-engine", config, resources));
    }

    @Test
    void constructor_shouldAcceptPauseTicksConfiguration() {
        Config config = createValidConfig().withValue(
                "pauseTicks",
                ConfigValueFactory.fromAnyRef(List.of(10L, 20L, 30L))
        );
        assertDoesNotThrow(() -> new SimulationEngine("test-engine", config, resources));
    }

    @Test
    void constructor_shouldAcceptWithoutSeed() {
        Config config = createValidConfig().withoutPath("seed");
        assertDoesNotThrow(() -> new SimulationEngine("test-engine", config, resources));
    }

    @Test
    void constructor_shouldAcceptCustomSamplingInterval() {
        Config config = createValidConfig().withValue("samplingInterval", ConfigValueFactory.fromAnyRef(10));
        assertDoesNotThrow(() -> new SimulationEngine("test-engine", config, resources));
    }

    @Test
    void constructor_shouldDefaultSamplingIntervalTo1() {
        Config config = createValidConfig().withoutPath("samplingInterval");
        SimulationEngine engine = new SimulationEngine("test-engine", config, resources);
        assertEquals(1, engine.getMetrics().get("sampling_interval"));
    }

    @Test
    void constructor_shouldDefaultPauseTicksToEmpty() {
        Config config = createValidConfig().withoutPath("pauseTicks");
        assertDoesNotThrow(() -> new SimulationEngine("test-engine", config, resources));
    }

    // ============ Metrics Tests ============

    @Test
    void getMetrics_shouldReturnInitialMetrics() {
        Config config = createValidConfig();
        SimulationEngine engine = new SimulationEngine("test-engine", config, resources);
        
        Map<String, Number> metrics = engine.getMetrics();
        
        assertNotNull(metrics);
        assertEquals(-1L, metrics.get("current_tick").longValue(), "Initial tick should be -1");
        assertEquals(1L, metrics.get("organisms_total").longValue());
        assertEquals(1L, metrics.get("organisms_alive").longValue());
        assertEquals(0L, metrics.get("messages_sent").longValue());
        assertEquals(1, metrics.get("sampling_interval").intValue());
        assertEquals(0.0, metrics.get("ticks_per_second").doubleValue());
        assertEquals(0, metrics.get("error_count").intValue());
    }

    @Test
    void getMetrics_shouldReturnCorrectOrganismCount() {
        Config config = createValidConfig().withValue(
                "organisms",
                ConfigValueFactory.fromAnyRef(List.of(
                        Map.of("program", programFile.toString(), "initialEnergy", 1000,
                               "placement", Map.of("positions", List.of(2, 2))),
                        Map.of("program", programFile.toString(), "initialEnergy", 500,
                               "placement", Map.of("positions", List.of(7, 7))),
                        Map.of("program", programFile.toString(), "initialEnergy", 750,
                               "placement", Map.of("positions", List.of(5, 5)))
                ))
        );
        SimulationEngine engine = new SimulationEngine("test-engine", config, resources);
        
        Map<String, Number> metrics = engine.getMetrics();
        
        assertEquals(3L, metrics.get("organisms_total").longValue());
        assertEquals(3L, metrics.get("organisms_alive").longValue());
    }

    // ============ Health and Error Management Tests ============

    @Test
    void isHealthy_shouldReturnTrueInitially() {
        Config config = createValidConfig();
        SimulationEngine engine = new SimulationEngine("test-engine", config, resources);
        
        assertTrue(engine.isHealthy());
    }

    @Test
    void getErrors_shouldReturnEmptyListInitially() {
        Config config = createValidConfig();
        SimulationEngine engine = new SimulationEngine("test-engine", config, resources);
        
        assertTrue(engine.getErrors().isEmpty());
    }

    @Test
    void clearErrors_shouldNotThrow() {
        Config config = createValidConfig();
        SimulationEngine engine = new SimulationEngine("test-engine", config, resources);
        
        assertDoesNotThrow(engine::clearErrors);
    }

    // ============ State Management Tests ============

    @Test
    void getCurrentState_shouldReturnStoppedInitially() {
        Config config = createValidConfig();
        SimulationEngine engine = new SimulationEngine("test-engine", config, resources);
        
        assertEquals(AbstractService.State.STOPPED, engine.getCurrentState());
    }
}
