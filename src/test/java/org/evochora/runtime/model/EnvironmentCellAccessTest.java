package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.evochora.runtime.label.PreExpandedHammingStrategy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The environment's coordinate-only surface: in-range accessors, the owned-cell view and the
 * canonical-order visits used for serialization. Every check runs under the tiled layout, where
 * the internal order differs from the canonical one.
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
    void inRangeWritesAreTrackedLikeEveryOtherWrite() {
        Environment env = tiled();
        env.setMoleculeAt(new int[]{33, 1}, new Molecule(Config.TYPE_LABEL, 77), 2);

        List<Integer> occupied = new ArrayList<>();
        env.forEachOccupiedCellInCanonicalOrder((canonical, molecule, owner) -> occupied.add(canonical));
        assertThat(occupied).containsExactly(env.properties.toFlatIndex(new int[]{33, 1}));
        assertThat(env.getLabelIndex().getCandidates(77)).hasSize(1);
        List<Integer> changed = new ArrayList<>();
        env.forEachCellChangedSinceLastSample((canonical, molecule, owner) -> changed.add(canonical));
        assertThat(changed).isEqualTo(occupied);
    }

    @Test
    void ownedCellsAreViewedInCanonicalOrderAndWritesGoThrough() {
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

        assertThat(canonicalIndices(env::forEachCellChangedSinceLastSample))
                .containsExactly(env.properties.toFlatIndex(new int[]{20, 20}));
        assertThat(canonicalIndices(env::forEachCellChangedSinceLastSnapshot))
                .containsExactly(env.properties.toFlatIndex(new int[]{10, 10}), env.properties.toFlatIndex(new int[]{20, 20}));

        env.markSnapshotTaken();
        assertThat(canonicalIndices(env::forEachCellChangedSinceLastSnapshot)).isEmpty();
        assertThat(canonicalIndices(env::forEachCellChangedSinceLastSample))
                .as("a snapshot does not forget what changed since the last sample")
                .containsExactly(env.properties.toFlatIndex(new int[]{20, 20}));

        env.resetChangeTracking();
        assertThat(canonicalIndices(env::forEachCellChangedSinceLastSample)).isEmpty();
    }

    @Test
    void occupiedCellsArriveInAscendingCanonicalOrder() {
        Environment env = tiled();
        int[][] cells = {{40, 1}, {1, 40}, {33, 33}, {0, 0}, {63, 63}, {2, 0}};
        for (int[] cell : cells) {
            env.setMoleculeAt(cell, data(cell[0] + 1), 1);
        }
        List<Integer> canonical = canonicalIndices(env::forEachOccupiedCellInCanonicalOrder);

        assertThat(canonical).hasSize(cells.length).isSorted();
        env.forEachOccupiedCellInCanonicalOrder((index, molecule, owner) -> {
            int[] coord = env.properties.flatIndexToCoordinates(index);
            assertThat(molecule).isEqualTo(data(coord[0] + 1).toInt());
            assertThat(owner).isEqualTo(1);
        });
    }

    private static List<Integer> canonicalIndices(java.util.function.Consumer<CanonicalCellVisitor> visit) {
        List<Integer> out = new ArrayList<>();
        visit.accept((canonical, molecule, owner) -> out.add(canonical));
        return out;
    }
}
