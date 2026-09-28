package org.evochora.cli.rendering.frame.descent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.node.processes.http.api.visualizer.descent.Ancestry;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrays;

/**
 * What the {@code descent} renderer knows about a run before it renders a frame: the parent and
 * the shade of every organism and the root of every recorded tick of the rendered range.
 * <p>
 * It is cut from a {@link DescentRecord}, which the pre-pass builds in one pass over the organism
 * lists of the recorded ticks, from the run's first tick up to the end of the rendered range;
 * environment cells are not read. Every organism appears in the list of at least one recorded
 * tick (a dead one once more, with its death), and a parent is born before its child, so a parent
 * has appeared by the tick its child first appears at. The pass therefore knows the parent of
 * every organism of a tick by the time it reaches the tick, and finds the root of the tick on the
 * spot:
 * <ul>
 *   <li><em>Parents</em>: organism id to parent id, {@link Ancestry#NO_PARENT} for a founder. A
 *       parent id that appears in no list is a founder's parent from before the run's first
 *       recorded tick (a forked run continues its parent run's ids) if it is at or below the
 *       number of organisms created up to that tick; any other such parent means that recorded
 *       ticks are missing, and the pass fails.</li>
 *   <li><em>Roots</em>: the youngest common ancestor of the organisms alive at a tick,
 *       {@link Ancestry#NO_PARENT} when they have none. Births hang below the living, so a
 *       tick's root descends from the root of the tick before; the {@link Builder} follows it
 *       incrementally from the births and deaths between consecutive recorded ticks.</li>
 *   <li><em>Shades</em>: the shade of every organism within its line ({@link LineShade}), worked
 *       out once, when the pass reads the organism for the first time, from its parent's shade
 *       and whether its genome hash differs from its parent's. A founder has shade 0; a parent the
 *       pass never read counts as shade 0; an organism that carries no parent genome hash
 *       although it has a parent counts as having its parent's genome.</li>
 * </ul>
 * <p>
 * It also answers the birth tick of every organism read ({@link #birthTickOf}), from the limits of
 * the whole record: the highest organism id read up to each recorded tick.
 * <p>
 * The batch files are read and decoded on a given number of threads, a few files ahead of the
 * pass, and handed to the pass in tick order; the pass itself runs on the calling thread.
 * <p>
 * Heap: twelve bytes per recorded tick of the range (the tick and its root), beside the record it
 * is cut from, which it keeps and whose parents it shares.
 * <p>
 * <strong>Thread Safety:</strong> immutable once built, and safe to share between the thread
 * instances of a renderer; the {@link Builder} is not thread-safe.
 */
final class DescentHistory {

    private final DescentRecord record;
    private final Ancestry ancestry;
    private final long[] ticks;
    private final int[] roots;

    private DescentHistory(final DescentRecord record, final Ancestry ancestry, final long[] ticks,
                           final int[] roots) {
        this.record = record;
        this.ancestry = ancestry;
        this.ticks = ticks;
        this.roots = roots;
    }

    /**
     * Receives the progress of the pre-pass, once per batch file taken.
     */
    @FunctionalInterface
    interface Progress {
        /**
         * Reports how far the pass has come.
         *
         * @param recordedTicks Recorded ticks taken so far
         * @param lastTick      The last recorded tick taken, {@link Long#MIN_VALUE} before the first
         */
        void update(int recordedTicks, long lastTick);
    }

    /**
     * Reads the organism lists of a run from its first batch up to the end of a range and cuts
     * the range from what it found.
     *
     * @param storage    The storage the run lies in
     * @param batchPaths Every batch file of the run, in tick order
     * @param startTick  First tick of the range, inclusive
     * @param endTick    Last tick of the range, inclusive
     * @return The history of the range
     * @throws Exception             if reading the storage fails
     * @throws IllegalStateException if the recorded ticks are out of order or incomplete
     */
    static DescentHistory read(final IBatchStorageRead storage, final List<StoragePath> batchPaths,
                               final long startTick, final long endTick) throws Exception {
        return of(scan(storage, batchPaths, endTick, 1, (ticks, tick) -> { }), startTick, endTick);
    }

