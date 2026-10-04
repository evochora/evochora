package org.evochora.runtime.worldgen;

import java.util.Arrays;
import java.util.Random;

/**
 * A set of opcodes a mutation draws from, each with a weight above zero, drawn in proportion to
 * its weight.
 * <p>
 * Built once when a plugin is configured and read on every draw; the draw allocates nothing.
 */
final class WeightedOpcodes {

    /** The set without an opcode, from which nothing can be drawn. */
    static final WeightedOpcodes EMPTY = new WeightedOpcodes(new int[0], new double[0]);

    private final int[] opcodeIds;
    /** Running sums of the weights, parallel to {@link #opcodeIds}; the last one is the total. */
    private final double[] cumulative;

    /**
     * Creates the set from opcodes and their weights. Opcodes of weight zero are left out.
     *
     * @param opcodeIds the opcodes
     * @param weights   the weight of each opcode, parallel to {@code opcodeIds}, none negative
     * @return the set, empty if no opcode weighs more than zero
     */
    static WeightedOpcodes of(int[] opcodeIds, double[] weights) {
        int[] ids = new int[opcodeIds.length];
        double[] sums = new double[opcodeIds.length];
        int n = 0;
        double sum = 0.0;
        for (int i = 0; i < opcodeIds.length; i++) {
            if (weights[i] > 0.0) {
                sum += weights[i];
                ids[n] = opcodeIds[i];
                sums[n] = sum;
                n++;
            }
        }
        return n == 0 ? EMPTY : new WeightedOpcodes(Arrays.copyOf(ids, n), Arrays.copyOf(sums, n));
    }

    private WeightedOpcodes(int[] opcodeIds, double[] cumulative) {
        this.opcodeIds = opcodeIds;
        this.cumulative = cumulative;
    }

    /**
     * Tells whether the set holds no opcode.
     *
     * @return {@code true} if nothing can be drawn
     */
    boolean isEmpty() {
        return opcodeIds.length == 0;
    }

    /**
     * Returns the number of opcodes in the set.
     *
     * @return the number of opcodes that can be drawn
     */
    int size() {
        return opcodeIds.length;
    }

    /**
     * Returns the opcode at a position of the set.
     *
     * @param index the position, below {@link #size()}
     * @return the opcode
     */
    int opcodeAt(int index) {
        return opcodeIds[index];
    }

    /**
     * Draws a position of the set, each with a probability proportional to its opcode's weight.
     *
     * @param random the random source, of which one number is taken
     * @return the position of the drawn opcode
     * @throws IllegalStateException if the set is empty
     */
    int drawIndex(Random random) {
        if (opcodeIds.length == 0) {
            throw new IllegalStateException("No opcode to draw from");
        }
        double r = random.nextDouble() * cumulative[cumulative.length - 1];
        int index = Arrays.binarySearch(cumulative, r);
        // An exact hit on a running sum belongs to the next opcode, since the draw is below it
        index = index >= 0 ? index + 1 : -index - 1;
        return Math.min(index, opcodeIds.length - 1);
    }
}
