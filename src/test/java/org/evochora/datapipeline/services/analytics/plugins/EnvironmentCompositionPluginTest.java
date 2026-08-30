package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.delta.ICellStateSource;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

@Tag("unit")
class EnvironmentCompositionPluginTest {

    /** A molecule type outside the seven the schema knows; 0x00 to 0x06 are taken. */
    private static final int UNKNOWN_TYPE = 0x07 << Config.TYPE_SHIFT;

    private EnvironmentCompositionPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new EnvironmentCompositionPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "env")));
        plugin.initialize(null);
    }

    @Test
    void countsEachMoleculeTypeIntoItsOwnColumn() {
        FixedCellState cells = new FixedCellState(100)
                .occupied(Config.TYPE_CODE, 1)
                .occupied(Config.TYPE_CODE, 2)
                .occupied(Config.TYPE_DATA, 7)
                .occupied(Config.TYPE_ENERGY, 7)
                .occupied(Config.TYPE_STRUCTURE, 7)
                .occupied(Config.TYPE_LABEL, 12345)
                .occupiedRaw(new Molecule(Config.TYPE_CODE, 0).toInt(), 1)  // CODE:0 owned - counts as empty
                .occupiedRaw(UNKNOWN_TYPE | 7, 0);                         // a type no column covers

        Object[] row = plugin.extractRows(tick(1L), cells).get(0);

        // Schema: tick, code, data, energy, structure, label, labelref, register, unknown, empty
        assertThat(row[0]).isEqualTo(1L);
        assertThat(row[1]).isEqualTo(2L);   // code, only where the value is non-zero
        assertThat(row[2]).isEqualTo(1L);   // data
        assertThat(row[3]).isEqualTo(1L);   // energy
        assertThat(row[4]).isEqualTo(1L);   // structure
        assertThat(row[5]).isEqualTo(1L);   // label
        assertThat(row[6]).isEqualTo(0L);   // labelref
        assertThat(row[7]).isEqualTo(0L);   // register
        assertThat(row[8]).isEqualTo(1L);   // unknown
    }

    /**
     * Empty cells are what is left of the world beyond the occupied ones, so their count follows
     * from the size of the state alone and needs nothing else to be known about the run.
     */
    @Test
    void emptyCellsAreTheRestOfTheWorld() {
        FixedCellState cells = new FixedCellState(100)
                .occupied(Config.TYPE_CODE, 1)
                .occupied(Config.TYPE_DATA, 7);

        Object[] row = plugin.extractRows(tick(1L), cells).get(0);

        assertThat(row[9]).isEqualTo(98L);
    }

    @Test
    void emptyCellsNeverGoNegative() {
        FixedCellState cells = new FixedCellState(1)
                .occupied(Config.TYPE_CODE, 1)
                .occupied(Config.TYPE_DATA, 7)
                .occupied(Config.TYPE_ENERGY, 7);

        Object[] row = plugin.extractRows(tick(1L), cells).get(0);

        assertThat(row[9]).isEqualTo(0L);
    }

    /**
     * The metric reads the environment, so being called without one is a defect in the caller,
     * not a case to be papered over with zeroed counts.
     */
    @Test
    void refusesToRunWithoutAnEnvironment() {
        assertThatThrownBy(() -> plugin.extractRows(tick(1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("environment");
    }

    @Test
    void declaresThatItNeedsTheEnvironment() {
        assertThat(plugin.needsEnvironmentData()).isTrue();
    }

    private TickData tick(long tickNumber) {
        return TickData.newBuilder().setTickNumber(tickNumber).build();
    }

    /** A cell state with a fixed set of occupied cells, handed out in index order. */
    private static final class FixedCellState implements ICellStateSource {
        private final int totalCells;
        private final List<int[]> cells = new ArrayList<>();

        FixedCellState(int totalCells) {
            this.totalCells = totalCells;
        }

        FixedCellState occupied(int type, int value) {
            return occupiedRaw(new Molecule(type, value).toInt(), 0);
        }

        FixedCellState occupiedRaw(int moleculeData, int ownerId) {
            cells.add(new int[]{cells.size(), moleculeData, ownerId});
            return this;
        }

        @Override
        public void forEachOccupiedCell(CellVisitor visitor) {
            for (int[] cell : cells) {
                visitor.visit(cell[0], cell[1], cell[2]);
            }
        }

        @Override
        public int getTotalCells() {
            return totalCells;
        }
    }
}
