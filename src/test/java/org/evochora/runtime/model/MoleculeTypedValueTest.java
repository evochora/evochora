package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The value of a molecule in the format its type declares: read for consumers outside the
 * instruction-execution path, and written as text for a person.
 */
@Tag("unit")
class MoleculeTypedValueTest {

    private static final int TOP_VALUE_BIT = 1 << (Config.VALUE_BITS - 1);

    @Test
    void decimalReadsANumberSignExtendedAndWritesItInDecimal() {
        assertThat(MoleculeValueFormat.DECIMAL.read(Config.VALUE_MASK)).isEqualTo(-1);
        assertThat(MoleculeValueFormat.DECIMAL.read(42)).isEqualTo(42);
        assertThat(MoleculeValueFormat.DECIMAL.write(Config.VALUE_MASK)).isEqualTo("-1");
        assertThat(MoleculeValueFormat.DECIMAL.write(42)).isEqualTo("42");
    }

    @Test
    void hexReadsTheRawBitsAndWritesFiveUppercaseDigitsWithLeadingZeros() {
        assertThat(MoleculeValueFormat.HEX.read(TOP_VALUE_BIT)).isEqualTo(TOP_VALUE_BIT);
        assertThat(MoleculeValueFormat.HEX.write(0)).isEqualTo("00000");
        assertThat(MoleculeValueFormat.HEX.write(0x3A7F)).isEqualTo("03A7F");
        assertThat(MoleculeValueFormat.HEX.write(Config.VALUE_MASK)).isEqualTo("FFFFF");
    }

    @Test
    void aFormatIgnoresEverythingAboveTheValueField() {
        int packed = (0xF << Config.MARKER_SHIFT) | Config.TYPE_LABEL | 0xABCDE;
        assertThat(MoleculeValueFormat.HEX.read(packed)).isEqualTo(0xABCDE);
        assertThat(MoleculeValueFormat.HEX.write(packed)).isEqualTo("ABCDE");
        // A bit pattern that was read through the signed path arrives sign-extended
        assertThat(MoleculeValueFormat.HEX.write(Molecule.extractSignedValue(TOP_VALUE_BIT))).isEqualTo("80000");
        assertThat(MoleculeValueFormat.DECIMAL.read(packed | Config.VALUE_MASK)).isEqualTo(-1);
    }

    @Test
    void aMoleculeIsReadInTheFormatItsTypeDeclares() {
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            MoleculeValueFormat format = MoleculeTypeRegistry.valueFormatOf(type);
            int packed = type | Config.VALUE_MASK;
            assertThat(Molecule.extractTypedValue(packed))
                    .as("value of %s", MoleculeTypeRegistry.typeToName(type))
                    .isEqualTo(format.read(packed));
            assertThat(Molecule.formatValue(packed))
                    .as("text of %s", MoleculeTypeRegistry.typeToName(type))
                    .isEqualTo(format.write(packed));
        }
    }

    @Test
    void aLabelValueWithTheTopBitSetStaysUnsignedWhileANumberTurnsNegative() {
        assertThat(Molecule.extractTypedValue(Config.TYPE_LABEL | TOP_VALUE_BIT)).isEqualTo(TOP_VALUE_BIT);
        assertThat(Molecule.extractTypedValue(Config.TYPE_LABELREF | Config.VALUE_MASK)).isEqualTo(Config.VALUE_MASK);
        assertThat(Molecule.extractTypedValue(Config.TYPE_DATA | Config.VALUE_MASK)).isEqualTo(-1);
    }

    @Test
    void toStringWritesTheValueInTheDeclaredFormat() {
        assertThat(Molecule.fromInt(Config.TYPE_LABEL | TOP_VALUE_BIT).toString()).isEqualTo("LABEL:80000 M:0");
        assertThat(Molecule.fromInt(Config.TYPE_LABELREF | 0xFF).toString()).isEqualTo("LABELREF:000FF M:0");
        assertThat(Molecule.fromInt(Config.TYPE_DATA | Config.VALUE_MASK).toString()).isEqualTo("DATA:-1 M:0");
    }
}
