package org.evochora.runtime.label;

import java.util.Arrays;
import java.util.BitSet;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.EnvironmentProperties;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

/**
 * All labels of a world by value and by place, for the search of the nearest label with a value.
 * <p>
 * One open-addressing hash table of primitive longs holds every label. A slot is two adjacent
 * longs, a key and what it stands for, so that a probe reads one cache line. How the labels of a
 * value are keyed depends on how many there are:
 * <ul>
 *   <li><b>A value with few labels</b> — at most {@link #FEW} — holds all of them under one key,
 *       wherever they lie. A search examines each. This is the state of a value that a mutation or
 *       a namespace of its own has made rare; a search that has to probe many such values pays one
 *       probe for each.</li>
 *   <li><b>A value with many labels</b> holds them by tile: the world is divided into square tiles
 *       of {@link #TILE_SIDE} cells over its first two dimensions — further dimensions are not
 *       divided — and the key is the value and the tile. Under stable label values one value is
 *       carried by every organism with that gene, so its labels are as many as the population. A
 *       search visits the tiles in rings around the caller's tile and ends with the ring no tile of
 *       which can hold a label nearer than the best found, or within the radius if none is found.
 *       Its cost follows the distance to the target, not the size of the population.</li>
 * </ul>
 * A value passes from the first state to the second when it gains its label number {@code FEW + 1}
 * and stays there until its last label is gone. Which state a value is in therefore depends on its
 * history, which differs between a run and its resume, and so does the order of the labels under a
 * key. No result depends on either: a search compares every candidate by distance and then by flat
 * index, which is a total order.
 * <p>
 * A key that stands for one label holds the label itself, its flat index and owner; several labels
 * under one key lie in a small unordered bucket. An addition and a removal therefore touch one
 * slot and at most one bucket. The table is never more than half full and doubles when it would be.
 * Its memory follows the number of labels alone — not the size of the world, and not the number of
 * different values.
 * <p>
 * The tile side is a trade between the two ends of a search among many labels. Smaller tiles end a
 * successful search sooner in a dense world; larger tiles leave fewer empty tiles to pass in a
 * sparse one.
 * <p>
 * Thread Safety: not synchronized. Mutated only from the simulation thread outside the parallel
 * wave, read concurrently inside it.
 */
final class TiledLabelIndex {

    /** Side of a tile in cells; a power of two. */
    static final int TILE_SIDE = 128;

    private static final int TILE_SHIFT = Integer.numberOfTrailingZeros(TILE_SIDE);

    /** Several labels under one key: flat indexes and owners, unordered. */
    private static final class Bucket {
        private int[] flatIndexes = new int[4];
        private int[] owners = new int[4];
        private int size;

