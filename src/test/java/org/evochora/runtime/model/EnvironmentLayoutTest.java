package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.evochora.runtime.label.PreExpandedHammingStrategy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The environment's index API under a tiled layout: indices are opaque, every conversion the
 * environment offers agrees with the canonical numbering of {@link EnvironmentProperties}, and a
 * world that does not fit the tile side is rejected at construction.
 */
@Tag("unit")
class EnvironmentLayoutTest {

    private static Environment tiled(int[] shape, boolean toroidal) {
        return new Environment(new EnvironmentProperties(shape, toroidal), new PreExpandedHammingStrategy(), 32);
    }

    @Test
    void everyIndexDecodesToTheCoordinateOfItsCanonicalIndex() {
        Environment env = tiled(new int[]{64, 32, 32}, true);
        BitSet canonicalSeen = new BitSet(env.getTotalCells());
        int[] coord = new int[3];

        for (int index = 0; index < env.getTotalCells(); index++) {
            env.getCoordinateFromIndex(index, coord);
            int canonical = env.toCanonicalIndex(index);

            assertThat(canonical).isEqualTo(env.properties.toFlatIndex(coord));
            assertThat(env.getCoordinateFromIndex(index)).isEqualTo(coord);
            assertThat(env.getIndexFromCoordinate(coord)).isEqualTo(index);
            canonicalSeen.set(canonical);
        }
        assertThat(canonicalSeen.cardinality())
                .as("the canonical indices of all indices form a permutation")
                .isEqualTo(env.getTotalCells());
    }

    @Test
    void aCellWrittenAtACoordinateIsFoundAtThatCoordinate() {
        Environment env = tiled(new int[]{64, 32, 32}, true);
        int[] coord = {63, 31, 2};
        env.setMolecule(Molecule.fromInt(Config.TYPE_DATA | 42), coord);

        int[] seen = {-1, 0};
        env.forEachOccupiedCellInCanonicalOrder((canonical, molecule, owner) -> { seen[0] = canonical; seen[1] = molecule; });

        assertThat(seen[0]).isEqualTo(env.properties.toFlatIndex(coord));
        assertThat(seen[1]).isEqualTo(Config.TYPE_DATA | 42);
        assertThat(env.getMoleculeInt(env.getIndexFromCoordinate(coord))).isEqualTo(Config.TYPE_DATA | 42);
        assertThat(env.getMolecule(coord).toInt()).isEqualTo(Config.TYPE_DATA | 42);
    }

    @Test
    void indexAndCanonicalIndexDifferUnderTiles() {
        Environment env = tiled(new int[]{64, 64}, false);
        env.setMolecule(Molecule.fromInt(Config.TYPE_DATA | 1), new int[]{1, 0});

        int index = env.getIndexFromCoordinate(new int[]{1, 0});

        assertThat(index).as("dimension 0 is contiguous inside a tile").isEqualTo(1);
        assertThat(env.toCanonicalIndex(index)).as("row-major").isEqualTo(64);
    }

    @Test
    void stepFollowsTheTopology() {
        Environment torus = tiled(new int[]{64, 64}, true);
        Environment bounded = tiled(new int[]{64, 64}, false);
        int[] edge = {63, 5};

        assertThat(torus.getCoordinateFromIndex(torus.stepIndex(torus.getIndexFromCoordinate(edge), 0, true)))
                .isEqualTo(new int[]{0, 5});
        assertThat(bounded.stepIndex(bounded.getIndexFromCoordinate(edge), 0, true)).isEqualTo(-1);
        assertThat(bounded.getCoordinateFromIndex(bounded.stepIndex(bounded.getIndexFromCoordinate(edge), 0, false)))
                .isEqualTo(new int[]{62, 5});
    }


    @Test
    void rejectsAWorldThatDoesNotFitTheTileSide() {
        assertThatThrownBy(() -> tiled(new int[]{100, 64}, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("World dimension 0 is 100");
    }
}
