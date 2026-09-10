package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests the mapping of an arbitrary vector to the unit vector nearest to it in angle.
 * <p>
 * Besides the individual cases, two properties are checked that the rule exists for: that a tie is
 * resolved without preferring any direction over the generations of a mutating program, and that
 * the common case neither allocates nor touches its argument. These tests operate on plain arrays
 * and require no simulation.
 */
class UnitVectorTest {

    /**
     * A vector that already names a direction is its own nearest unit vector, and it is handed back
     * as the same object so that the common case allocates nothing.
     */
    @Test
    @Tag("unit")
    void aUnitVectorIsReturnedAsTheSameObject() {
        for (int dimensions = 2; dimensions <= 3; dimensions++) {
            for (int[] unit : unitVectors(dimensions)) {
                assertThat(UnitVector.nearest(unit))
                        .as("unit vector %s", Arrays.toString(unit))
                        .isSameAs(unit);
            }
        }
    }

    /**
     * A vector that points along one axis but is longer than one step keeps its direction and loses
     * its length.
     */
    @Test
    @Tag("unit")
    void aLongerVectorKeepsItsAxisAndSign() {
        assertThat(UnitVector.nearest(new int[]{2, 0})).isEqualTo(new int[]{1, 0});
        assertThat(UnitVector.nearest(new int[]{0, -3})).isEqualTo(new int[]{0, -1});
        assertThat(UnitVector.nearest(new int[]{5, 0, 0})).isEqualTo(new int[]{1, 0, 0});
        assertThat(UnitVector.nearest(new int[]{0, 0, -7})).isEqualTo(new int[]{0, 0, -1});
    }

    /**
     * A component that outweighs the others decides the direction, whatever the others hold.
     */
    @Test
    @Tag("unit")
    void theLargestComponentDecides() {
        assertThat(UnitVector.nearest(new int[]{3, 1})).isEqualTo(new int[]{1, 0});
        assertThat(UnitVector.nearest(new int[]{-1, 4})).isEqualTo(new int[]{0, 1});
        assertThat(UnitVector.nearest(new int[]{2, -5, 1})).isEqualTo(new int[]{0, -1, 0});
    }

    /**
     * Two axes of equal magnitude are separated by the number of negative components among them.
     * The four sign combinations are distributed evenly over the two axes, which is what keeps the
     * rule free of drift.
     */
    @Test
    @Tag("unit")
    void aTieBetweenTwoAxesFollowsTheNegativeCandidates() {
        assertThat(UnitVector.nearest(new int[]{1, 1})).isEqualTo(new int[]{1, 0});
        assertThat(UnitVector.nearest(new int[]{1, -1})).isEqualTo(new int[]{0, -1});
        assertThat(UnitVector.nearest(new int[]{-1, 1})).isEqualTo(new int[]{0, 1});
        assertThat(UnitVector.nearest(new int[]{-1, -1})).isEqualTo(new int[]{-1, 0});

        // A tie on two of three axes behaves the same: the untied axis takes no part.
        assertThat(UnitVector.nearest(new int[]{0, 1, 1})).isEqualTo(new int[]{0, 1, 0});
        assertThat(UnitVector.nearest(new int[]{0, 1, -1})).isEqualTo(new int[]{0, 0, -1});
        assertThat(UnitVector.nearest(new int[]{0, -1, 1})).isEqualTo(new int[]{0, 0, 1});
        assertThat(UnitVector.nearest(new int[]{0, -1, -1})).isEqualTo(new int[]{0, -1, 0});
    }

    /**
     * Three axes of equal magnitude are not distributed evenly; the outcomes over the eight sign
     * combinations are 1, 1, 2, 1, 1, 2 across the six directions. Reaching this state needs several
     * mutations of one vector, and the imbalance is a documented limit rather than a defect.
     */
    @Test
    @Tag("unit")
    void aTieBetweenThreeAxesIsUneven() {
        Map<List<Integer>, Integer> outcomes = new HashMap<>();
        for (int x : new int[]{1, -1}) {
            for (int y : new int[]{1, -1}) {
                for (int z : new int[]{1, -1}) {
                    List<Integer> result = boxed(UnitVector.nearest(new int[]{x, y, z}));
                    outcomes.merge(result, 1, Integer::sum);
                }
            }
        }

        assertThat(outcomes).containsOnly(
                org.assertj.core.api.Assertions.entry(List.of(1, 0, 0), 1),
                org.assertj.core.api.Assertions.entry(List.of(-1, 0, 0), 1),
                org.assertj.core.api.Assertions.entry(List.of(0, 1, 0), 2),
                org.assertj.core.api.Assertions.entry(List.of(0, -1, 0), 1),
                org.assertj.core.api.Assertions.entry(List.of(0, 0, 1), 1),
                org.assertj.core.api.Assertions.entry(List.of(0, 0, -1), 2));
    }

