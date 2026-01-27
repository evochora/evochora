package org.evochora.node.processes.http.api.visualizer;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.node.processes.http.api.visualizer.MinimapAggregator.MinimapResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MinimapAggregator}.
 * <p>
 * Tests cover:
 * <ul>
 *   <li>Aspect ratio preservation for various world shapes</li>
 *   <li>Priority-based cell type aggregation</li>
 *   <li>Edge cases (empty worlds, single cells)</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("MinimapAggregator Unit Tests")
class MinimapAggregatorUnitTest {

    private MinimapAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new MinimapAggregator();
    }

    @Nested
    @DisplayName("Aspect Ratio Preservation")
    class AspectRatioTests {

        @Test
        @DisplayName("Wide world (4:3) produces 150x112 minimap")
        void wideWorld_preservesAspectRatio() {
            var envProps = new EnvironmentProperties(new int[]{4000, 3000}, false);
            var columns = createEmptyColumns();

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            assertThat(result.width()).isEqualTo(150);
            assertThat(result.height()).isEqualTo(113); // 150 * 3000/4000 = 112.5 → 113
        }

        @Test
        @DisplayName("Tall world (3:4) produces 112x150 minimap")
        void tallWorld_preservesAspectRatio() {
            var envProps = new EnvironmentProperties(new int[]{3000, 4000}, false);
            var columns = createEmptyColumns();

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            assertThat(result.width()).isEqualTo(113); // 150 * 3000/4000 = 112.5 → 113
            assertThat(result.height()).isEqualTo(150);
        }

        @Test
        @DisplayName("Square world (1:1) produces 150x150 minimap")
        void squareWorld_preservesAspectRatio() {
            var envProps = new EnvironmentProperties(new int[]{1000, 1000}, false);
            var columns = createEmptyColumns();

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            assertThat(result.width()).isEqualTo(150);
            assertThat(result.height()).isEqualTo(150);
        }

        @Test
        @DisplayName("Very wide world (10:1) produces 150x15 minimap")
        void veryWideWorld_preservesAspectRatio() {
            var envProps = new EnvironmentProperties(new int[]{1000, 100}, false);
            var columns = createEmptyColumns();

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            assertThat(result.width()).isEqualTo(150);
            assertThat(result.height()).isEqualTo(15);
        }
    }

    @Nested
    @DisplayName("Cell Type Aggregation")
    class AggregationTests {

        @Test
        @DisplayName("Single cell maps to correct minimap position")
        void singleCell_mapsCorrectly() {
            var envProps = new EnvironmentProperties(new int[]{150, 150}, false);
            // Place a STRUCTURE cell at (75, 75) - center of world
            var columns = createColumns(
                new int[]{75 + 75 * 150}, // flatIndex = x + y * width
                new int[]{packMolecule(3)} // TYPE_STRUCTURE = 3
            );

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            // Center of 150x150 world maps to center of 150x150 minimap
            int minimapIndex = 75 + 75 * 150;
            assertThat(result.cellTypes()[minimapIndex]).isEqualTo((byte) 3);
        }

        @Test
        @DisplayName("STRUCTURE wins over CODE at same position")
        void structureWinsOverCode() {
            var envProps = new EnvironmentProperties(new int[]{150, 150}, false);
            // Place both CODE and STRUCTURE at positions that map to same minimap pixel
            // With 150x150 world and 150x150 minimap, scale is 1:1, so use same position
            int flatIndex = 50 + 50 * 150;
            var columns = createColumns(
                new int[]{flatIndex, flatIndex},
                new int[]{packMolecule(0), packMolecule(3)} // CODE first, then STRUCTURE
            );

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            int minimapIndex = 50 + 50 * 150;
            assertThat(result.cellTypes()[minimapIndex]).isEqualTo((byte) 3); // STRUCTURE wins
        }

        @Test
        @DisplayName("CODE wins over DATA at same position")
        void codeWinsOverData() {
            var envProps = new EnvironmentProperties(new int[]{150, 150}, false);
            int flatIndex = 25 + 25 * 150;
            var columns = createColumns(
                new int[]{flatIndex, flatIndex},
                new int[]{packMolecule(1), packMolecule(0)} // DATA first, then CODE
            );

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            int minimapIndex = 25 + 25 * 150;
            assertThat(result.cellTypes()[minimapIndex]).isEqualTo((byte) 0); // CODE wins
        }

        @Test
        @DisplayName("Downsampling aggregates multiple cells to one pixel")
        void downsampling_aggregatesCorrectly() {
            // 300x300 world maps to 150x150 minimap (2:1 scale)
            var envProps = new EnvironmentProperties(new int[]{300, 300}, false);
            // Place cells at (0,0), (1,0), (0,1), (1,1) - all map to minimap (0,0)
            var columns = createColumns(
                new int[]{0, 1, 300, 301}, // flatIndices
                new int[]{
                    packMolecule(1), // DATA at (0,0)
                    packMolecule(2), // ENERGY at (1,0)
                    packMolecule(0), // CODE at (0,1)
                    packMolecule(3)  // STRUCTURE at (1,1)
                }
            );

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            assertThat(result.width()).isEqualTo(150);
            assertThat(result.height()).isEqualTo(150);
            // STRUCTURE (priority 4) wins over CODE (priority 3)
            assertThat(result.cellTypes()[0]).isEqualTo((byte) 3);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty columns produces empty minimap")
        void emptyColumns_producesEmptyMinimap() {
            var envProps = new EnvironmentProperties(new int[]{100, 100}, false);
            var columns = createEmptyColumns();

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            // All bytes should be 0 (empty)
            for (byte b : result.cellTypes()) {
                assertThat(b).isEqualTo((byte) 0);
            }
        }

        @Test
        @DisplayName("Returns null for invalid world shape")
        void invalidWorldShape_returnsNull() {
            var envProps = new EnvironmentProperties(new int[]{0, 100}, false);
            var columns = createEmptyColumns();

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Returns null for 1D world")
        void oneDimensionalWorld_returnsNull() {
            var envProps = new EnvironmentProperties(new int[]{100}, false);
            var columns = createEmptyColumns();

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNull();
        }
    }

    // Helper methods

    private CellDataColumns createEmptyColumns() {
        return CellDataColumns.newBuilder().build();
    }

    private CellDataColumns createColumns(int[] flatIndices, int[] moleculeData) {
        var builder = CellDataColumns.newBuilder();
        for (int idx : flatIndices) {
            builder.addFlatIndices(idx);
        }
        for (int data : moleculeData) {
            builder.addMoleculeData(data);
        }
        // Owner IDs not needed for minimap
        for (int i = 0; i < flatIndices.length; i++) {
            builder.addOwnerIds(0);
        }
        return builder.build();
    }

    /**
     * Packs a raw type value (0-4) into the molecule data format.
     */
    private int packMolecule(int rawType) {
        // Type is stored at bits 20-27 (TYPE_SHIFT = 20)
        return rawType << 20; // Config.TYPE_SHIFT = 20
    }
}
