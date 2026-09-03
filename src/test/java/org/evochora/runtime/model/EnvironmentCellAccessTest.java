package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.evochora.runtime.label.PreExpandedHammingStrategy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The environment's coordinate-only surface: in-range accessors, the owned-cell view and the
 * flat-index visits used for serialization. Every check runs under the tiled layout, where
 * the layout order differs from the flat-index order.
 */
@Tag("unit")
class EnvironmentCellAccessTest {

    private static Environment tiled() {
        return new Environment(new EnvironmentProperties(new int[]{64, 64}, true), new PreExpandedHammingStrategy(), 32);
    }

    private static Molecule data(int value) {
        return new Molecule(Config.TYPE_DATA, value);
    }

    @Test
    void inRangeAccessorsAgreeWithTheNormalizingOnes() {
        Environment env = tiled();
        int[] coord = {40, 3};
        env.setMoleculeAt(coord, data(7), 5);

        assertThat(env.getMoleculeIntAt(coord)).isEqualTo(env.getMolecule(coord).toInt());
        assertThat(env.getOwnerIdAt(coord)).isEqualTo(5).isEqualTo(env.getOwnerId(coord));

        env.setMoleculeAt(coord, data(8));
        assertThat(env.getMolecule(coord).value()).isEqualTo(8);
        assertThat(env.getOwnerId(coord)).as("a write without owner keeps the owner").isEqualTo(5);
    }

    @Test
    void aCoordinateOutsideTheWorldIsRejectedInsteadOfAddressingAnotherCell() {
        Environment env = tiled();
        int[][] outside = {{64, 0}, {0, 64}, {-1, 5}, {5, -1}, {96, 3}};
        for (int[] coord : outside) {
            String expected = "Coordinate " + java.util.Arrays.toString(coord) + " lies outside the world of shape [64, 64]";
            assertThatThrownBy(() -> env.getMoleculeIntAt(coord)).isInstanceOf(IllegalArgumentException.class).hasMessage(expected);
            assertThatThrownBy(() -> env.getOwnerIdAt(coord)).isInstanceOf(IllegalArgumentException.class).hasMessage(expected);
            assertThatThrownBy(() -> env.setMoleculeAt(coord, data(1))).isInstanceOf(IllegalArgumentException.class).hasMessage(expected);
            assertThatThrownBy(() -> env.setMoleculeAt(coord, data(1), 2)).isInstanceOf(IllegalArgumentException.class).hasMessage(expected);
            assertThatThrownBy(() -> env.getIndexFromCoordinate(coord)).isInstanceOf(IllegalArgumentException.class).hasMessage(expected);
        }
        List<Integer> occupied = new ArrayList<>();
        env.forEachOccupiedCellInFlatIndexOrder((flatIndex, molecule, owner) -> occupied.add(flatIndex));
        assertThat(occupied).as("nothing was written").isEmpty();
    }

    @Test
    void inRangeWritesAreTrackedLikeEveryOtherWrite() {
        Environment env = tiled();
        env.setMoleculeAt(new int[]{33, 1}, new Molecule(Config.TYPE_LABEL, 77), 2);

        List<Integer> occupied = new ArrayList<>();
        env.forEachOccupiedCellInFlatIndexOrder((flatIndex, molecule, owner) -> occupied.add(flatIndex));
        assertThat(occupied).containsExactly(env.properties.toFlatIndex(new int[]{33, 1}));
        assertThat(env.getLabelIndex().getCandidates(77)).hasSize(1);
        List<Integer> changed = new ArrayList<>();
        env.forEachCellChangedSinceLastSample((flatIndex, molecule, owner) -> changed.add(flatIndex));
        assertThat(changed).isEqualTo(occupied);
    }

    @Test
    void ownedCellsAreViewedInFlatIndexOrderAndWritesGoThrough() {
        Environment env = tiled();
        int[][] cells = {{35, 2}, {2, 35}, {63, 0}, {0, 63}, {1, 1}};
        for (int[] cell : cells) {
            env.setMoleculeAt(cell, data(cell[0]), 9);
        }
        env.setMoleculeAt(new int[]{5, 5}, data(1), 4);

        List<Integer> visited = new ArrayList<>();
        env.visitCellsOwnedBy(9, view -> {
            visited.add(env.properties.toFlatIndex(view.coordinate()));
            assertThat(view.ownerId()).isEqualTo(9);
            assertThat(view.moleculeInt()).isEqualTo(data(view.coordinate()[0]).toInt());
            assertThat(view.molecule().value()).isEqualTo(view.coordinate()[0]);
            view.setMolecule(data(100 + view.coordinate()[0]));
        });

        assertThat(visited).hasSize(cells.length).isSorted();
        for (int[] cell : cells) {
            assertThat(env.getMolecule(cell).value()).isEqualTo(100 + cell[0]);
            assertThat(env.getOwnerId(cell)).isEqualTo(9);
        }
        List<Integer> none = new ArrayList<>();
        env.visitCellsOwnedBy(42, view -> none.add(1));
        assertThat(none).isEmpty();
    }

