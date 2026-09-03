package org.evochora.runtime.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The radix-ordered batch against {@code Arrays.sort} on the same keys: any batch size around the
 * insertion-sort threshold and far beyond it, canonical indices spanning the full range a world of
 * two billion cells can produce, and molecule and owner travelling with their cell.
 */
@Tag("unit")
class CanonicalCellOrderTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 31, 32, 33, 64, 257, 1000, 4096, 100_000})
    void ordersLikeASortOfTheKeys(int size) {
        // Every cell has one canonical index, so canonical indices in a batch are distinct; a small
        // range makes the upper radix bytes collide, a large one exercises all four passes.
        Random random = new Random(size);
        CanonicalCellOrder order = new CanonicalCellOrder();
        long[] expected = new long[size];
        java.util.Map<Integer, Integer> ownerOf = new java.util.HashMap<>();
        int bound = size < 100 ? 300 : Integer.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            int canonical;
            do {
                canonical = random.nextInt(bound);
            } while (ownerOf.containsKey(canonical));
            int molecule = random.nextInt();
            int owner = random.nextInt(1000);
            ownerOf.put(canonical, owner);
            order.add(canonical, molecule, owner);
            expected[i] = ((long) canonical << 32) | (molecule & 0xFFFFFFFFL);
        }
        Arrays.sort(expected);

        order.sort();

        assertThat(order.count()).isEqualTo(size);
        for (int i = 0; i < size; i++) {
            int canonical = (int) (expected[i] >>> 32);
            assertThat(order.canonicalAt(i)).as("canonical at %d", i).isEqualTo(canonical);
            assertThat(order.moleculeAt(i)).as("molecule at %d", i).isEqualTo((int) expected[i]);
            assertThat(order.ownerAt(i)).as("owner travels with its cell, at %d", i).isEqualTo(ownerOf.get(canonical));
        }
    }

    @Test
    void keysSharingTheirHighBytesStillEndUpOrdered() {
        // All canonical indices below 2^16: the two upper radix passes see a single bucket each.
        Random random = new Random(7);
        CanonicalCellOrder order = new CanonicalCellOrder();
        int[] expected = new int[5000];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = random.nextInt(1 << 16);
            order.add(expected[i], i, 0);
        }
        Arrays.sort(expected);

        order.sort();

        for (int i = 0; i < expected.length; i++) {
            assertThat(order.canonicalAt(i)).isEqualTo(expected[i]);
        }
    }

    @Test
    void aClearedBatchStartsEmptyAndKeepsWorking() {
        CanonicalCellOrder order = new CanonicalCellOrder();
        for (int i = 0; i < 2000; i++) {
            order.add(2000 - i, i, i);
        }
        order.sort();
        order.clear();
        assertThat(order.count()).isZero();

        order.add(5, 50, 500);
        order.add(3, 30, 300);
        order.sort();
        assertThat(order.count()).isEqualTo(2);
        assertThat(order.canonicalAt(0)).isEqualTo(3);
        assertThat(order.moleculeAt(0)).isEqualTo(30);
        assertThat(order.ownerAt(0)).isEqualTo(300);
        assertThat(order.canonicalAt(1)).isEqualTo(5);
    }
}
