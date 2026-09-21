package org.evochora.runtime.model;

import org.evochora.runtime.Config;

/**
 * How the value of a molecule is read and written.
 * <p>
 * The value field of a cell is a bit pattern; what it means is declared per molecule type when the
 * type is registered in {@link MoleculeTypeRegistry}. A format knows both directions that follow
 * from that meaning: the integer a consumer outside the instruction-execution path works with, and
 * the text a person reads. Code that shows or exports a value asks the type's format and never
 * decides by the type itself, so that a new type is covered by its registration alone.
 * <p>
 * Thread Safety: the constants are stateless and safe for concurrent use.
 */
public enum MoleculeValueFormat {

    /**
     * The value is a number in two's complement: read sign-extended, written in decimal.
     */
    DECIMAL {
        @Override
        public int read(int valueBits) {
            return Molecule.extractSignedValue(valueBits);
        }

        @Override
        public String write(int valueBits) {
            return Integer.toString(read(valueBits));
        }
    },

    /**
     * The value is a bit pattern without a sign: read as the raw value bits, written as uppercase
     * hexadecimal digits with leading zeros — as many as the value field holds — and no prefix.
     */
    HEX {
        @Override
        public int read(int valueBits) {
            return valueBits & Config.VALUE_MASK;
        }

        @Override
        public String write(int valueBits) {
            char[] digits = new char[HEX_DIGITS];
            int remaining = read(valueBits);
            for (int i = digits.length - 1; i >= 0; i--) {
                digits[i] = HEX_ALPHABET[remaining & 0xF];
                remaining >>>= 4;
            }
            return new String(digits);
        }
    };

    /** The number of hexadecimal digits that hold every bit of the value field. */
    private static final int HEX_DIGITS = (Config.VALUE_BITS + 3) / 4;

    private static final char[] HEX_ALPHABET = "0123456789ABCDEF".toCharArray();

    /**
     * Reads a value in this format.
     *
     * @param valueBits The value bits, or a packed molecule integer; everything above the value
     *                  field is ignored.
     * @return The value as this format defines it.
     */
    public abstract int read(int valueBits);

    /**
     * Writes a value in this format as text for a person to read.
     *
     * @param valueBits The value bits, or a packed molecule integer; everything above the value
     *                  field is ignored.
     * @return The text of the value, without a type prefix.
     */
    public abstract String write(int valueBits);
}
