package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.Config;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests the rules for a value at the edges of the value field: saturation of computed numbers,
 * shifts that take their amount literally, and rotation around the width. The cases are stated in
 * terms of {@link Config#VALUE_BITS}, so they hold for every width. These tests operate on plain
 * numbers and require no simulation.
 */
class ValueBoundsTest {

    private static final int WIDTH = Config.VALUE_BITS;
    private static final int MAX = ValueBounds.MAX_VALUE;
    private static final int MIN = ValueBounds.MIN_VALUE;

    /**
     * The ends of the range are the ends of a two's complement number of the value width: the
     * largest value has every bit of the field but the sign bit set, the smallest only the sign bit.
     */
    @Test
    @Tag("unit")
    void theRangeIsThatOfATwosComplementNumberOfTheValueWidth() {
        assertThat(MAX & Config.VALUE_MASK).isEqualTo(Config.VALUE_MASK >>> 1);
        assertThat(MIN & Config.VALUE_MASK).isEqualTo(1 << (WIDTH - 1));
        assertThat(Molecule.extractSignedValue(MAX)).isEqualTo(MAX);
        assertThat(Molecule.extractSignedValue(MIN)).isEqualTo(MIN);
    }

    @Test
    @Tag("unit")
    void aNumberInTheRangeIsKept() {
        assertThat(ValueBounds.saturate(0)).isZero();
        assertThat(ValueBounds.saturate(-1)).isEqualTo(-1);
        assertThat(ValueBounds.saturate(MAX)).isEqualTo(MAX);
        assertThat(ValueBounds.saturate(MIN)).isEqualTo(MIN);
    }

    @Test
    @Tag("unit")
    void aNumberPastAnEndStaysAtThatEnd() {
        assertThat(ValueBounds.saturate((long) MAX + 1)).isEqualTo(MAX);
        assertThat(ValueBounds.saturate((long) MIN - 1)).isEqualTo(MIN);
        assertThat(ValueBounds.saturate(Long.MAX_VALUE)).isEqualTo(MAX);
        assertThat(ValueBounds.saturate(Long.MIN_VALUE)).isEqualTo(MIN);
        assertThat(ValueBounds.saturate((long) MAX * MAX)).isEqualTo(MAX);
        assertThat(ValueBounds.saturate((long) MIN * MAX)).isEqualTo(MIN);
    }

    /**
     * The range has one more negative value than positive ones, so the negation and the magnitude
     * of the smallest value have no place in it and end at the largest value.
     */
    @Test
    @Tag("unit")
    void negationAndMagnitudeOfTheSmallestValueEndAtTheLargest() {
        assertThat(ValueBounds.saturate(-(long) MIN)).isEqualTo(MAX);
        assertThat(ValueBounds.saturate(Math.abs((long) MIN))).isEqualTo(MAX);
    }

    @Test
    @Tag("unit")
    void aLeftShiftDropsTheBitsShiftedOutOfTheField() {
        assertThat(ValueBounds.shiftLeft(1, WIDTH - 1)).isEqualTo(MIN);
        assertThat(ValueBounds.shiftLeft(1, WIDTH)).isZero();
        assertThat(ValueBounds.shiftLeft(1, WIDTH + 1)).isZero();
        assertThat(ValueBounds.shiftLeft(-1, WIDTH)).isZero();
        assertThat(ValueBounds.shiftLeft(MAX, 1)).isEqualTo(-2);
        assertThat(ValueBounds.shiftLeft(3, 2)).isEqualTo(12);
        assertThat(ValueBounds.shiftLeft(5, 0)).isEqualTo(5);
    }

    @Test
    @Tag("unit")
    void aNegativeLeftShiftShiftsToTheRight() {
        assertThat(ValueBounds.shiftLeft(12, -2)).isEqualTo(3);
        assertThat(ValueBounds.shiftLeft(-8, -1)).isEqualTo(-4);
        assertThat(ValueBounds.shiftLeft(MAX, -WIDTH)).isZero();
        assertThat(ValueBounds.shiftLeft(MIN, -WIDTH)).isEqualTo(-1);
        assertThat(ValueBounds.shiftLeft(MIN, Integer.MIN_VALUE)).isEqualTo(-1);
    }

    @Test
    @Tag("unit")
    void aRightShiftOfAPositiveValueFillsWithZeros() {
        assertThat(ValueBounds.shiftRight(MAX, WIDTH - 2)).isEqualTo(1);
        assertThat(ValueBounds.shiftRight(MAX, WIDTH - 1)).isZero();
        assertThat(ValueBounds.shiftRight(MAX, WIDTH)).isZero();
        assertThat(ValueBounds.shiftRight(MAX, WIDTH + 1)).isZero();
    }

    @Test
    @Tag("unit")
    void aRightShiftOfANegativeValueKeepsTheSign() {
        assertThat(ValueBounds.shiftRight(MIN, WIDTH - 2)).isEqualTo(-2);
        assertThat(ValueBounds.shiftRight(MIN, WIDTH - 1)).isEqualTo(-1);
        assertThat(ValueBounds.shiftRight(MIN, WIDTH)).isEqualTo(-1);
        assertThat(ValueBounds.shiftRight(MIN, WIDTH + 1)).isEqualTo(-1);
        assertThat(ValueBounds.shiftRight(-1, 1)).isEqualTo(-1);
    }

    @Test
    @Tag("unit")
    void aNegativeRightShiftShiftsToTheLeft() {
        assertThat(ValueBounds.shiftRight(3, -2)).isEqualTo(12);
        assertThat(ValueBounds.shiftRight(1, -(WIDTH - 1))).isEqualTo(MIN);
        assertThat(ValueBounds.shiftRight(1, -WIDTH)).isZero();
        assertThat(ValueBounds.shiftRight(1, Integer.MIN_VALUE)).isZero();
    }

    @Test
    @Tag("unit")
    void aRotationGoesAroundTheField() {
        int pattern = 0b1011;
        assertThat(ValueBounds.rotate(pattern, 0)).isEqualTo(pattern);
        assertThat(ValueBounds.rotate(pattern, WIDTH)).isEqualTo(pattern);
        assertThat(ValueBounds.rotate(pattern, WIDTH + 1)).isEqualTo(pattern << 1);
        assertThat(ValueBounds.rotate(1, -1)).isEqualTo(1 << (WIDTH - 1));
        assertThat(ValueBounds.rotate(1 << (WIDTH - 1), 1)).isEqualTo(1);
        assertThat(ValueBounds.rotate(-1, 3)).isEqualTo(Config.VALUE_MASK);
    }
}
