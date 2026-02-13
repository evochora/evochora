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
 *   <li>Ownership aggregation (dominant owner per minimap pixel)</li>
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
        @DisplayName("Wide world (4:3) produces 300x225 minimap")
        void wideWorld_preservesAspectRatio() {
            var envProps = new EnvironmentProperties(new int[]{4000, 3000}, false);
            var columns = createEmptyColumns();

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            assertThat(result.width()).isEqualTo(300);
            assertThat(result.height()).isEqualTo(225); // 300 * 3000/4000 = 225
            assertThat(result.ownerIds()).hasSize(300 * 225);
        }

        @Test
        @DisplayName("Tall world (3:4) produces 225x300 minimap")
        void tallWorld_preservesAspectRatio() {
            var envProps = new EnvironmentProperties(new int[]{3000, 4000}, false);
            var columns = createEmptyColumns();

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            assertThat(result.width()).isEqualTo(225); // 300 * 3000/4000 = 225
            assertThat(result.height()).isEqualTo(300);
            assertThat(result.ownerIds()).hasSize(225 * 300);
        }

        @Test
        @DisplayName("Square world (1:1) produces 300x300 minimap")
        void squareWorld_preservesAspectRatio() {
            var envProps = new EnvironmentProperties(new int[]{1000, 1000}, false);
            var columns = createEmptyColumns();

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            assertThat(result.width()).isEqualTo(300);
            assertThat(result.height()).isEqualTo(300);
            assertThat(result.ownerIds()).hasSize(300 * 300);
        }

        @Test
        @DisplayName("Very wide world (10:1) produces 300x30 minimap")
        void veryWideWorld_preservesAspectRatio() {
            var envProps = new EnvironmentProperties(new int[]{1000, 100}, false);
            var columns = createEmptyColumns();

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            assertThat(result.width()).isEqualTo(300);
            assertThat(result.height()).isEqualTo(30);
            assertThat(result.ownerIds()).hasSize(300 * 30);
        }
    }

    @Nested
    @DisplayName("Cell Type Aggregation")
    class AggregationTests {

        @Test
        @DisplayName("Single cell maps to correct minimap position")
        void singleCell_mapsCorrectly() {
            // Use 600x600 world which scales to 300x300 minimap (2:1 ratio)
            var envProps = new EnvironmentProperties(new int[]{600, 600}, false);
            // Place a STRUCTURE cell at (300, 300) - center of world
            // flatIndex = x * height + y (row-major)
            var columns = createColumns(
                new int[]{300 * 600 + 300}, // flatIndex = x * height + y
                new int[]{packMolecule(3)} // TYPE_STRUCTURE = 3
            );

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            // Center of 600x600 world (300, 300) maps to center of 300x300 minimap (150, 150)
            // Minimap index: my * width + mx = 150 * 300 + 150
            int minimapIndex = 150 * 300 + 150;
            assertThat(result.cellTypes()[minimapIndex]).isEqualTo((byte) 3);
        }

        @Test
        @DisplayName("STRUCTURE wins over CODE when it has majority")
        void structureWinsOverCode() {
            // Use 600x600 world which scales to 300x300 minimap (2:1 ratio, 4 cells per pixel)
            var envProps = new EnvironmentProperties(new int[]{600, 600}, false);
            // Place 3 STRUCTURE and 1 CODE in same minimap pixel block
            // Cells at (100, 100), (100, 101), (101, 100), (101, 101) all map to minimap pixel (50, 50)
            // flatIndex = x * height + y
            var columns = createColumns(
                new int[]{
                    100 * 600 + 100, // (100, 100) -> CODE
                    100 * 600 + 101, // (100, 101) -> STRUCTURE
                    101 * 600 + 100, // (101, 100) -> STRUCTURE
                    101 * 600 + 101  // (101, 101) -> STRUCTURE
                },
                new int[]{packMolecule(0), packMolecule(3), packMolecule(3), packMolecule(3)}
            );

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            // Minimap pixel: my * width + mx = 50 * 300 + 50
            int minimapIndex = 50 * 300 + 50;
            assertThat(result.cellTypes()[minimapIndex]).isEqualTo((byte) 3); // STRUCTURE wins (majority)
        }

        @Test
        @DisplayName("CODE wins over DATA when it has majority")
        void codeWinsOverData() {
            // Use 600x600 world which scales to 300x300 minimap (2:1 ratio, 4 cells per pixel)
            var envProps = new EnvironmentProperties(new int[]{600, 600}, false);
            // Place 3 CODE and 1 DATA in same minimap pixel block
            // Cells at (50, 50), (50, 51), (51, 50), (51, 51) all map to minimap pixel (25, 25)
            // flatIndex = x * height + y
            var columns = createColumns(
                new int[]{
                    50 * 600 + 50, // (50, 50) -> DATA
                    50 * 600 + 51, // (50, 51) -> CODE
                    51 * 600 + 50, // (51, 50) -> CODE
                    51 * 600 + 51  // (51, 51) -> CODE
                },
                new int[]{packMolecule(1), packMolecule(0), packMolecule(0), packMolecule(0)}
            );

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            // Minimap pixel: my * width + mx = 25 * 300 + 25
            int minimapIndex = 25 * 300 + 25;
            assertThat(result.cellTypes()[minimapIndex]).isEqualTo((byte) 0); // CODE wins (majority)
        }

        @Test
        @DisplayName("Downsampling aggregates multiple cells to one pixel")
        void downsampling_aggregatesCorrectly() {
            // 600x600 world maps to 300x300 minimap (2:1 scale)
            var envProps = new EnvironmentProperties(new int[]{600, 600}, false);
            // Place cells at (0,0), (0,1), (1,0), (1,1) - all map to minimap (0,0)
            // flatIndex = x * height + y
            // With 3 STRUCTURE cells, STRUCTURE wins by majority
            var columns = createColumns(
                new int[]{
                    0 * 600 + 0, // (0,0)
                    0 * 600 + 1, // (0,1)
                    1 * 600 + 0, // (1,0)
                    1 * 600 + 1  // (1,1)
                },
                new int[]{
                    packMolecule(1), // DATA at (0,0)
                    packMolecule(3), // STRUCTURE at (0,1)
                    packMolecule(3), // STRUCTURE at (1,0)
                    packMolecule(3)  // STRUCTURE at (1,1)
                }
            );

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            assertThat(result.width()).isEqualTo(300);
            assertThat(result.height()).isEqualTo(300);
            // STRUCTURE wins by majority (3 out of 4 cells)
            assertThat(result.cellTypes()[0]).isEqualTo((byte) 3);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty columns produces empty minimap")
        void emptyColumns_producesEmptyMinimap() {
            // Use a larger world to ensure proper scaling
            var envProps = new EnvironmentProperties(new int[]{600, 600}, false);
            var columns = createEmptyColumns();

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            // All bytes should be 7 (TYPE_EMPTY) since there are no cells
            for (byte b : result.cellTypes()) {
                assertThat(b).isEqualTo((byte) 7);
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

    @Nested
    @DisplayName("Ownership Aggregation")
    class OwnershipTests {

        @Test
        @DisplayName("Dominant owner wins majority vote for a minimap pixel")
        void dominantOwner_winsMajorityVote() {
            var envProps = new EnvironmentProperties(new int[]{600, 600}, false);
            // 4 cells in same pixel block: 3 owned by org 5, 1 owned by org 3
            var columns = createColumns(
                new int[]{100 * 600 + 100, 100 * 600 + 101, 101 * 600 + 100, 101 * 600 + 101},
                new int[]{packMolecule(0), packMolecule(0), packMolecule(0), packMolecule(0)},
                new int[]{5, 5, 5, 3}
            );

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            int minimapIndex = 50 * 300 + 50;
            assertThat(result.ownerIds()[minimapIndex]).isEqualTo(5);
        }

        @Test
        @DisplayName("Unowned cells produce owner 0")
        void unownedCells_produceZeroOwner() {
            var envProps = new EnvironmentProperties(new int[]{600, 600}, false);
            var columns = createColumns(
                new int[]{0, 1},
                new int[]{packMolecule(0), packMolecule(0)},
                new int[]{0, 0}
            );

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            assertThat(result.ownerIds()[0]).isEqualTo(0);
        }

        @Test
        @DisplayName("Empty minimap has all-zero owner IDs")
        void emptyMinimap_hasAllZeroOwners() {
            var envProps = new EnvironmentProperties(new int[]{600, 600}, false);
            var columns = createEmptyColumns();

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            for (int id : result.ownerIds()) {
                assertThat(id).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("Mixed owned and unowned cells - owned cells determine the owner")
        void mixedOwnership_ownedCellsWin() {
            var envProps = new EnvironmentProperties(new int[]{600, 600}, false);
            // 2 unowned, 2 owned by org 7 - org 7 wins
            var columns = createColumns(
                new int[]{0 * 600 + 0, 0 * 600 + 1, 1 * 600 + 0, 1 * 600 + 1},
                new int[]{packMolecule(0), packMolecule(0), packMolecule(0), packMolecule(0)},
                new int[]{0, 0, 7, 7}
            );

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            assertThat(result.ownerIds()[0]).isEqualTo(7);
        }

        @Test
        @DisplayName("Owner IDs array has correct size")
        void ownerIds_hasCorrectSize() {
            var envProps = new EnvironmentProperties(new int[]{600, 600}, false);
            var columns = createEmptyColumns();

            MinimapResult result = aggregator.aggregate(columns, envProps);

            assertThat(result).isNotNull();
            assertThat(result.ownerIds()).hasSize(result.width() * result.height());
        }
    }

    // Helper methods

    private CellDataColumns createEmptyColumns() {
        return CellDataColumns.newBuilder().build();
    }

    private CellDataColumns createColumns(int[] flatIndices, int[] moleculeData) {
        int[] zeroOwners = new int[flatIndices.length];
        return createColumns(flatIndices, moleculeData, zeroOwners);
    }

    private CellDataColumns createColumns(int[] flatIndices, int[] moleculeData, int[] ownerIds) {
        var builder = CellDataColumns.newBuilder();
        for (int idx : flatIndices) {
            builder.addFlatIndices(idx);
        }
        for (int data : moleculeData) {
            builder.addMoleculeData(data);
        }
        for (int id : ownerIds) {
            builder.addOwnerIds(id);
        }
        return builder.build();
    }

    /**
     * Packs a raw type value (0-4) into the molecule data format.
     * For CODE type (0), includes a non-zero value to distinguish from EMPTY.
     */
    private int packMolecule(int rawType) {
        // Type is stored at bits 20-27 (TYPE_SHIFT = 20)
        // For CODE type, include value=1 so it's not classified as EMPTY
        int value = (rawType == 0) ? 1 : 0;
        return (rawType << 20) | value; // Config.TYPE_SHIFT = 20
    }
}
