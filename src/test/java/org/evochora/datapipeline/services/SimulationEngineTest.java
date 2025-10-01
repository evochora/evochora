package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.wrappers.queues.IOutputQueueResource;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

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
        mockTickDataOutput = mock(IOutputQueueResource.class);
        mockMetadataOutput = mock(IOutputQueueResource.class);

        resources = new HashMap<>();
        resources.put("tickData", Collections.singletonList(mockTickDataOutput));
        resources.put("metadataOutput", Collections.singletonList(mockMetadataOutput));

        Path sourceProgram = Path.of("assembly/examples/simple.s");
        programFile = tempDir.resolve("simple.s");
        Files.copy(sourceProgram, programFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private Config createValidConfig() {
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
                "energyStrategies", Collections.emptyList(),
                "seed", 12345
        ));
    }

    @Test
    void constructor_shouldThrowException_whenOrganismsAreMissing() {
        Config emptyConfig = ConfigFactory.parseMap(Map.of("organisms", Collections.emptyList()));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> new SimulationEngine("test", emptyConfig, resources));
        assertEquals("At least one organism must be configured.", exception.getMessage());
    }

    @Test
    void constructor_shouldThrowException_whenProgramFileIsMissing() {
        Config config = createValidConfig().withValue("organisms.0.program", ConfigValueFactory.fromAnyRef("nonexistent/path/to/program.s"));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> new SimulationEngine("test", config, resources));
        assertTrue(exception.getMessage().contains("Failed to read or compile program file"));
    }

    @Test
    void constructor_shouldParseConfigurationCorrectly() {
        Config config = createValidConfig();
        assertDoesNotThrow(() -> new SimulationEngine("test-engine", config, resources),
                "Constructor should not throw an exception with valid configuration.");
    }

    @Test
    void constructor_shouldThrowException_whenRequiredResourceIsMissing() {
        resources.remove("tickData");
        Config config = createValidConfig();
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> new SimulationEngine("test", config, resources));
        assertEquals("Resource port 'tickData' is not configured.", exception.getMessage());
    }
}