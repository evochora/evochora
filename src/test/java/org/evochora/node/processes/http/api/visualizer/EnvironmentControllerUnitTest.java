/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.node.processes.http.api.visualizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.database.dto.CellWithCoordinates;
import org.evochora.datapipeline.api.resources.database.dto.SpatialRegion;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 3 unit tests: HTTP request parsing and response formatting (no database I/O)
 * <p>
 * Tests focus on data classes and JSON serialization without database dependencies:
 * <ul>
 *   <li>SpatialRegion parsing and validation</li>
 *   <li>CellWithCoordinates serialization</li>
 *   <li>Controller construction and configuration</li>
 *   <li>JSON response formatting</li>
 * </ul>
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
    @DisplayName("JSON Serialization")
    class JsonSerialization {

        @Test
        @DisplayName("Should serialize environment response correctly")
        void serializeEnvironmentResponse_correctly() throws Exception {
            // Create test data
            CellWithCoordinates cell1 = createCellWithCoordinates(new int[]{5, 10}, "DATA", 255, 7);
            CellWithCoordinates cell2 = createCellWithCoordinates(new int[]{6, 11}, "ENERGY", 128, 8);
            
            // Create mock response structure
            var response = new Object() {
                public final int tick = 100;
                public final String runId = "test_run";
                public final CellWithCoordinates[] cells = {cell1, cell2};
            };
            
            String json = objectMapper.writeValueAsString(response);
            
            assertThat(json).contains("\"tick\":100");
            assertThat(json).contains("\"runId\":\"test_run\"");
            assertThat(json).contains("\"cells\":[");
            assertThat(json).contains("\"coordinates\":[5,10]");
            assertThat(json).contains("\"coordinates\":[6,11]");
            assertThat(json).contains("\"moleculeType\":\"DATA\"");
            assertThat(json).contains("\"moleculeType\":\"ENERGY\"");
            assertThat(json).contains("\"moleculeValue\":255");
            assertThat(json).contains("\"moleculeValue\":128");
            assertThat(json).contains("\"ownerId\":7");
            assertThat(json).contains("\"ownerId\":8");
        }

        @Test
        @DisplayName("Should serialize single cell correctly")
        void serializeSingleCell_correctly() throws Exception {
            CellWithCoordinates cell = createCellWithCoordinates(new int[]{42, 73}, "DATA", 255, 7);
            
            String json = objectMapper.writeValueAsString(cell);
            
            assertThat(json).contains("\"coordinates\":[42,73]");
            assertThat(json).contains("\"moleculeType\":\"DATA\"");
            assertThat(json).contains("\"moleculeValue\":255");
            assertThat(json).contains("\"ownerId\":7");
        }

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

        @Test
        @DisplayName("Should validate CellWithCoordinates with different dimensions")
        void validateCellWithCoordinatesWithDifferentDimensions() {
            CellWithCoordinates cell2D = createCellWithCoordinates(new int[]{5, 10}, "DATA", 255, 7);
            CellWithCoordinates cell3D = createCellWithCoordinates(new int[]{5, 10, 15}, "ENERGY", 128, 8);
            CellWithCoordinates cell4D = createCellWithCoordinates(new int[]{1, 2, 3, 4}, "STRUCTURE", 64, 9);
            
            assertThat(cell2D.coordinates()).isEqualTo(new int[]{5, 10});
            assertThat(cell3D.coordinates()).isEqualTo(new int[]{5, 10, 15});
            assertThat(cell4D.coordinates()).isEqualTo(new int[]{1, 2, 3, 4});
        }

        @Test
        @DisplayName("Should validate CellWithCoordinates with zero values")
        void validateCellWithCoordinatesWithZeroValues() {
            CellWithCoordinates cell = createCellWithCoordinates(new int[]{0, 0}, "CODE", 0, 0);
            
            assertThat(cell.coordinates()).isEqualTo(new int[]{0, 0});
            assertThat(cell.moleculeType()).isEqualTo("CODE");
            assertThat(cell.moleculeValue()).isEqualTo(0);
            assertThat(cell.ownerId()).isEqualTo(0);
        }
    }

    // Helper methods for creating test data
    private SpatialRegion createSpatialRegion(int[] bounds) {
        return new SpatialRegion(bounds);
    }

    private CellWithCoordinates createCellWithCoordinates(int[] coordinates, String moleculeType, int moleculeValue, int ownerId) {
        return new CellWithCoordinates(coordinates, moleculeType, moleculeValue, ownerId, null);
    }
}