    /**
     * The zero vector carries no direction, so none is nearest to it. The caller decides what to use
     * instead; here only the absence of an answer is fixed.
     */
    @Test
    @Tag("unit")
    void theZeroVectorHasNoNearestUnitVector() {
        assertThat(UnitVector.nearest(new int[]{0, 0})).isNull();
        assertThat(UnitVector.nearest(new int[]{0, 0, 0})).isNull();
    }

    /**
     * The argument may be an organism's live register or an entry still held on its data stack, and
     * a conflict loser retries the same instruction with the same operands in the next tick. Writing
     * into it would change what the organism retries with.
     */
    @Test
    @Tag("unit")
    void theArgumentIsNeverWrittenInto() {
        int[] argument = new int[]{3, -3, 1};
        int[] before = Arrays.copyOf(argument, argument.length);

        int[] nearest = UnitVector.nearest(argument);

        assertThat(argument).as("the argument stays as it was").isEqualTo(before);
        assertThat(nearest).as("a snapped vector is a new array").isNotSameAs(argument);
    }

    /**
     * The property the tie rule exists for: no direction is favoured over the generations.
     * <p>
     * Every unit vector is subjected to every single-component mutation the substitution operator
     * can produce — a component out of {-1, 0, 1} always receives an offset of ±1 — and the result
     * is snapped. Counting the transitions gives a matrix whose row sums are equal by construction;
     * that the column sums equal them as well means every direction is reached as often as it is
     * left, so a population of mutating programs drifts towards no axis. A tie rule that preferred
     * one axis would break the column sums while leaving every other test in this class green.
     * <p>
     * The zero vector is excluded because its replacement comes from the organism's own direction of
     * travel rather than from this rule. The second dimensionality is here to catch an
     * implementation that hard-codes two axes, not to test the rule, which depends only on the set
     * of tied axes.
     */
    @Test
    @Tag("unit")
    void noDirectionIsFavouredOverTheGenerations() {
        for (int dimensions = 2; dimensions <= 3; dimensions++) {
            List<int[]> directions = unitVectors(dimensions);
            Map<List<Integer>, Integer> reached = new HashMap<>();
            Map<List<Integer>, Integer> left = new HashMap<>();

            for (int[] start : directions) {
                for (int component = 0; component < dimensions; component++) {
                    for (int offset : new int[]{-1, 1}) {
                        int[] mutated = Arrays.copyOf(start, dimensions);
                        mutated[component] += offset;

                        int[] snapped = UnitVector.nearest(mutated);
                        if (snapped == null) {
                            continue; // the zero vector is answered from the organism's DV
                        }
                        left.merge(boxed(start), 1, Integer::sum);
                        reached.merge(boxed(snapped), 1, Integer::sum);
                    }
                }
            }

            for (int[] direction : directions) {
                List<Integer> key = boxed(direction);
                assertThat(reached.get(key))
                        .as("in %d dimensions, %s is reached as often as it is left",
                                dimensions, key)
                        .isEqualTo(left.get(key));
            }
        }
    }

    /**
     * The {@code 2 · dimensions} unit vectors of a world, in axis order, each axis positive first.
     *
     * @param dimensions The number of dimensions.
     * @return The unit vectors, as fresh arrays.
     */
    private static List<int[]> unitVectors(int dimensions) {
        List<int[]> vectors = new ArrayList<>(2 * dimensions);
        for (int axis = 0; axis < dimensions; axis++) {
            for (int sign : new int[]{1, -1}) {
                int[] vector = new int[dimensions];
                vector[axis] = sign;
                vectors.add(vector);
            }
        }
        return vectors;
    }

    /**
     * A vector as a list, so that it can be a map key.
     *
     * @param vector The vector to convert.
     * @return The components in order.
     */
    private static List<Integer> boxed(int[] vector) {
        List<Integer> components = new ArrayList<>(vector.length);
        for (int component : vector) {
            components.add(component);
        }
        return components;
    }
}