    @Test
    void changesSinceSampleAndSinceSnapshotAreTrackedSeparately() {
        Environment env = tiled();
        env.setMoleculeAt(new int[]{10, 10}, data(1));
        env.markSampleTaken();
        env.setMoleculeAt(new int[]{20, 20}, data(2));

        assertThat(flatIndices(env::forEachCellChangedSinceLastSample))
                .containsExactly(env.properties.toFlatIndex(new int[]{20, 20}));
        assertThat(flatIndices(env::forEachCellChangedSinceLastSnapshot))
                .containsExactly(env.properties.toFlatIndex(new int[]{10, 10}), env.properties.toFlatIndex(new int[]{20, 20}));

        env.markSnapshotTaken();
        assertThat(flatIndices(env::forEachCellChangedSinceLastSnapshot)).isEmpty();
        assertThat(flatIndices(env::forEachCellChangedSinceLastSample))
                .as("a snapshot does not forget what changed since the last sample")
                .containsExactly(env.properties.toFlatIndex(new int[]{20, 20}));

        env.resetChangeTracking();
        assertThat(flatIndices(env::forEachCellChangedSinceLastSample)).isEmpty();
    }

    @Test
    void occupiedCellsArriveInAscendingFlatIndexOrder() {
        Environment env = tiled();
        int[][] cells = {{40, 1}, {1, 40}, {33, 33}, {0, 0}, {63, 63}, {2, 0}};
        for (int[] cell : cells) {
            env.setMoleculeAt(cell, data(cell[0] + 1), 1);
        }
        List<Integer> flatIndex = flatIndices(env::forEachOccupiedCellInFlatIndexOrder);

        assertThat(flatIndex).hasSize(cells.length).isSorted();
        env.forEachOccupiedCellInFlatIndexOrder((index, molecule, owner) -> {
            int[] coord = env.properties.flatIndexToCoordinates(index);
            assertThat(molecule).isEqualTo(data(coord[0] + 1).toInt());
            assertThat(owner).isEqualTo(1);
        });
    }

    @Test
    void aViewIsValidOnlyInsideItsVisit() {
        Environment env = tiled();
        env.setMoleculeAt(new int[]{3, 3}, data(3), 8);
        env.setMoleculeAt(new int[]{9, 9}, data(9), 8);

        List<CellView> retained = new ArrayList<>();
        List<int[]> coordinatesSeen = new ArrayList<>();
        env.visitCellsOwnedBy(8, view -> {
            retained.add(view);
            coordinatesSeen.add(view.coordinate());
        });

        assertThat(coordinatesSeen.get(0)).as("the coordinate buffer is shared between cells").isSameAs(coordinatesSeen.get(1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> retained.get(0).moleculeInt())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside the visit");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> retained.get(0).setMolecule(data(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(env.getMolecule(9, 9).value()).as("nothing was written after the visit").isEqualTo(9);
    }

    @Test
    void aNestedOwnedCellVisitFailsInsteadOfCorruptingTheRunningOne() {
        Environment env = tiled();
        env.setMoleculeAt(new int[]{1, 1}, data(1), 4);
        env.setMoleculeAt(new int[]{2, 2}, data(2), 5);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                env.visitCellsOwnedBy(4, view -> env.visitCellsOwnedBy(5, inner -> { })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be nested");
        List<Integer> afterwards = new ArrayList<>();
        env.visitCellsOwnedBy(5, view -> afterwards.add(view.moleculeInt()));
        assertThat(afterwards).as("the environment visits again after the failed nested visit").containsExactly(data(2).toInt());
    }

    @Test
    void aNestedFlatIndexVisitFailsInsteadOfCorruptingTheBatch() {
        Environment env = tiled();
        env.setMoleculeAt(new int[]{1, 1}, data(1));
        env.setMoleculeAt(new int[]{2, 2}, data(2));

        List<Integer> outer = new ArrayList<>();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                env.forEachOccupiedCellInFlatIndexOrder((flatIndex, molecule, owner) -> {
                    outer.add(flatIndex);
                    env.forEachCellChangedSinceLastSample((c, m, o) -> { });
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");
        assertThat(flatIndices(env::forEachOccupiedCellInFlatIndexOrder))
                .as("the environment visits again after the failed nested visit").hasSize(2);
    }

    private static List<Integer> flatIndices(java.util.function.Consumer<FlatIndexCellVisitor> visit) {
        List<Integer> out = new ArrayList<>();
        visit.accept((flatIndex, molecule, owner) -> out.add(flatIndex));
        return out;
    }
}
