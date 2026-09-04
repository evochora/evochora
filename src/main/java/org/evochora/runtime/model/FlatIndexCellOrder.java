package org.evochora.runtime.model;

import java.util.Arrays;

/**
 * A batch of cells ordered by flat index, used to hand cells out of the environment in the
 * numbering they are persisted in, whatever the environment's memory layout.
 * <p>
 * Each cell is one {@code long} key — the flat index in the upper 32 bits, the packed
 * molecule value in the lower 32 — and one {@code int} owner in a parallel buffer. A cell's content
 * is read when it is added, in whatever order the caller walks the grid, so that the walk can be
 * sequential over memory and the sorted batch is handed out without touching the grid again. Both
 * buffers are grow-only and retained between batches. The batch is sorted in place by an MSD radix
 * sort over the four bytes of the flat index, moving key and owner together: linear in the
 * number of cells, no second buffer, and equal flat indices cannot occur, as every cell has
 * one. After the first batch nothing on this path allocates: the key and owner buffers grow only,
 * and the bucket counters of the radix passes are held per depth.
 * <p>
 * Thread safety: not thread-safe; one instance belongs to one environment and is used only from
 * the thread that serializes it.
 */
final class FlatIndexCellOrder {

    /** Below this many keys a range is finished by insertion sort instead of another radix pass. */
    private static final int INSERTION_SORT_THRESHOLD = 32;
    /** Bit position of the most significant byte of the flat index inside a key. */
    private static final int TOP_BYTE_SHIFT = 56;
    /** Bit position of the least significant byte of the flat index inside a key. */
    private static final int LOW_BYTE_SHIFT = 32;

    /** Radix passes over the four bytes of the flat index, from the most significant down. */
    private static final int RADIX_DEPTH = 4;

    private long[] keys = new long[0];
    private int[] owners = new int[0];
    private int count = 0;
    // Bucket bounds of the radix pass at each depth; a deeper pass never touches its parent's row
    private final int[][] bucketEnd = new int[RADIX_DEPTH][256];
    private final int[][] bucketNext = new int[RADIX_DEPTH][256];

    /** Empties the batch; the buffer is kept. */
    void clear() {
        count = 0;
    }

    /**
     * Adds a cell with its content to the batch.
     *
     * @param flatIndex      the cell's flat index
     * @param moleculeInt    the cell's packed molecule value
     * @param ownerId        the id of the organism owning the cell, {@code 0} if none
     */
    void add(int flatIndex, int moleculeInt, int ownerId) {
        if (count == keys.length) {
            int capacity = (int) Math.min(Integer.MAX_VALUE - 8, Math.max(1024, 2L * keys.length));
            keys = Arrays.copyOf(keys, capacity);
            owners = Arrays.copyOf(owners, capacity);
        }
        keys[count] = ((long) flatIndex << 32) | (moleculeInt & 0xFFFFFFFFL);
        owners[count] = ownerId;
        count++;
    }

    /** Orders the batch by flat index. */
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
     * @return the flat index of the cell at that position
     */
    int flatIndexAt(int position) {
        return (int) (keys[position] >>> 32);
    }

    /**
     * @param position a position in the sorted batch, {@code 0 <= position < count()}
     * @return the packed molecule value of the cell at that position
     */
    int moleculeAt(int position) {
        return (int) keys[position];
    }

    /**
     * @param position a position in the sorted batch, {@code 0 <= position < count()}
     * @return the owner id of the cell at that position
     */
    int ownerAt(int position) {
        return owners[position];
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
        int depth = (TOP_BYTE_SHIFT - shift) / 8;
        int[] bucketEnd = this.bucketEnd[depth];
        int[] bucketNext = this.bucketNext[depth];
        Arrays.fill(bucketEnd, 0);
        for (int i = from; i < to; i++) {
            bucketEnd[byteAt(keys[i], shift)]++;
        }
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
                    int owner = owners[i];
                    owners[i] = owners[slot];
                    owners[slot] = owner;
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
            int owner = owners[i];
            int j = i - 1;
            while (j >= from && Long.compareUnsigned(keys[j], key) > 0) {
                keys[j + 1] = keys[j];
                owners[j + 1] = owners[j];
                j--;
            }
            keys[j + 1] = key;
            owners[j + 1] = owner;
        }
    }
}