        private int positionOf(int flatIndex) {
            for (int i = 0; i < size; i++) {
                if (flatIndexes[i] == flatIndex) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static final long EMPTY = -1L;
    private static final long HASH_MULTIPLIER = 0x9E3779B97F4A7C15L;

    /**
     * Pairs of key — value and tile, or value and {@link #ANYWHERE} — and content. A content that
     * is not negative is the only label under the key, flat index in the upper and owner in the
     * lower half; a negative one is -1 - the position of the bucket holding several.
     */
    private long[] slots;
    private int mask;
    private int shift;
    private int size;
    private Bucket[] buckets = new Bucket[16];
    private int[] freeBuckets = new int[16];
    private int freeCount;
    private int bucketCount;

    /** Number of labels of every value that is held by tile, to know when its last label is gone. */
    private final Int2IntOpenHashMap labelsOfValue = new Int2IntOpenHashMap();

    private final CoordinateDecoder coordinates;
    private final int tilesAlongFirst;
    private final int tilesAlongSecond;

    /**
     * One bit per label value in use, so that a probe for a value nobody carries costs a bit read
     * instead of a hash lookup.
     */
    private final BitSet valuesInUse = new BitSet(1 << Config.VALUE_BITS);

    /**
     * Creates an empty index for a world.
     *
     * @param properties The properties of the world
     */
    TiledLabelIndex(EnvironmentProperties properties) {
        coordinates = new CoordinateDecoder(properties);
        tilesAlongFirst = tileCount(coordinates.size(0));
        tilesAlongSecond = coordinates.dimensions() > 1 ? tileCount(coordinates.size(1)) : 1;
        allocate(1 << 10);
    }

    private void allocate(int capacity) {
        slots = new long[capacity * 2];
        for (int i = 0; i < slots.length; i += 2) {
            slots[i] = EMPTY;
        }
        mask = capacity - 1;
        shift = Long.SIZE - Integer.numberOfTrailingZeros(capacity);
    }

    private static long key(int labelValue, int tile) {
        return ((long) labelValue << Integer.SIZE) | tile;
    }

    private int home(long key) {
        return (int) ((key * HASH_MULTIPLIER) >>> shift);
    }

    /** The slot of a key, or -1 - the free slot it would take. */
    private int slotOf(long key) {
        int i = home(key);
        while (true) {
            long found = slots[i << 1];
            if (found == key) {
                return i;
            }
            if (found == EMPTY) {
                return -1 - i;
            }
            i = (i + 1) & mask;
        }
    }

    private void grow() {
        long[] old = slots;
        allocate((mask + 1) * 2);
        for (int i = 0; i < old.length; i += 2) {
            if (old[i] != EMPTY) {
                int slot = -1 - slotOf(old[i]);
                slots[slot << 1] = old[i];
                slots[(slot << 1) + 1] = old[i + 1];
            }
        }
    }

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

    private static long single(int flatIndex, int owner) {
        return ((long) flatIndex << Integer.SIZE) | (owner & 0xFFFFFFFFL);
    }

    private int newBucket() {
        if (freeCount > 0) {
            int position = freeBuckets[--freeCount];
            buckets[position] = new Bucket();
            return position;
        }
        if (bucketCount == buckets.length) {
            buckets = Arrays.copyOf(buckets, bucketCount * 2);
        }
        buckets[bucketCount] = new Bucket();
        return bucketCount++;
    }

    private void releaseBucket(int position) {
        buckets[position] = null;
        if (freeCount == freeBuckets.length) {
            freeBuckets = Arrays.copyOf(freeBuckets, freeCount * 2);
        }
        freeBuckets[freeCount++] = position;
    }

    private static int tileCount(int cells) {
        return (cells + TILE_SIDE - 1) >> TILE_SHIFT;
    }

    private int tileOf(int flatIndex) {
        return (coordinates.coordinate(0, flatIndex) >> TILE_SHIFT) * tilesAlongSecond
                + (coordinates.coordinate(1, flatIndex) >> TILE_SHIFT);
    }

    /**
     * Gets the decoder of the world this index was created for.
     *
     * @return The decoder
     */
    CoordinateDecoder coordinates() {
        return coordinates;
    }

    /**
     * Tells whether any label carries a value.
     *
     * @param labelValue The label value
     * @return {@code true} if the index holds a label with it
     */
    boolean isInUse(int labelValue) {
        return valuesInUse.get(labelValue);
    }

    /**
     * Tells whether the index holds no label at all.
     *
     * @return {@code true} if it is empty
     */
    boolean isEmpty() {
        return size == 0;
    }

    /** The pseudo tile under which a value with few labels holds all of them, wherever they lie. */
    private static final int ANYWHERE = Integer.MAX_VALUE;

    /** A value is held under {@link #ANYWHERE} until it has more labels than this. */
    private static final int FEW = 16;

    /** One bit per value whose labels are held by tile. */
    private final BitSet tiledValues = new BitSet(1 << Config.VALUE_BITS);

    private long keyFor(int labelValue, int flatIndex) {
        return key(labelValue, tiledValues.get(labelValue) ? tileOf(flatIndex) : ANYWHERE);
    }

    /**
     * Adds a label, or sets the owner of the label already at that cell: a cell holds one label.
     *
     * @param labelValue The label's value
     * @param flatIndex The flat index of the cell holding the label
     * @param owner The owner of the cell
     */
    void put(int labelValue, int flatIndex, int owner) {
        boolean tiled = tiledValues.get(labelValue);
        int held = putAt(key(labelValue, tiled ? tileOf(flatIndex) : ANYWHERE), flatIndex, owner);
        if (held < 0) {
            return;
        }
        if (tiled) {
            labelsOfValue.addTo(labelValue, 1);
        } else if (held == 1) {
            valuesInUse.set(labelValue);
        } else if (held > FEW) {
            spreadOverTiles(labelValue);
        }
    }

    /** Moves the labels of a value from the one entry that held them all to an entry per tile. */
    private void spreadOverTiles(int labelValue) {
        int slot = slotOf(key(labelValue, ANYWHERE));
        int bucketPosition = (int) (-1L - slots[(slot << 1) + 1]);
        Bucket all = buckets[bucketPosition];
        free(slot);
        releaseBucket(bucketPosition);
        tiledValues.set(labelValue);
        labelsOfValue.put(labelValue, all.size);
        for (int i = 0; i < all.size; i++) {
            putAt(key(labelValue, tileOf(all.flatIndexes[i])), all.flatIndexes[i], all.owners[i]);
        }
    }

    /**
     * Adds a label under a key, or sets the owner of the label already there.
     *
     * @return the number of labels under the key after a label was added, -1 if only an owner changed
     */
    private int putAt(long key, int flatIndex, int owner) {
        if ((size + 1) * 2 > mask + 1) {
            grow();
        }
        int slot = slotOf(key);
        if (slot < 0) {
            slot = -1 - slot;
            slots[slot << 1] = key;
            slots[(slot << 1) + 1] = single(flatIndex, owner);
            size++;
            return 1;
        }
        long content = slots[(slot << 1) + 1];
        if (content >= 0) {
            int held = (int) (content >>> Integer.SIZE);
            if (held == flatIndex) {
                slots[(slot << 1) + 1] = single(flatIndex, owner);
                return -1;
            }
            int position = newBucket();
            Bucket bucket = buckets[position];
            bucket.flatIndexes[0] = held;
            bucket.owners[0] = (int) content;
            bucket.flatIndexes[1] = flatIndex;
            bucket.owners[1] = owner;
            bucket.size = 2;
            slots[(slot << 1) + 1] = -1L - position;
            return 2;
        }
        Bucket bucket = buckets[(int) (-1L - content)];
        int position = bucket.positionOf(flatIndex);
        if (position >= 0) {
            bucket.owners[position] = owner;
            return -1;
        }
        if (bucket.size == bucket.flatIndexes.length) {
            bucket.flatIndexes = Arrays.copyOf(bucket.flatIndexes, bucket.size * 2);
            bucket.owners = Arrays.copyOf(bucket.owners, bucket.size * 2);
        }
        bucket.flatIndexes[bucket.size] = flatIndex;
        bucket.owners[bucket.size] = owner;
        return ++bucket.size;
    }

    /**
     * Sets the owner of a label, if the index holds it.
     *
     * @param labelValue The label's value
     * @param flatIndex The flat index of the cell
     * @param owner The new owner of the cell
     * @return {@code true} if the index holds the label
     */
    boolean setOwner(int labelValue, int flatIndex, int owner) {
        int slot = slotOf(keyFor(labelValue, flatIndex));
        if (slot < 0) {
            return false;
        }
        long content = slots[(slot << 1) + 1];
        if (content >= 0) {
            if ((int) (content >>> Integer.SIZE) != flatIndex) {
                return false;
            }
            slots[(slot << 1) + 1] = single(flatIndex, owner);
            return true;
        }
        Bucket bucket = buckets[(int) (-1L - content)];
        int position = bucket.positionOf(flatIndex);
        if (position < 0) {
            return false;
        }
        bucket.owners[position] = owner;
        return true;
    }

    /**
     * Removes a label, if the index holds it.
     *
     * @param labelValue The label's value
     * @param flatIndex The flat index of the cell
     * @return {@code true} if a label was removed
     */
    boolean remove(int labelValue, int flatIndex) {
        boolean tiled = tiledValues.get(labelValue);
        int slot = slotOf(key(labelValue, tiled ? tileOf(flatIndex) : ANYWHERE));
        if (slot < 0) {
            return false;
        }
        long content = slots[(slot << 1) + 1];
        boolean lastUnderKey;
        if (content >= 0) {
            if ((int) (content >>> Integer.SIZE) != flatIndex) {
                return false;
            }
            free(slot);
            lastUnderKey = true;
        } else {
            int bucketPosition = (int) (-1L - content);
            Bucket bucket = buckets[bucketPosition];
            int position = bucket.positionOf(flatIndex);
            if (position < 0) {
                return false;
            }
            bucket.size--;
            bucket.flatIndexes[position] = bucket.flatIndexes[bucket.size];
            bucket.owners[position] = bucket.owners[bucket.size];
            if (bucket.size == 1) {
                slots[(slot << 1) + 1] = single(bucket.flatIndexes[0], bucket.owners[0]);
                releaseBucket(bucketPosition);
            }
            lastUnderKey = false;
        }
        if (tiled) {
            if (labelsOfValue.addTo(labelValue, -1) == 1) {
                labelsOfValue.remove(labelValue);
                tiledValues.clear(labelValue);
                valuesInUse.clear(labelValue);
            }
        } else if (lastUnderKey) {
            valuesInUse.clear(labelValue);
        }
        return true;
    }

    /**
     * Tells which owner a label is held under.
     *
     * @param labelValue The label's value
     * @param flatIndex The flat index of the cell
     * @return The owner, or -1 if the index holds no such label
     */
    int ownerOf(int labelValue, int flatIndex) {
        int slot = slotOf(keyFor(labelValue, flatIndex));
        if (slot < 0) {
            return -1;
        }
        long content = slots[(slot << 1) + 1];
        if (content >= 0) {
            return (int) (content >>> Integer.SIZE) == flatIndex ? (int) content : -1;
        }
        Bucket bucket = buckets[(int) (-1L - content)];
        int position = bucket.positionOf(flatIndex);
        return position < 0 ? -1 : bucket.owners[position];
    }

    // ==================== Search ====================

    /**
     * Packs the state of a search: the distance of the best label found and its flat index.
     *
     * @param distance The distance of the best label, {@link Integer#MAX_VALUE} while there is none
     * @param flatIndex The flat index of the best label, -1 while there is none
     * @return The packed state
     */
    static long searchState(int distance, int flatIndex) {
        return ((long) distance << Integer.SIZE) | (flatIndex & 0xFFFFFFFFL);
    }

    /**
     * Reads the flat index of the best label out of a search state.
     *
     * @param state The packed state
     * @return The flat index, -1 if no label was found
     */
    static int foundFlatIndex(long state) {
        return (int) state;
    }

    /**
     * Continues a search for the nearest label over the labels of one value. A search runs over
     * several values in turn and carries its state from one to the next, which lets the best label
     * found so far bound what is visited of the next value.
     *
     * @param labelValue The value whose labels are searched
     * @param excludedOwner An owner whose labels are passed over
     * @param from The coordinates the distance is measured from
     * @param radius The greatest distance at which a label counts
     * @param preferLowIndex How labels at equal distance are told apart: the lowest flat index if
     *                       {@code true}, the highest otherwise
     * @param state The state of the search so far, see {@link #searchState}
     * @return The state of the search after this value
     */
    long nearest(int labelValue, int excludedOwner, int[] from, int radius, boolean preferLowIndex, long state) {
        long[] table = slots;
        int slotMask = mask;
        Bucket[] several = buckets;
        long valueKey = (long) labelValue << Integer.SIZE;
        int best = (int) state;
        int bestDistance = (int) (state >>> Integer.SIZE);
        if (!tiledValues.get(labelValue)) {
            // The few labels of the value are held together: examine each
            int slot = slotOf(valueKey | ANYWHERE);
            if (slot < 0) {
                return state;
            }
            long content = table[(slot << 1) + 1];
            if (content >= 0) {
                if ((int) content != excludedOwner) {
                    int flatIndex = (int) (content >>> Integer.SIZE);
                    int distance = coordinates.distance(from, flatIndex);
                    if (distance <= radius && isNearer(distance, flatIndex, bestDistance, best, preferLowIndex)) {
                        bestDistance = distance;
                        best = flatIndex;
                    }
                }
                return searchState(bestDistance, best);
            }
            Bucket bucket = several[(int) (-1L - content)];
            for (int i = 0, n = bucket.size; i < n; i++) {
                if (bucket.owners[i] == excludedOwner) {
                    continue;
                }
                int flatIndex = bucket.flatIndexes[i];
                int distance = coordinates.distance(from, flatIndex);
                if (distance <= radius && isNearer(distance, flatIndex, bestDistance, best, preferLowIndex)) {
                    bestDistance = distance;
                    best = flatIndex;
                }
            }
            return searchState(bestDistance, best);
        }
        int bound = Math.min(radius, bestDistance);

        boolean toroidal = coordinates.isToroidal();
        int sizeFirst = coordinates.size(0);
        int sizeSecond = coordinates.dimensions() > 1 ? coordinates.size(1) : 1;
        int fromFirst = from[0];
        int fromSecond = coordinates.dimensions() > 1 ? from[1] : 0;
        int homeFirst = fromFirst >> TILE_SHIFT;
        int homeSecond = fromSecond >> TILE_SHIFT;

        // Tile offsets that reach every tile once: the shorter way around a torus, the tiles that
        // exist in a bounded world
        int minFirst = toroidal ? -((tilesAlongFirst - 1) / 2) : -homeFirst;
        int maxFirst = toroidal ? tilesAlongFirst / 2 : tilesAlongFirst - 1 - homeFirst;
        int minSecond = toroidal ? -((tilesAlongSecond - 1) / 2) : -homeSecond;
        int maxSecond = toroidal ? tilesAlongSecond / 2 : tilesAlongSecond - 1 - homeSecond;
        int lastRing = Math.max(Math.max(-minFirst, maxFirst), Math.max(-minSecond, maxSecond));

        for (int ring = 0; ring <= lastRing; ring++) {
            // A tile of ring r lies at least r - 1 whole tiles away; one tile more is allowed for,
            // because the last tile of a world that is no multiple of the tile side is narrower
            if (ring >= 2 && ((ring - 2) << TILE_SHIFT) + 1 > bound) {
                break;
            }
            for (int offsetFirst = Math.max(-ring, minFirst); offsetFirst <= Math.min(ring, maxFirst); offsetFirst++) {
                int tileFirst = wrap(homeFirst + offsetFirst, tilesAlongFirst);
                int gapFirst = gap(fromFirst, tileFirst, sizeFirst, toroidal);
                if (gapFirst > bound) {
                    continue;
                }
                // The ring's two outer columns are visited in full, the columns between them only
                // at the ring's two outer rows
                boolean outerColumn = offsetFirst == -ring || offsetFirst == ring;
                int step = outerColumn || ring == 0 ? 1 : 2 * ring;
                for (int offsetSecond = -ring; offsetSecond <= ring; offsetSecond += step) {
                    if (offsetSecond < minSecond || offsetSecond > maxSecond) {
                        continue;
                    }
                    int tileSecond = wrap(homeSecond + offsetSecond, tilesAlongSecond);
                    if (gapFirst + gap(fromSecond, tileSecond, sizeSecond, toroidal) > bound) {
                        continue;
                    }
                    long key = valueKey | (tileFirst * tilesAlongSecond + tileSecond);
                    int slot = home(key);
                    long content = Long.MIN_VALUE;
                    while (true) {
                        long found = table[slot << 1];
                        if (found == key) {
                            content = table[(slot << 1) + 1];
                            break;
                        }
                        if (found == EMPTY) {
                            break;
                        }
                        slot = (slot + 1) & slotMask;
                    }
                    if (content == Long.MIN_VALUE) {
                        continue;
                    }
                    if (content >= 0) {
                        if ((int) content != excludedOwner) {
                            int flatIndex = (int) (content >>> Integer.SIZE);
                            int distance = coordinates.distance(from, flatIndex);
                            if (distance <= radius && isNearer(distance, flatIndex, bestDistance, best, preferLowIndex)) {
                                bestDistance = distance;
                                best = flatIndex;
                                bound = Math.min(radius, bestDistance);
                            }
                        }
                        continue;
                    }
                    Bucket bucket = several[(int) (-1L - content)];
                    int[] flatIndexes = bucket.flatIndexes;
                    int[] owners = bucket.owners;
                    for (int i = 0, n = bucket.size; i < n; i++) {
                        if (owners[i] == excludedOwner) {
                            continue;
                        }
                        int flatIndex = flatIndexes[i];
                        int distance = coordinates.distance(from, flatIndex);
                        if (distance <= radius && isNearer(distance, flatIndex, bestDistance, best, preferLowIndex)) {
                            bestDistance = distance;
                            best = flatIndex;
                            bound = Math.min(radius, bestDistance);
                        }
                    }
                }
            }
        }
        return searchState(bestDistance, best);
    }

    private static int wrap(int tile, int tiles) {
        if (tile < 0) {
            return tile + tiles;
        }
        return tile >= tiles ? tile - tiles : tile;
    }

    /**
     * The distance along one dimension from a coordinate to the nearest cell of a tile.
     */
    private static int gap(int coordinate, int tile, int size, boolean toroidal) {
        int low = tile << TILE_SHIFT;
        int high = Math.min(low + TILE_SIDE, size) - 1;
        if (coordinate >= low && coordinate <= high) {
            return 0;
        }
        int direct = coordinate < low ? low - coordinate : coordinate - high;
        if (!toroidal) {
            return direct;
        }
        int around = coordinate < low ? coordinate + size - high : low + size - coordinate;
        return Math.min(direct, around);
    }

    /**
     * Whether a label beats the best found so far: the nearer one does, and of two at the same
     * distance the one the tie rule names.
     *
     * @param distance The label's distance
     * @param flatIndex The label's flat index
     * @param bestDistance The distance of the best label so far
     * @param bestFlatIndex The flat index of the best label so far
     * @param preferLowIndex Whether the lowest flat index wins a tie, the highest otherwise
     * @return {@code true} if the label is the new best
     */
    static boolean isNearer(int distance, int flatIndex, int bestDistance, int bestFlatIndex,
                            boolean preferLowIndex) {
        if (distance != bestDistance) {
            return distance < bestDistance;
        }
        return preferLowIndex ? flatIndex < bestFlatIndex : flatIndex > bestFlatIndex;
    }
}
