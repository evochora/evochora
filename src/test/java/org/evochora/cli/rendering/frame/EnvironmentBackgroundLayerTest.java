package org.evochora.cli.rendering.frame;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.runtime.Config;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for EnvironmentBackgroundLayer.
 * Tests cell type mapping, snapshot/delta processing, majority voting,
 * generation-based reset, and coordinate mapping.
 */
@Tag("unit")
public class EnvironmentBackgroundLayerTest {

    private static final int EMPTY_COLOR = EnvironmentBackgroundLayer.CELL_COLORS[EnvironmentBackgroundLayer.TYPE_EMPTY];
    private static final int ENERGY_COLOR = EnvironmentBackgroundLayer.CELL_COLORS[EnvironmentBackgroundLayer.TYPE_ENERGY];
    private static final int CODE_COLOR = EnvironmentBackgroundLayer.CELL_COLORS[EnvironmentBackgroundLayer.TYPE_CODE];

    // ========================================================================
    // Cell type mapping
    // ========================================================================

    @Test
    void testGetCellTypeIndex_emptyCell_returnsEmpty() {
        assertThat(EnvironmentBackgroundLayer.getCellTypeIndex(0))
            .isEqualTo(EnvironmentBackgroundLayer.TYPE_EMPTY);
    }

    @Test
    void testGetCellTypeIndex_codeCell_returnsCode() {
        int codeMolecule = Config.TYPE_CODE | 42;
        assertThat(EnvironmentBackgroundLayer.getCellTypeIndex(codeMolecule))
            .isEqualTo(EnvironmentBackgroundLayer.TYPE_CODE);
    }

    @Test
    void testGetCellTypeIndex_energyCell_returnsEnergy() {
        int energyMolecule = Config.TYPE_ENERGY | 1;
        assertThat(EnvironmentBackgroundLayer.getCellTypeIndex(energyMolecule))
            .isEqualTo(EnvironmentBackgroundLayer.TYPE_ENERGY);
    }

    @Test
    void testGetCellTypeIndex_registerCell_returnsRegister() {
        int registerMolecule = Config.TYPE_REGISTER | 5;
        assertThat(EnvironmentBackgroundLayer.getCellTypeIndex(registerMolecule))
            .isEqualTo(EnvironmentBackgroundLayer.TYPE_REGISTER);
    }

    // ========================================================================
    // Rendering with empty state
    // ========================================================================

    @Test
    void testRenderTo_noData_allEmpty() {
        EnvironmentBackgroundLayer layer = new EnvironmentBackgroundLayer(100, 100, 10, 10);
        int[] buffer = new int[100];

        // Process empty snapshot to initialize generation
        layer.processSnapshotCells(CellDataColumns.getDefaultInstance());
        layer.renderTo(buffer);

        for (int px : buffer) {
            assertThat(px).isEqualTo(EMPTY_COLOR);
        }
    }

    // ========================================================================
    // Snapshot processing + majority voting
    // ========================================================================

    @Test
    void testSnapshot_energyCellsDominate_energyColor() {
        // 100x100 world → 10x10 output (scale ~0.1)
        EnvironmentBackgroundLayer layer = new EnvironmentBackgroundLayer(100, 100, 10, 10);
        int[] buffer = new int[100];

        // Fill top-left world block (0-9, 0-9) with ENERGY
        CellDataColumns.Builder cellsBuilder = CellDataColumns.newBuilder();
        for (int wx = 0; wx < 10; wx++) {
            for (int wy = 0; wy < 10; wy++) {
                int flatIndex = wx * 100 + wy;  // column-major: x * height + y
                cellsBuilder.addFlatIndices(flatIndex);
                cellsBuilder.addMoleculeData(Config.TYPE_ENERGY | 1);
            }
        }

        layer.processSnapshotCells(cellsBuilder.build());
        layer.renderTo(buffer);

        // Output pixel (0,0) should be ENERGY_COLOR
        assertThat(buffer[0]).isEqualTo(ENERGY_COLOR);

        // Output pixel (5,5) should still be EMPTY (no cells there)
        assertThat(buffer[5 * 10 + 5]).isEqualTo(EMPTY_COLOR);
    }

    @Test
    void testSnapshot_mixedCells_majorityWins() {
        // 20x20 world → 10x10 output (2x2 cells per pixel)
        EnvironmentBackgroundLayer layer = new EnvironmentBackgroundLayer(20, 20, 10, 10);
        int[] buffer = new int[100];

        // Put 3 ENERGY and 1 CODE cell in the top-left 2x2 block
        CellDataColumns.Builder cellsBuilder = CellDataColumns.newBuilder();
        // (0,0) = ENERGY
        cellsBuilder.addFlatIndices(0 * 20 + 0);
        cellsBuilder.addMoleculeData(Config.TYPE_ENERGY | 1);
        // (0,1) = ENERGY
        cellsBuilder.addFlatIndices(0 * 20 + 1);
        cellsBuilder.addMoleculeData(Config.TYPE_ENERGY | 1);
        // (1,0) = ENERGY
        cellsBuilder.addFlatIndices(1 * 20 + 0);
        cellsBuilder.addMoleculeData(Config.TYPE_ENERGY | 1);
        // (1,1) = CODE
        cellsBuilder.addFlatIndices(1 * 20 + 1);
        cellsBuilder.addMoleculeData(Config.TYPE_CODE | 42);

        layer.processSnapshotCells(cellsBuilder.build());
        layer.renderTo(buffer);

        // ENERGY should win (3 vs 1)
        assertThat(buffer[0]).isEqualTo(ENERGY_COLOR);
    }

