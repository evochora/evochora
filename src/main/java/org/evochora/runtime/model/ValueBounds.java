package org.evochora.runtime.model;

import org.evochora.runtime.Config;

/**
 * The rules for a value at the edges of the value field of a molecule.
 * <p>
 * A value is {@link Config#VALUE_BITS} wide and read in two's complement, so it lies between
 * {@link #MIN_VALUE} and {@link #MAX_VALUE}. Everything that holds a value — a cell, a data
 * register, a component of a vector — holds one in this range, and this class is the only place
 * that knows the rules for leaving it:
 * <ul>
 *   <li><strong>Quantities saturate.</strong> A computed number — a sum, a product, a negation,
 *       a mutated literal — is computed at full width, and only the result an organism sees is
 *       held at the nearest end of the range ({@link #saturate(long)}). Energy and entropy behave
 *       the same way at their limits, and so does a physical quantity: a number that grows past
 *       the largest value stays the largest value, it does not turn into the smallest one.</li>
 *   <li><strong>Patterns keep their width.</strong> A bitwise operation works on the bit pattern
 *       of the value field. A shift takes its amount literally: a bit shifted out of the field is
 *       gone, and an amount at or beyond the width leaves only the fill
 *       ({@link #shiftLeft(int, int)}, {@link #shiftRight(int, int)}). A rotation goes around the
 *       field ({@link #rotate(int, int)}).</li>
 * </ul>
 * <p>
 * Masking a value to the width when a molecule is packed ({@link Molecule#toInt()}) is a step of
 * storage, not a rule of arithmetic: a number that went past an end and were only masked would
 * come back from the other end. Code that computes a value therefore passes the result through
 * this class before it packs it, and computes no bound of its own.
 */
public final class ValueBounds {

    /** The largest value a molecule holds. */
    public static final int MAX_VALUE = (1 << (Config.VALUE_BITS - 1)) - 1;

    /** The smallest value a molecule holds. */
    public static final int MIN_VALUE = -(1 << (Config.VALUE_BITS - 1));

    private ValueBounds() {
    }

    /**
     * Holds a computed number within the value range.
     *
     * @param value The number, computed at full width.
     * @return The number itself if it lies in the range, otherwise the end of the range it went
     *         past.
     */
    public static int saturate(long value) {
        if (value > MAX_VALUE) {
            return MAX_VALUE;
        }
        if (value < MIN_VALUE) {
            return MIN_VALUE;
        }
        return (int) value;
    }

    /**
     * Shifts the bit pattern of a value to the left, within the width of the value field.
     * <p>
     * The bits shifted out of the field are gone and zeros come in from the right; an amount at or
     * beyond the width leaves the empty pattern. A negative amount shifts to the right by its
     * magnitude, as {@link #shiftRight(int, int)} does.
     *
     * @param value The value whose pattern is shifted.
     * @param amount The number of bit positions, taken literally.
     * @return The shifted pattern as a sign-extended value, as
     *         {@link Molecule#extractSignedValue(int)} returns it.
     */
    public static int shiftLeft(int value, int amount) {
        return shift(value, amount);
    }

    /**
     * Shifts the bit pattern of a value to the right, within the width of the value field.
     * <p>
     * The shift is arithmetic: the sign bit of the field fills from the left, so the sign of the
     * value is kept, and an amount at or beyond the width leaves only the fill, {@code 0} for a
     * value that is not negative and {@code -1} for one that is. A negative amount shifts to the
     * left by its magnitude, as {@link #shiftLeft(int, int)} does.
     *
     * @param value The value whose pattern is shifted.
     * @param amount The number of bit positions, taken literally.
     * @return The shifted pattern as a sign-extended value, as
     *         {@link Molecule#extractSignedValue(int)} returns it.
     */
    public static int shiftRight(int value, int amount) {
        return shift(value, -(long) amount);
    }

    /**
     * Rotates the bit pattern of a value within the width of the value field.
     * <p>
     * The amount is taken modulo the width, so a rotation by the full width leaves the pattern as
     * it was; a negative amount rotates to the right.
     *
     * @param value The value whose pattern is rotated.
     * @param amount The number of bit positions to rotate to the left.
     * @return The rotated pattern as a sign-extended value, as
     *         {@link Molecule#extractSignedValue(int)} returns it.
     */
    public static int rotate(int value, int amount) {
        int width = Config.VALUE_BITS;
        int mask = (1 << width) - 1;
        int v = value & mask;
        int k = amount % width;
        if (k < 0) k += width;
        return Molecule.extractSignedValue((v << k) | (v >>> (width - k)));
    }

    /**
     * Shifts the bit pattern of a value by a signed amount.
     *
     * @param value The value whose pattern is shifted.
     * @param leftAmount The amount to the left; a negative amount shifts to the right. It is a
     *                   {@code long} so that the magnitude of every {@code int} amount is exact.
     * @return The shifted pattern as a sign-extended value.
     */
    private static int shift(int value, long leftAmount) {
        int pattern = Molecule.extractSignedValue(value);
        if (leftAmount >= 0) {
            if (leftAmount >= Config.VALUE_BITS) {
                return 0;
            }
            return Molecule.extractSignedValue(pattern << leftAmount);
        }
        long rightAmount = -leftAmount;
        if (rightAmount >= Config.VALUE_BITS) {
            return pattern < 0 ? -1 : 0;
        }
        return pattern >> rightAmount;
    }
}
