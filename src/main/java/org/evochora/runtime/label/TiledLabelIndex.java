package org.evochora.runtime.label;

import java.util.Arrays;
import java.util.BitSet;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.EnvironmentProperties;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * All labels of a world by value and by place, for the search of the nearest label with a value.
 * <p>
 * The world is divided into square tiles of {@link #TILE_SIDE} cells over its first two dimensions;
 * further dimensions are not divided. Per label value there is one array with a slot per tile, and
 * a tile that holds labels of the value has a small unordered bucket of them there. Under stable
 * label values one value is carried by every organism with that gene, so the labels of a value are
 * as many as the population — the tiles keep both the search and a change from touching more of
 * them than lie nearby:
 * <ul>
 *   <li>A search visits the tiles in rings around the caller's tile and ends with the ring no tile
 *       of which can hold a label nearer than the best found, or within the radius if none is
 *       found. Its cost follows the distance to the target, not the size of the population.</li>
 *   <li>An addition appends to one bucket, a removal or an owner change scans one bucket.</li>
 * </ul>
 * The order of a bucket depends on the order of the changes, which differs between a run and its
 * resume. No result depends on it: a search compares every candidate by distance and then by flat
 * index, which is a total order.
 * <p>
 * The tile side is a trade between the two ends of a search. Smaller tiles end a successful search
 * sooner in a dense world; larger tiles leave fewer empty tiles to pass in a sparse one and need
 * less memory for the per-value arrays.
 * <p>
 * Thread Safety: not synchronized. Mutated only from the simulation thread outside the parallel
 * wave, read concurrently inside it.
 */
final class TiledLabelIndex {

    /** Side of a tile in cells; a power of two. */
    static final int TILE_SIDE = 128;

    private static final int TILE_SHIFT = Integer.numberOfTrailingZeros(TILE_SIDE);

    /** The labels of one value within one tile: flat indexes and owners, unordered. */
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

    /** The labels of one value: a bucket per tile that holds any, and their number. */
    private static final class ValueTiles {
        private final Bucket[] buckets;
        private int labels;

        private ValueTiles(int tileCount) {
            buckets = new Bucket[tileCount];
        }
    }

    private final CoordinateDecoder coordinates;
    private final int tilesAlongFirst;
    private final int tilesAlongSecond;
    private final Int2ObjectOpenHashMap<ValueTiles> tilesByValue = new Int2ObjectOpenHashMap<>();

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
        return tilesByValue.isEmpty();
    }

    /**
     * Adds a label, or sets the owner of the label already at that cell: a cell holds one label.
     *
     * @param labelValue The label's value
     * @param flatIndex The flat index of the cell holding the label
     * @param owner The owner of the cell
     */
    void put(int labelValue, int flatIndex, int owner) {
        ValueTiles ofValue = tilesByValue.get(labelValue);
        if (ofValue == null) {
            ofValue = new ValueTiles(tilesAlongFirst * tilesAlongSecond);
            tilesByValue.put(labelValue, ofValue);
            valuesInUse.set(labelValue);
        }
        int tile = tileOf(flatIndex);
        Bucket bucket = ofValue.buckets[tile];
        if (bucket == null) {
            bucket = new Bucket();
            ofValue.buckets[tile] = bucket;
        }
        int position = bucket.positionOf(flatIndex);
        if (position >= 0) {
            bucket.owners[position] = owner;
            return;
        }
        if (bucket.size == bucket.flatIndexes.length) {
            bucket.flatIndexes = Arrays.copyOf(bucket.flatIndexes, bucket.size * 2);
            bucket.owners = Arrays.copyOf(bucket.owners, bucket.size * 2);
        }
        bucket.flatIndexes[bucket.size] = flatIndex;
        bucket.owners[bucket.size] = owner;
        bucket.size++;
        ofValue.labels++;
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
        ValueTiles ofValue = tilesByValue.get(labelValue);
        Bucket bucket = ofValue == null ? null : ofValue.buckets[tileOf(flatIndex)];
        int position = bucket == null ? -1 : bucket.positionOf(flatIndex);
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
        ValueTiles ofValue = tilesByValue.get(labelValue);
        if (ofValue == null) {
            return false;
        }
        int tile = tileOf(flatIndex);
        Bucket bucket = ofValue.buckets[tile];
        int position = bucket == null ? -1 : bucket.positionOf(flatIndex);
        if (position < 0) {
            return false;
        }
        bucket.size--;
        bucket.flatIndexes[position] = bucket.flatIndexes[bucket.size];
        bucket.owners[position] = bucket.owners[bucket.size];
        if (bucket.size == 0) {
            ofValue.buckets[tile] = null;
        }
        if (--ofValue.labels == 0) {
            tilesByValue.remove(labelValue);
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
        ValueTiles ofValue = tilesByValue.get(labelValue);
        Bucket bucket = ofValue == null ? null : ofValue.buckets[tileOf(flatIndex)];
        int position = bucket == null ? -1 : bucket.positionOf(flatIndex);
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
        ValueTiles ofValue = tilesByValue.get(labelValue);
        if (ofValue == null) {
            return state;
        }
        Bucket[] buckets = ofValue.buckets;
        int best = (int) state;
        int bestDistance = (int) (state >>> Integer.SIZE);
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
                    Bucket bucket = buckets[tileFirst * tilesAlongSecond + tileSecond];
                    if (bucket == null || gapFirst + gap(fromSecond, tileSecond, sizeSecond, toroidal) > bound) {
                        continue;
                    }
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