    /**
     * Runs the pre-pass: reads the organism lists of a run from its first batch up to a tick.
     * <p>
     * Up to {@code threads} batch files are read and decoded at once, ahead of the pass; the
     * pass takes them in the order given. With one thread no file is read after the one that
     * holds {@code endTick}.
     *
     * @param storage    The storage the run lies in; read from {@code threads} threads at once
     * @param batchPaths Every batch file of the run, in tick order
     * @param endTick    Last tick to read, inclusive
     * @param threads    Batch files read at once (must be &gt;= 1)
     * @param progress   Receives the progress once per batch file taken
     * @return What the pass found, from the run's first recorded tick up to {@code endTick}
     * @throws Exception                if reading the storage fails
     * @throws IllegalArgumentException if {@code threads} is below 1
     * @throws IllegalStateException    if the recorded ticks are out of order or incomplete
     */
    static DescentRecord scan(final IBatchStorageRead storage, final List<StoragePath> batchPaths,
                              final long endTick, final int threads, final Progress progress) throws Exception {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1, got " + threads);
        }
        final Builder builder = new Builder(endTick);
        final ExecutorService readers = Executors.newFixedThreadPool(threads);
        try {
            final ArrayDeque<Future<List<TickDataChunk>>> ahead = new ArrayDeque<>();
            int next = 0;
            while (!builder.isComplete() && (next < batchPaths.size() || !ahead.isEmpty())) {
                while (ahead.size() < threads && next < batchPaths.size()) {
                    final StoragePath path = batchPaths.get(next++);
                    ahead.add(readers.submit(() -> readChunks(storage, path)));
                }
                for (final TickDataChunk chunk : resultOf(ahead.poll())) {
                    builder.accept(chunk);
                }
                progress.update(builder.tickCount(), builder.lastTick());
            }
        } finally {
            readers.shutdownNow();
        }
        return builder.build();
    }

    private static List<TickDataChunk> readChunks(final IBatchStorageRead storage, final StoragePath path)
            throws Exception {
        final List<TickDataChunk> chunks = new ArrayList<>();
        storage.forEachChunk(path, ChunkFieldFilter.SKIP_CELLS, chunks::add);
        return chunks;
    }

    /**
     * The chunks of a batch file read ahead, with a failure of the read rethrown as it was thrown.
     */
    private static List<TickDataChunk> resultOf(final Future<List<TickDataChunk>> read) throws Exception {
        try {
            return read.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    /**
     * Cuts a range from a record.
     *
     * @param record    What the pre-pass found, reaching at least as far as the range needs
     * @param startTick First tick of the range, inclusive
     * @param endTick   Last tick of the range, inclusive
     * @return The history of the range: its recorded ticks with their roots, the parents of the
     *         organisms read up to its end, and the birth ticks from the whole record
     */
    static DescentHistory of(final DescentRecord record, final long startTick, final long endTick) {
        int from = 0;
        while (from < record.tickCount() && record.tickAt(from) < startTick) {
            from++;
        }
        int to = from;
        while (to < record.tickCount() && record.tickAt(to) <= endTick) {
            to++;
        }
        // The last recorded tick at or before the end, also when the range holds none
        final int last = to - 1;
        final int limit = last >= 0 ? record.limitAt(last) : 0;
        final long[] ticks = new long[to - from];
        final int[] roots = new int[to - from];
        for (int i = from; i < to; i++) {
            ticks[i - from] = record.tickAt(i);
            roots[i - from] = record.rootAt(i);
        }
        return new DescentHistory(record, new Ancestry(record.parents(), limit), ticks, roots);
    }

    /**
     * The tick an organism was born at, as far as the record tells it.
     * <p>
     * The record holds no birth ticks. It holds, per recorded tick, the highest organism id read up
     * to it, and ids are handed out in birth order; so the value is the first recorded tick of the
     * whole record, not only of the range, whose highest id read is at least the organism's id.
     * It is exact to the recording interval of the run: the organism was born after the recorded
     * tick before it and at or before it. For an organism alive at the run's first recorded tick
     * (a founder, or an organism a forked run took over from its parent run) it is that first
     * recorded tick, however long before it the organism was born.
     * <p>
     * A binary search over the recorded ticks; allocates nothing.
     *
     * @param id The organism id
     * @return The tick
     * @throws IllegalArgumentException if the record did not read the organism
     */
    long birthTickOf(final int id) {
        final int count = record.tickCount();
        if (id <= 0 || count == 0 || id > record.limitAt(count - 1) || record.parents()[id] == Ancestry.UNREAD) {
            throw new IllegalArgumentException("Organism " + id + " was not read by the descent pre-pass");
        }
        int low = 0;
        int high = count - 1;
        while (low < high) {
            final int middle = (low + high) >>> 1;
            if (record.limitAt(middle) >= id) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return record.tickAt(low);
    }

    /**
     * The shade of an organism within its line ({@link LineShade}), read from the record without
     * copying.
     *
     * @param id The id of an organism the pre-pass read (1 to the highest id read)
     * @return The shade, in [{@code -}{@link LineShade#MAX_SHADE}, {@link LineShade#MAX_SHADE}]
     */
    byte shadeOf(final int id) {
        return record.shades()[id];
    }

    /**
     * The parents of the organisms read.
     *
     * @return A view up to the highest organism id read up to the end of the range
     */
    Ancestry ancestry() {
        return ancestry;
    }

    /**
     * The number of recorded ticks of the range.
     *
     * @return The count
     */
    int tickCount() {
        return ticks.length;
    }

    /**
     * A recorded tick of the range.
     *
     * @param index Its position among the recorded ticks of the range, from 0
     * @return The tick number
     */
    long tickAt(final int index) {
        return ticks[index];
    }

    /**
     * The position of a recorded tick among the recorded ticks of the range.
     *
     * @param tick The tick number
     * @return The position, from 0
     * @throws IllegalStateException if the tick is not a recorded tick of the range
     */
    int indexOf(final long tick) {
        final int index = Arrays.binarySearch(ticks, tick);
        if (index < 0) {
            throw new IllegalStateException("Tick " + tick + " was not read by the descent pre-pass");
        }
        return index;
    }

    /**
     * The root of a recorded tick of the range.
     *
     * @param index Its position among the recorded ticks of the range
     * @return The youngest common ancestor of the organisms alive at the tick,
     *         {@link Ancestry#NO_PARENT} when they have none
     */
    int rootAt(final int index) {
        return roots[index];
    }

    /**
     * Builds a record from the recorded ticks of a run, given in tick order.
     * <p>
     * The root is followed incrementally. Every organism carries a mark count: one for itself
     * while it is alive, and one for each of its children below which organisms are alive. A
     * birth adds its own mark and, while a count goes from 0 to 1, a mark to the parent; a death
     * takes its mark away and, while a count goes from 1 to 0, the parent's. Both walks stop at
     * the root at the latest, so the counts of the root and everything below it are exact, and an
     * organism with a positive count is the root or lies below it. The root descends while it is
     * not alive itself and exactly one of its children has living organisms below it (its count
     * is 1); the child is found on the line of any living organism, which lies below it. A root
     * left behind has its count cleared, so that no walk from outside can stop on it.
     * <p>
     * Work: per recorded tick one look at every organism of its list; per birth or death one
     * walk of at most the generations between the organism and the root, ending at the first
     * ancestor whose count does not change between 0 and 1; per recorded tick at which the root
     * moves one walk from a living organism up to the old root.
     * <p>
     * The shade of an organism is worked out when it is entered, after its parent's: the organisms
     * entered at one tick are taken in ascending id order, and a parent's id is below its child's.
     * <p>
     * Heap while building: thirteen bytes per organism (parent, shade, count, last tick seen alive).
     */
    static final class Builder {
        private static final int INITIAL_CAPACITY = 1024;
        /** Mark of the tick before the first: every organism starts as not alive at it. */
        private static final int BEFORE_FIRST = 1;

        private final long endTick;

        private int[] parents = new int[INITIAL_CAPACITY];
        private byte[] shades = new byte[INITIAL_CAPACITY];
        private int[] marks = new int[INITIAL_CAPACITY];
        private int[] aliveAt = new int[INITIAL_CAPACITY];
        private int maxId;
        private long boundary = -1;
        private long lastTick = Long.MIN_VALUE;
        private boolean complete;
        private int root = Ancestry.NO_PARENT;
        private int sequence = BEFORE_FIRST;

        private final LongArrayList ticks = new LongArrayList();
        private final IntArrayList roots = new IntArrayList();
        private final IntArrayList limits = new IntArrayList();
        /** The organisms entered at the current tick: id in the high, list position in the low half. */
        private final LongArrayList fresh = new LongArrayList();
        private IntArrayList living = new IntArrayList();
        private IntArrayList livingBefore = new IntArrayList();
        private final IntArrayList path = new IntArrayList();

        /**
         * Creates a builder that reads up to a tick.
         *
         * @param endTick Last tick to take, inclusive
         */
        Builder(final long endTick) {
            this.endTick = endTick;
            Arrays.fill(parents, Ancestry.UNREAD);
        }

        /**
         * Whether the last tick to take, or a tick beyond it, has been given, so that nothing
         * further is needed.
         *
         * @return {@code true} once the pass is complete
         */
        boolean isComplete() {
            return complete;
        }

        /**
         * The number of recorded ticks taken.
         *
         * @return The count
         */
        int tickCount() {
            return ticks.size();
        }

        /**
         * The last recorded tick taken.
         *
         * @return The tick, {@link Long#MIN_VALUE} before the first
         */
        long lastTick() {
            return lastTick;
        }

        /**
         * Takes the recorded ticks of one chunk: its snapshot and its deltas.
         *
         * @param chunk A chunk read with at least its organism lists
         * @throws IllegalStateException if the ticks are out of order or incomplete
         */
        void accept(final TickDataChunk chunk) {
            if (complete) {
                return;
            }
            acceptTick(chunk.getSnapshot().getTickNumber(), chunk.getSnapshot().getOrganismsList(),
                chunk.getSnapshot().getTotalOrganismsCreated());
            for (final TickDelta delta : chunk.getDeltasList()) {
                if (complete) {
                    return;
                }
                acceptTick(delta.getTickNumber(), delta.getOrganismsList(), delta.getTotalOrganismsCreated());
            }
        }

        /**
         * Takes one recorded tick.
         *
         * @param tick         The tick number; greater than every tick given before
         * @param organisms    Every organism of the tick, the dead included
         * @param totalCreated Organisms created in the run up to and including the tick
         * @throws IllegalStateException if the tick is not greater than the one before, an
         *                               organism names a parent that appeared in no list, or a
         *                               newly living organism does not descend from the root of
         *                               the tick before
         */
        void acceptTick(final long tick, final List<OrganismState> organisms, final long totalCreated) {
            if (tick > endTick) {
                complete = true;
                return;
            }
            if (tick <= lastTick) {
                throw new IllegalStateException("Recorded tick " + tick + " follows tick " + lastTick
                    + ": the batch files are not in tick order");
            }
            lastTick = tick;
            if (boundary < 0) {
                boundary = totalCreated;
            }
            enter(tick, organisms);
            advance(tick, organisms);
            ticks.add(tick);
            roots.add(root);
            limits.add(maxId);
            // Ticks only grow, so nothing after the last tick to take is needed
            complete = tick >= endTick;
        }

        /**
         * Enters the organisms seen for the first time, then resolves their parents and works out
         * their shades, in ascending id order.
         */
        private void enter(final long tick, final List<OrganismState> organisms) {
            fresh.clear();
            int position = 0;
            for (final OrganismState organism : organisms) {
                final int id = organism.getOrganismId();
                if (id <= 0) {
                    throw new IllegalStateException("Organism id " + id + " at tick " + tick + " is not positive");
                }
                if (id >= parents.length) {
                    grow(id);
                }
                if (parents[id] == Ancestry.UNREAD) {
                    parents[id] = organism.hasParentId() ? organism.getParentId() : Ancestry.NO_PARENT;
                    fresh.add(((long) id << 32) | position);
                    maxId = Math.max(maxId, id);
                }
                position++;
            }
            LongArrays.unstableSort(fresh.elements(), 0, fresh.size());
            for (int i = 0; i < fresh.size(); i++) {
                final long entry = fresh.getLong(i);
                final int id = (int) (entry >>> 32);
                final int parent = parents[id];
                if (parent == Ancestry.NO_PARENT) {
                    shades[id] = 0;
                    continue;
                }
                if (parent < 0 || parent >= id) {
                    throw new IllegalStateException("Organism " + id + " at tick " + tick
                        + " names parent " + parent + ", which is not below its own id");
                }
                final boolean parentRead = parents[parent] != Ancestry.UNREAD;
                if (!parentRead) {
                    if (parent > boundary) {
                        throw new IllegalStateException("Organism " + id + " at tick " + tick + " names parent "
                            + parent + ", which appears in no recorded tick up to it: recorded ticks are missing");
                    }
                    parents[id] = Ancestry.NO_PARENT;
                }
                shades[id] = shadeOf(organisms.get((int) entry), parentRead ? shades[parent] : 0);
            }
        }

        /**
         * The shade of an organism that has a parent.
         *
         * @param organism    The organism
         * @param parentShade The parent's shade, 0 for a parent the pass never read
         * @return The shade
         */
        private static byte shadeOf(final OrganismState organism, final byte parentShade) {
            if (!organism.hasParentGenomeHash() || organism.getGenomeHash() == organism.getParentGenomeHash()) {
                return parentShade;
            }
            return LineShade.drift(parentShade, organism.getGenomeHash());
        }

        private void grow(final int id) {
            final int capacity = Math.max(id + 1, parents.length + (parents.length >> 1));
            final int oldLength = parents.length;
            parents = Arrays.copyOf(parents, capacity);
            Arrays.fill(parents, oldLength, capacity, Ancestry.UNREAD);
            shades = Arrays.copyOf(shades, capacity);
            marks = Arrays.copyOf(marks, capacity);
            aliveAt = Arrays.copyOf(aliveAt, capacity);
        }

        /**
         * Takes the births and deaths since the tick before, then moves the root down as far as
         * the living of this tick allow.
         */
        private void advance(final long tick, final List<OrganismState> organisms) {
            sequence++;
            living.clear();
            for (final OrganismState organism : organisms) {
                if (organism.getIsDead()) {
                    continue;
                }
                final int id = organism.getOrganismId();
                if (aliveAt[id] == sequence) {
                    continue;
                }
                final boolean aliveBefore = aliveAt[id] == sequence - 1;
                aliveAt[id] = sequence;
                living.add(id);
                if (!aliveBefore) {
                    born(tick, id);
                }
            }
            for (int i = 0; i < livingBefore.size(); i++) {
                final int id = livingBefore.getInt(i);
                if (aliveAt[id] != sequence) {
                    died(id);
                }
            }
            final IntArrayList swap = livingBefore;
            livingBefore = living;
            living = swap;
            descend();
        }

        /**
         * Marks an organism alive and its ancestors down to the first that already leads to the
         * living, or to the root.
         */
        private void born(final long tick, final int id) {
            int x = id;
            while (true) {
                if (x < root) {
                    // Ids grow with descent: an id below the root does not descend from it
                    throw new IllegalStateException("A living organism " + id + " at tick " + tick
                        + " does not descend from the root " + root + " of the tick before");
                }
                if (marks[x]++ > 0 || x == root) {
                    return;
                }
                x = parents[x];
            }
        }

        /**
         * Takes the mark of a dead organism away, and from its ancestors while they no longer
         * lead to the living, up to the root.
         */
        private void died(final int id) {
            int x = id;
            while (--marks[x] == 0 && x != root) {
                x = parents[x];
            }
        }

        /**
         * Moves the root down while it is not alive and exactly one of its children leads to
         * the living.
         */
        private void descend() {
            if (marks[root] != 1 || isAlive(root)) {
                return;
            }
            // Its one child that leads to the living lies on the line of every living organism
            path.clear();
            for (int x = livingBefore.getInt(0); x != root; x = parents[x]) {
                path.add(x);
            }
            int below = path.size() - 1;
            while (marks[root] == 1 && !isAlive(root)) {
                marks[root] = 0;
                root = path.getInt(below--);
            }
        }

        private boolean isAlive(final int id) {
            return id != Ancestry.NO_PARENT && aliveAt[id] == sequence;
        }

        /**
         * Finishes the record.
         *
         * @return The record of what was given
         */
        DescentRecord build() {
            return new DescentRecord(parents, shades, maxId, ticks.toLongArray(), roots.toIntArray(), limits.toIntArray());
        }
    }
}
