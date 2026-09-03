package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.evochora.runtime.Config;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The environment's secondary indexes must describe the grid's content alone: the order in which
 * an owner's cells are handed out must not depend on the order they were written, and the set of
 * occupied cells must shrink when ownership operations empty a cell.
 */
@Tag("unit")
class EnvironmentIndexConsistencyTest {

    @Test
    void ownedCellsInIndexOrder_areIndependentOfInsertionOrder() {
        List<int[]> positions = new ArrayList<>();
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 30; x++) {
                positions.add(new int[]{x, y});
            }
        }
        Environment ascending = fill(positions);
        Collections.shuffle(positions, new Random(3L));
        Environment permuted = fill(positions);

        List<Integer> expected = new ArrayList<>();
        ascending.visitCellsOwnedBy(1, cell -> expected.add(ascending.properties.toFlatIndex(cell.coordinate())));
        List<Integer> actual = new ArrayList<>();
        permuted.visitCellsOwnedBy(1, cell -> actual.add(permuted.properties.toFlatIndex(cell.coordinate())));

        assertThat(expected).hasSize(900).as("visited in ascending flat-index order").isSorted();
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void occupiedCells_areIteratedInIndexOrderRegardlessOfWriteHistory() {
        List<int[]> positions = new ArrayList<>();
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 30; x++) {
                positions.add(new int[]{x, y});
            }
        }
        Environment ascending = fill(positions);
        Collections.shuffle(positions, new Random(5L));
        Environment permuted = fill(positions);
        // Churn the permuted one: remove and re-add a block, which reshuffles a hash-based index
        for (int x = 0; x < 30; x++) {
            permuted.setMolecule(new Molecule(Config.TYPE_CODE, 0), 0, new int[]{x, 7});
        }
        for (int x = 29; x >= 0; x--) {
            permuted.setMolecule(new Molecule(Config.TYPE_DATA, x + 7), 1, new int[]{x, 7});
        }

        List<Integer> expected = new ArrayList<>();
        ascending.forEachOccupiedCellInFlatIndexOrder((flatIndex, molecule, owner) -> expected.add(flatIndex));
        List<Integer> actual = new ArrayList<>();
        permuted.forEachOccupiedCellInFlatIndexOrder((flatIndex, molecule, owner) -> actual.add(flatIndex));

        assertThat(expected).hasSize(900).isSorted();
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void clearOwnershipFor_dropsEmptiedCellsFromTheOccupiedSet() {
        Environment env = new Environment(new EnvironmentProperties(new int[]{32, 32}, true));
        env.setMolecule(new Molecule(Config.TYPE_CODE, 0), 5, new int[]{1, 1}); // empty but owned
        env.setMolecule(new Molecule(Config.TYPE_DATA, 3), 5, new int[]{2, 1}); // content and owned
        assertThat(occupied(env)).isEqualTo(2);

        env.clearOwnershipFor(5);

        assertThat(env.getOwnerId(1, 1)).isZero();
        assertThat(occupied(env)).as("the emptied cell leaves the occupied set, the data cell stays").isEqualTo(1);
    }

    @Test
    void transferOwnership_keepsTheOccupiedSetConsistent() {
        Environment env = new Environment(new EnvironmentProperties(new int[]{32, 32}, true));
        env.setMolecule(new Molecule(Config.TYPE_CODE, 0, 1), 5, new int[]{1, 1}); // empty, owned, marker 1

        env.transferOwnership(5, 0, 1); // hand the marked cell to "nobody"

        assertThat(env.getOwnerId(1, 1)).isZero();
        assertThat(occupied(env)).isZero();
    }

    @Test
    void clearMarkersFor_keepsTheOccupiedSetConsistent() {
        Environment env = new Environment(new EnvironmentProperties(new int[]{32, 32}, true));
        env.setMolecule(new Molecule(Config.TYPE_DATA, 9, 1), 5, new int[]{1, 1});

        env.clearMarkersFor(5, 1);

        assertThat(env.getMolecule(1, 1).isEmpty()).isTrue();
        assertThat(env.getOwnerId(1, 1)).isZero();
        assertThat(occupied(env)).isZero();
    }

    private static Environment fill(List<int[]> positions) {
        Environment env = new Environment(new EnvironmentProperties(new int[]{32, 32}, true));
        for (int[] p : positions) {
            env.setMolecule(new Molecule(Config.TYPE_DATA, p[0] + p[1]), 1, p);
        }
        return env;
    }

    private static int occupied(Environment env) {
        int[] count = {0};
        env.forEachOccupiedCellInFlatIndexOrder((flatIndex, molecule, owner) -> count[0]++);
        return count[0];
    }
}
