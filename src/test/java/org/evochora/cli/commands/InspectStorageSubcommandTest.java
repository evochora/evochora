package org.evochora.cli.commands;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InspectStorageSubcommandTest {

    @Test
    public void testCalculateTotalCells_ThrowsException_WhenEnvironmentMissing() throws Exception {
        InspectStorageSubcommand command = new InspectStorageSubcommand();
        Method method = InspectStorageSubcommand.class.getDeclaredMethod("calculateTotalCells", SimulationMetadata.class);
        method.setAccessible(true);

        // Metadata without environment
        SimulationMetadata metadata = SimulationMetadata.newBuilder().build();

        try {
            method.invoke(command, metadata);
            throw new RuntimeException("Should have thrown exception");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertEquals(IllegalStateException.class, e.getCause().getClass());
            assertEquals("Simulation metadata is missing resolved config. Cannot calculate total cells for decoder.", e.getCause().getMessage());
        }
    }

    @Test
    public void testCalculateTotalCells_ThrowsException_WhenShapeEmpty() throws Exception {
        InspectStorageSubcommand command = new InspectStorageSubcommand();
        Method method = InspectStorageSubcommand.class.getDeclaredMethod("calculateTotalCells", SimulationMetadata.class);
        method.setAccessible(true);

        // Metadata with empty resolvedConfigJson (no environment shape)
        String emptyConfigJson = """
            {
                "environment": {
                    "shape": [],
                    "topology": "BOUNDED"
                },
                "samplingInterval": 1,
                "accumulatedDeltaInterval": 100,
                "snapshotInterval": 10,
                "chunkInterval": 1,
                "tickPlugins": [],
                "organisms": [],
                "runtime": {
                    "organism": {
                        "max-energy": 32767,
                        "max-entropy": 8191,
                        "error-penalty-cost": 10
                    },
                    "thermodynamics": {
                        "default": {
                            "className": "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy",
                            "options": {
                                "base-energy": 1,
                                "base-entropy": 1
                            }
                        },
                        "overrides": {
                            "instructions": {},
                            "families": {}
                        }
                    }
                }
            }
            """;
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
                .setResolvedConfigJson(emptyConfigJson)
                .build();

        try {
            method.invoke(command, metadata);
            throw new RuntimeException("Should have thrown exception");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertEquals(IllegalStateException.class, e.getCause().getClass());
            assertEquals("Simulation metadata has empty environment shape. Cannot calculate total cells for decoder.", e.getCause().getMessage());
        }
    }

    @Test
    public void testCalculateTotalCells_ReturnsCorrectTotal_WhenShapePresent() throws Exception {
        InspectStorageSubcommand command = new InspectStorageSubcommand();
        Method method = InspectStorageSubcommand.class.getDeclaredMethod("calculateTotalCells", SimulationMetadata.class);
        method.setAccessible(true);

        // Metadata with environment and shape [10, 20] -> 200
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
                .setResolvedConfigJson(TestMetadataHelper.builder()
                    .shape(10, 20)
                    .build())
                .build();

        int result = (int) method.invoke(command, metadata);
        assertEquals(200, result, "Should return product of dimensions");
    }
}
