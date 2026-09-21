package org.evochora.runtime.label;

/**
 * Answers the most frequent lookup in one probe: which label of this owner carries exactly this
 * value.
 * <p>
 * One open-addressing hash table over all owners, keyed by (owner, label value) and held in a
 * single array of primitive longs. A slot is two adjacent longs, the key and what it stands for,
 * so that a probe reads one cache line and follows no reference. A key stands for the flat index
 * of the owner's only label with that value, or for {@link #SEVERAL} when the owner holds
 * duplicates of it; which duplicate a jump takes is not decided here.
 * <p>
 * The table is never more than half full, which keeps probe sequences short, and doubles when it
 * would be.
 * <p>
 * Thread Safety: not synchronized. Mutated only from the simulation thread outside the parallel
 * wave, read concurrently inside it.
 */
final class OwnLabelTable {

    /** The owner has no label with the value. */
    static final int NONE = -1;

    /** The owner has several labels with the value. */
    static final int SEVERAL = -2;

    private static final long EMPTY = -1L;
    private static final int INITIAL_CAPACITY = 1 << 10;

    /** Multiplier of the multiplicative hash: 2^64 divided by the golden ratio. */
    private static final long HASH_MULTIPLIER = 0x9E3779B97F4A7C15L;

    /** Pairs of key and meaning; a key of {@link #EMPTY} marks a free slot. */
    private long[] slots;
    private int mask;
    private int shift;
    private int size;

    OwnLabelTable() {
        allocate(INITIAL_CAPACITY);
    }

    private void allocate(int capacity) {
        slots = new long[capacity * 2];
        for (int i = 0; i < slots.length; i += 2) {
            slots[i] = EMPTY;
        }
        mask = capacity - 1;
        shift = Long.SIZE - Integer.numberOfTrailingZeros(capacity);
    }

    private static long key(int owner, int labelValue) {
        return ((long) owner << Integer.SIZE) | labelValue;
    }

    private int home(long key) {
        return (int) ((key * HASH_MULTIPLIER) >>> shift);
    }

    /**
     * Finds the owner's label with exactly a value.
     *
     * @param owner The owner's id
     * @param labelValue The label value
     * @return The flat index of the owner's only label with the value, {@link #NONE} or
     *         {@link #SEVERAL}
     */
    int find(int owner, int labelValue) {
        long key = key(owner, labelValue);
        long[] table = slots;
        int slotMask = mask;
        int i = home(key);
        while (true) {
            long found = table[i << 1];
            if (found == key) {
                return (int) table[(i << 1) + 1];
            }
            if (found == EMPTY) {
                return NONE;
            }
            i = (i + 1) & slotMask;
        }
    }

    /**
     * Records that an owner has one more label with a value.
     *
     * @param owner The owner's id
     * @param labelValue The label's value
     * @param flatIndex The flat index of the cell holding the label
     */
    void add(int owner, int labelValue, int flatIndex) {
        if ((size + 1) * 2 > mask + 1) {
            grow();
        }
        long key = key(owner, labelValue);
        int i = home(key);
        while (true) {
            long found = slots[i << 1];
            if (found == key) {
                slots[(i << 1) + 1] = SEVERAL;
                return;
            }
            if (found == EMPTY) {
                slots[i << 1] = key;
                slots[(i << 1) + 1] = flatIndex;
                size++;
                return;
            }
            i = (i + 1) & mask;
        }
    }

    /**
     * Records that an owner has one label with a value less.
     *
     * @param owner The owner's id
     * @param labelValue The label's value
     * @param ownLabels The owner's labels without the removed one; read only when the owner held
     *                  duplicates of the value, to find what is left of them
     */
    void remove(int owner, int labelValue, LabelList ownLabels) {
        long key = key(owner, labelValue);
        int i = home(key);
        while (slots[i << 1] != key) {
            if (slots[i << 1] == EMPTY) {
                return;
            }
            i = (i + 1) & mask;
        }
        if ((int) slots[(i << 1) + 1] == SEVERAL) {
            int left = NONE;
            for (int position = 0, n = ownLabels.size(); position < n; position++) {
                if (ownLabels.payloadAt(position) == labelValue) {
                    left = left == NONE ? ownLabels.flatIndexAt(position) : SEVERAL;
                }
            }
            if (left != NONE) {
                slots[(i << 1) + 1] = left;
                return;
            }
        }
        free(i);
    }

    /**
     * Frees a slot and moves the entries behind it forward, so that every remaining entry stays
     * reachable from its home slot without a marker for deleted entries.
     */
    private void free(int slot) {
        size--;
        int hole = slot;
        int next = (slot + 1) & mask;
        while (slots[next << 1] != EMPTY) {
            int home = home(slots[next << 1]);
            boolean reachableFromHole = hole <= next ? (home <= hole || home > next) : (home <= hole && home > next);
            if (reachableFromHole) {
                slots[hole << 1] = slots[next << 1];
                slots[(hole << 1) + 1] = slots[(next << 1) + 1];
                hole = next;
            }
            next = (next + 1) & mask;
        }
        slots[hole << 1] = EMPTY;
    }

    private void grow() {
        long[] old = slots;
        allocate((mask + 1) * 2);
        for (int i = 0; i < old.length; i += 2) {
            if (old[i] != EMPTY) {
                int slot = home(old[i]);
                while (slots[slot << 1] != EMPTY) {
                    slot = (slot + 1) & mask;
                }
                slots[slot << 1] = old[i];
                slots[(slot << 1) + 1] = old[i + 1];
            }
        }
    }

    /**
     * Gets the number of (owner, value) pairs the table holds.
     *
     * @return The number of entries
     */
    int size() {
        return size;
    }
}
