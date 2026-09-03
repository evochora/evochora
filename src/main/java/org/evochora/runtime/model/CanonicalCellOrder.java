package org.evochora.runtime.model;

/**
 * A batch of cells ordered by canonical index, used to hand cells out of the environment in the
 * numbering they are persisted in, whatever the environment's memory layout.
 * <p>
 * Each cell is one {@code long} key: the canonical index in the upper 32 bits and the internal
 * index in the lower 32, so that ordering the keys orders the cells and keeps the index needed to
 * read each cell. Keys are collected into one grow-only buffer that is retained between batches,
 * and sorted in place by an MSD radix sort over the four bytes of the canonical index. The sort is
 * linear in the number of cells, needs no second buffer, and leaves keys with equal canonical index
 * in an unspecified mutual order — which cannot occur, as every cell has one canonical index.
 * <p>
 * Thread safety: not thread-safe; one instance belongs to one environment and is used only from
 * the thread that serializes it.
 */
final class CanonicalCellOrder {

    /** Below this many keys a range is finished by insertion sort instead of another radix pass. */
    private static final int INSERTION_SORT_THRESHOLD = 32;
    /** Bit position of the most significant byte of the canonical index inside a key. */
    private static final int TOP_BYTE_SHIFT = 56;
    /** Bit position of the least significant byte of the canonical index inside a key. */
    private static final int LOW_BYTE_SHIFT = 32;

    private long[] keys = new long[0];
    private int count = 0;

    /** Empties the batch; the buffer is kept. */
    void clear() {
        count = 0;
    }

    /**
     * Adds a cell to the batch.
     *
     * @param canonicalIndex the cell's canonical index
     * @param internalIndex  the cell's internal index
     */
    void add(int canonicalIndex, int internalIndex) {
        if (count == keys.length) {
            keys = java.util.Arrays.copyOf(keys, Math.max(1024, keys.length * 2));
        }
        keys[count++] = ((long) canonicalIndex << 32) | (internalIndex & 0xFFFFFFFFL);
    }

    /** Orders the batch by canonical index. */
    void sort() {
        sortRange(0, count, TOP_BYTE_SHIFT);
    }

    /**
     * @return the number of cells in the batch
     */
    int count() {
        return count;
    }

    /**
     * @param position a position in the sorted batch, {@code 0 <= position < count()}
     * @return the canonical index of the cell at that position
     */
    int canonicalAt(int position) {
        return (int) (keys[position] >>> 32);
    }

    /**
     * @param position a position in the sorted batch, {@code 0 <= position < count()}
     * @return the internal index of the cell at that position
     */
    int internalAt(int position) {
        return (int) keys[position];
    }

    /**
     * American flag sort: distributes the keys of {@code [from, to)} into 256 buckets by the byte
     * at {@code shift}, permuting in place, then recurses into every bucket on the next lower
     * byte. Small ranges are finished by insertion sort, which also orders the bytes below the
     * current one.
     */
    private void sortRange(int from, int to, int shift) {
        int n = to - from;
        if (n < 2) {
            return;
        }
        if (n <= INSERTION_SORT_THRESHOLD) {
            insertionSort(from, to);
            return;
        }
        int[] bucketEnd = new int[256];
        for (int i = from; i < to; i++) {
            bucketEnd[byteAt(keys[i], shift)]++;
        }
        int[] bucketNext = new int[256];
        int start = from;
        for (int b = 0; b < 256; b++) {
            bucketNext[b] = start;
            start += bucketEnd[b];
            bucketEnd[b] = start;
        }
        // Walk the buckets in order; every key met that belongs elsewhere is swapped into its own
        // bucket's next free slot until a key that belongs here arrives, which is then placed.
        for (int b = 0; b < 256; b++) {
            int i = bucketNext[b];
            while (i < bucketEnd[b]) {
                long key = keys[i];
                int target = byteAt(key, shift);
                if (target == b) {
                    i++;
                } else {
                    int slot = bucketNext[target]++;
                    keys[i] = keys[slot];
                    keys[slot] = key;
                }
            }
            bucketNext[b] = i;
        }
        if (shift > LOW_BYTE_SHIFT) {
            int bucketStart = from;
            for (int b = 0; b < 256; b++) {
                sortRange(bucketStart, bucketEnd[b], shift - 8);
                bucketStart = bucketEnd[b];
            }
        }
    }

    private static int byteAt(long key, int shift) {
        return (int) ((key >>> shift) & 0xFF);
    }

    private void insertionSort(int from, int to) {
        for (int i = from + 1; i < to; i++) {
            long key = keys[i];
            int j = i - 1;
            while (j >= from && Long.compareUnsigned(keys[j], key) > 0) {
                keys[j + 1] = keys[j];
                j--;
            }
            keys[j + 1] = key;
        }
    }
}