    // ========================================================================
    // Delta processing
    // ========================================================================

    @Test
    void testDelta_cellChangesToEmpty_updatesCorrectly() {
        EnvironmentBackgroundLayer layer = new EnvironmentBackgroundLayer(20, 20, 10, 10);
        int[] buffer = new int[100];

        // Snapshot: fill top-left 2x2 with ENERGY
        CellDataColumns.Builder snapshotCells = CellDataColumns.newBuilder();
        for (int wx = 0; wx < 2; wx++) {
            for (int wy = 0; wy < 2; wy++) {
                snapshotCells.addFlatIndices(wx * 20 + wy);
                snapshotCells.addMoleculeData(Config.TYPE_ENERGY | 1);
            }
        }
        layer.processSnapshotCells(snapshotCells.build());
        layer.renderTo(buffer);
        assertThat(buffer[0]).isEqualTo(ENERGY_COLOR);

        // Delta: all 4 cells become empty (moleculeData = 0)
        CellDataColumns.Builder deltaCells = CellDataColumns.newBuilder();
        for (int wx = 0; wx < 2; wx++) {
            for (int wy = 0; wy < 2; wy++) {
                deltaCells.addFlatIndices(wx * 20 + wy);
                deltaCells.addMoleculeData(0);  // empty
            }
        }
        layer.processDeltaCells(deltaCells.build());
        layer.renderTo(buffer);

        // Pixel should now show EMPTY
        assertThat(buffer[0]).isEqualTo(EMPTY_COLOR);
    }

    @Test
    void testDelta_cellChangesType_updatesCorrectly() {
        EnvironmentBackgroundLayer layer = new EnvironmentBackgroundLayer(20, 20, 10, 10);
        int[] buffer = new int[100];

        // Snapshot: ENERGY cells
        CellDataColumns.Builder snapshotCells = CellDataColumns.newBuilder();
        for (int wx = 0; wx < 2; wx++) {
            for (int wy = 0; wy < 2; wy++) {
                snapshotCells.addFlatIndices(wx * 20 + wy);
                snapshotCells.addMoleculeData(Config.TYPE_ENERGY | 1);
            }
        }
        layer.processSnapshotCells(snapshotCells.build());

        // Delta: all become CODE
        CellDataColumns.Builder deltaCells = CellDataColumns.newBuilder();
        for (int wx = 0; wx < 2; wx++) {
            for (int wy = 0; wy < 2; wy++) {
                deltaCells.addFlatIndices(wx * 20 + wy);
                deltaCells.addMoleculeData(Config.TYPE_CODE | 42);
            }
        }
        layer.processDeltaCells(deltaCells.build());
        layer.renderTo(buffer);

        assertThat(buffer[0]).isEqualTo(CODE_COLOR);
    }

    // ========================================================================
    // Generation reset
    // ========================================================================

    @Test
    void testNewSnapshot_resetsOldState() {
        EnvironmentBackgroundLayer layer = new EnvironmentBackgroundLayer(20, 20, 10, 10);
        int[] buffer = new int[100];

        // First snapshot: ENERGY cells in top-left
        CellDataColumns.Builder cells1 = CellDataColumns.newBuilder();
        for (int wx = 0; wx < 2; wx++) {
            for (int wy = 0; wy < 2; wy++) {
                cells1.addFlatIndices(wx * 20 + wy);
                cells1.addMoleculeData(Config.TYPE_ENERGY | 1);
            }
        }
        layer.processSnapshotCells(cells1.build());
        layer.renderTo(buffer);
        assertThat(buffer[0]).isEqualTo(ENERGY_COLOR);

        // Second snapshot: empty (no cells at all)
        layer.processSnapshotCells(CellDataColumns.getDefaultInstance());
        layer.renderTo(buffer);

        // Old ENERGY should be gone — generation reset invalidated it
        assertThat(buffer[0]).isEqualTo(EMPTY_COLOR);
    }

    // ========================================================================
    // Coordinate mapping
    // ========================================================================

    @Test
    void testWorldCoordsToPixelIndex_mapsCorrectly() {
        // 100x100 world → 10x10 output
        EnvironmentBackgroundLayer layer = new EnvironmentBackgroundLayer(100, 100, 10, 10);

        // (0,0) → pixel (0,0) = index 0
        assertThat(layer.worldCoordsToPixelIndex(0, 0)).isEqualTo(0);

        // (50,50) → pixel (5,5) = index 55
        assertThat(layer.worldCoordsToPixelIndex(50, 50)).isEqualTo(5 * 10 + 5);

        // (99,99) → pixel (9,9) = index 99
        assertThat(layer.worldCoordsToPixelIndex(99, 99)).isEqualTo(9 * 10 + 9);
    }

    @Test
    void testWorldCoordsToPixelIndex_clampsEdgeCases() {
        EnvironmentBackgroundLayer layer = new EnvironmentBackgroundLayer(100, 100, 10, 10);

        // Should not exceed bounds
        int idx = layer.worldCoordsToPixelIndex(100, 100);
        assertThat(idx).isLessThan(100);
        assertThat(idx).isGreaterThanOrEqualTo(0);
    }
}
