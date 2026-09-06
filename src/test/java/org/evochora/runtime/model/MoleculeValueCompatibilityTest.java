package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Molecule#areValueCompatible(int, int)}.
 * <p>
 * Covers the three cases the predicate distinguishes: two equal types, the DATA/STATE pair in
 * both directions, and a pair of different types that share no value semantics.
 */
@Tag("unit")
class MoleculeValueCompatibilityTest {

    @Test
    void testEqualTypesAreCompatible() {
        assertThat(Molecule.areValueCompatible(Config.TYPE_DATA, Config.TYPE_DATA)).isTrue();
        assertThat(Molecule.areValueCompatible(Config.TYPE_STATE, Config.TYPE_STATE)).isTrue();
        assertThat(Molecule.areValueCompatible(Config.TYPE_ENERGY, Config.TYPE_ENERGY)).isTrue();
        assertThat(Molecule.areValueCompatible(Config.TYPE_CODE, Config.TYPE_CODE)).isTrue();
    }

    @Test
    void testDataAndStateAreCompatibleInBothDirections() {
        assertThat(Molecule.areValueCompatible(Config.TYPE_DATA, Config.TYPE_STATE)).isTrue();
        assertThat(Molecule.areValueCompatible(Config.TYPE_STATE, Config.TYPE_DATA)).isTrue();
    }

    @Test
    void testOtherTypePairsAreNotCompatible() {
        assertThat(Molecule.areValueCompatible(Config.TYPE_DATA, Config.TYPE_ENERGY)).isFalse();
        assertThat(Molecule.areValueCompatible(Config.TYPE_ENERGY, Config.TYPE_DATA)).isFalse();
        assertThat(Molecule.areValueCompatible(Config.TYPE_CODE, Config.TYPE_DATA)).isFalse();
        assertThat(Molecule.areValueCompatible(Config.TYPE_STATE, Config.TYPE_STRUCTURE)).isFalse();
        assertThat(Molecule.areValueCompatible(Config.TYPE_STATE, Config.TYPE_ENERGY)).isFalse();
    }
}
