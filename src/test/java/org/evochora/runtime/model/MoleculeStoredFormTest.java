package org.evochora.runtime.model;

import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.runtime.Config;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Molecule#storedFormOfWrite(int, int)}.
 * <p>
 * Covers the three parts of the write rule: the {@code CODE:0} exception, the conversion of DATA
 * written in the ephemeral marker class into STATE, and the marker stamping that leaves every
 * other molecule's type untouched.
 */
@Tag("unit")
class MoleculeStoredFormTest {

    private static Molecule stored(Molecule written, int markerRegister) {
        return Molecule.fromInt(Molecule.storedFormOfWrite(written.toInt(), markerRegister));
    }

    @Test
    void testDataWrittenWithoutMarkerBecomesState() {
        Molecule result = stored(new Molecule(Config.TYPE_DATA, 89), 0);

        assertThat(result.type()).isEqualTo(Config.TYPE_STATE);
        assertThat(result.value()).isEqualTo(89);
        assertThat(result.marker()).isEqualTo(0);
    }

    @Test
    void testNegativeDataValueSurvivesTheConversion() {
        Molecule result = stored(new Molecule(Config.TYPE_DATA, -1), 0);

        assertThat(result.type()).isEqualTo(Config.TYPE_STATE);
        assertThat(result.value()).isEqualTo(-1);
    }

    @Test
    void testDataWrittenWithMarkerKeepsItsType() {
        Molecule result = stored(new Molecule(Config.TYPE_DATA, 89), 3);

        assertThat(result.type()).isEqualTo(Config.TYPE_DATA);
        assertThat(result.value()).isEqualTo(89);
        assertThat(result.marker()).isEqualTo(3);
    }

    @Test
    void testEveryOtherTypeKeepsItsTypeWithAndWithoutMarker() {
        int[] types = {Config.TYPE_CODE, Config.TYPE_ENERGY, Config.TYPE_STRUCTURE,
                Config.TYPE_LABEL, Config.TYPE_LABELREF, Config.TYPE_REGISTER, Config.TYPE_STATE};
        for (int type : types) {
            Molecule withoutMarker = stored(new Molecule(type, 7), 0);
            assertThat(withoutMarker.type()).as("type kept with marker register 0").isEqualTo(type);
            assertThat(withoutMarker.value()).isEqualTo(7);
            assertThat(withoutMarker.marker()).isEqualTo(0);

            Molecule withMarker = stored(new Molecule(type, 7), 5);
            assertThat(withMarker.type()).as("type kept with a non-zero marker register").isEqualTo(type);
            assertThat(withMarker.value()).isEqualTo(7);
            assertThat(withMarker.marker()).isEqualTo(5);
        }
    }

    @Test
    void testEmptyCellIsStoredWithoutMarker() {
        // CODE:0 is the empty cell and never carries a marker, whatever the marker register holds.
        assertThat(Molecule.storedFormOfWrite(new Molecule(Config.TYPE_CODE, 0).toInt(), 9)).isZero();

        Molecule result = stored(new Molecule(Config.TYPE_CODE, 0), 9);
        assertThat(result.type()).isEqualTo(Config.TYPE_CODE);
        assertThat(result.value()).isEqualTo(0);
        assertThat(result.marker()).isEqualTo(0);
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*Molecule.*",
               messagePattern = "CODE:0 molecule with marker.*")
    void testAWrittenEmptyCellCarryingAMarkerIsReported() {
        // A CODE:0 that already carries marker bits breaks the empty-cell invariant before it
        // reaches the grid; it is stored empty and the violation is reported.
        int written = ((4 & Config.MARKER_VALUE_MASK) << Config.MARKER_SHIFT) | Config.TYPE_CODE;

        assertThat(Molecule.storedFormOfWrite(written, 0)).isZero();
    }

    @Test
    void testTheMarkerRegisterReplacesAMarkerTheWrittenValueCarries() {
        Molecule result = stored(new Molecule(Config.TYPE_STRUCTURE, 12, 4), 2);

        assertThat(result.type()).isEqualTo(Config.TYPE_STRUCTURE);
        assertThat(result.marker()).isEqualTo(2);
    }
}
