/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.node.processes.http.api.visualizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.database.dto.SpatialRegion;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Phase 3 unit tests: HTTP request parsing and controller construction (no database I/O)
 * <p>
 * Tests focus on data classes and controller construction without database dependencies:
 * <ul>
 *   <li>SpatialRegion parsing and validation</li>
 *   <li>Controller construction and configuration</li>
 * </ul>
 * <p>
 * <strong>Note:</strong> CellWithCoordinates tests were removed as the API now returns
 * Protobuf binary instead of JSON. See EnvironmentControllerIntegrationTest for 
 * Protobuf response validation.
 * <p>
 * <strong>AGENTS.md Compliance:</strong>
 * <ul>
 *   <li>Tagged as @Tag("unit") - <0.2s runtime, no I/O</li>
 *   <li>No database dependencies - pure unit tests</li>
 *   <li>Inline test data - all test data constructed inline</li>
 *   <li>Fast execution - no external dependencies</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("EnvironmentController Unit Tests")
class EnvironmentControllerUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("Region Parsing")
    class RegionParsing {

        @Test
        @DisplayName("Should parse 2D region correctly")
        void parse2DRegion_correctly() {
            SpatialRegion region = createSpatialRegion(new int[]{0, 50, 0, 50});
            
            assertThat(region).isNotNull();
            assertThat(region.getDimensions()).isEqualTo(2);
            assertThat(region.bounds).isEqualTo(new int[]{0, 50, 0, 50});
        }

        @Test
        @DisplayName("Should parse 3D region correctly")
        void parse3DRegion_correctly() {
            SpatialRegion region = createSpatialRegion(new int[]{0, 100, 0, 100, 0, 50});
            
            assertThat(region).isNotNull();
            assertThat(region.getDimensions()).isEqualTo(3);
            assertThat(region.bounds).isEqualTo(new int[]{0, 100, 0, 100, 0, 50});
        }

        @Test
        @DisplayName("Should parse 4D region correctly")
        void parse4DRegion_correctly() {
            SpatialRegion region = createSpatialRegion(new int[]{0, 10, 0, 10, 0, 5, 0, 5});
            
            assertThat(region).isNotNull();
            assertThat(region.getDimensions()).isEqualTo(4);
            assertThat(region.bounds).isEqualTo(new int[]{0, 10, 0, 10, 0, 5, 0, 5});
        }

        @Test
        @DisplayName("Should throw exception for odd number of coordinates")
        void oddNumberOfCoordinates_throwsException() {
            int[] bounds = new int[]{1, 2, 3};
            
            assertThatThrownBy(() -> createSpatialRegion(bounds))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("even number of values");
        }

        @Test
        @DisplayName("Should handle negative coordinates")
        void negativeCoordinates_handledCorrectly() {
            SpatialRegion region = createSpatialRegion(new int[]{-10, 10, -5, 5});
            
            assertThat(region).isNotNull();
            assertThat(region.getDimensions()).isEqualTo(2);
            assertThat(region.bounds).isEqualTo(new int[]{-10, 10, -5, 5});
        }

        @Test
        @DisplayName("Should handle large coordinates")
        void largeCoordinates_handledCorrectly() {
            SpatialRegion region = createSpatialRegion(new int[]{0, 1000000, 0, 1000000});
            
            assertThat(region).isNotNull();
            assertThat(region.getDimensions()).isEqualTo(2);
            assertThat(region.bounds).isEqualTo(new int[]{0, 1000000, 0, 1000000});
        }
    }

    @Nested
    @DisplayName("Controller Construction")
    class ControllerConstruction {

        @Test
        @DisplayName("Should create controller with valid dependencies")
        void createControllerWithValidDependencies() {
            ServiceRegistry serviceRegistry = new ServiceRegistry();
            IDatabaseReaderProvider mockDatabase = mock(IDatabaseReaderProvider.class);
            serviceRegistry.register(IDatabaseReaderProvider.class, mockDatabase);
            
            Config config = ConfigFactory.parseString("runId = \"test_run\"");
            
            EnvironmentController testController = new EnvironmentController(serviceRegistry, config);
            
            assertThat(testController).isNotNull();
        }

        @Test
        @DisplayName("Should create controller with default configuration")
        void createControllerWithDefaultConfiguration() {
            ServiceRegistry serviceRegistry = new ServiceRegistry();
            IDatabaseReaderProvider mockDatabase = mock(IDatabaseReaderProvider.class);
            serviceRegistry.register(IDatabaseReaderProvider.class, mockDatabase);
            
            Config config = ConfigFactory.empty();
            
            EnvironmentController controllerWithDefault = new EnvironmentController(serviceRegistry, config);
            
            assertThat(controllerWithDefault).isNotNull();
        }
    }

    @Nested
    @DisplayName("SpatialRegion Serialization")
    class SpatialRegionSerialization {

        @Test
        @DisplayName("Should serialize SpatialRegion correctly")
        void serializeSpatialRegion_correctly() throws Exception {
            SpatialRegion region = createSpatialRegion(new int[]{0, 100, 0, 50});
            
            String json = objectMapper.writeValueAsString(region);
            
            assertThat(json).contains("\"bounds\":[0,100,0,50]");
        }
    }

    @Nested
    @DisplayName("Data Class Validation")
    class DataClassValidation {

        @Test
        @DisplayName("Should validate SpatialRegion with negative coordinates")
        void validateSpatialRegionWithNegativeCoordinates() {
            SpatialRegion region = createSpatialRegion(new int[]{-10, 10, -5, 5});
            
            assertThat(region).isNotNull();
            assertThat(region.getDimensions()).isEqualTo(2);
            assertThat(region.bounds).isEqualTo(new int[]{-10, 10, -5, 5});
        }

        @Test
        @DisplayName("Should validate SpatialRegion with large coordinates")
        void validateSpatialRegionWithLargeCoordinates() {
            SpatialRegion region = createSpatialRegion(new int[]{0, 1000000, 0, 1000000});
            
            assertThat(region).isNotNull();
            assertThat(region.getDimensions()).isEqualTo(2);
            assertThat(region.bounds).isEqualTo(new int[]{0, 1000000, 0, 1000000});
        }
    }

    // Helper method for creating test data
    private SpatialRegion createSpatialRegion(int[] bounds) {
        return new SpatialRegion(bounds);
    }
}
