package org.evochora.node.processes.http.api.visualizer.descent;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.database.MetadataNotFoundException;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.ParentRows;
import org.evochora.datapipeline.api.resources.database.dto.TickRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * The parent of every organism of one run, held in the heap of the web server.
 * <p>
 * The index is a plain {@code int[]} indexed by organism id: the entry of an id is the id of its
 * parent, {@link Ancestry#NO_PARENT} for a founder, and {@link Ancestry#UNREAD} for an id whose row
 * has not been read. The walks over it are those of {@link Ancestry}, which every
 * {@link #snapshot()} carries as a view up to the cursor. It is derived from the {@code organisms} table alone and can be rebuilt from it at any
 * time, so nothing about it is persisted.
 * <p>
 * Key features:
 * <ul>
 *   <li>Built and kept current by one mechanism, the catch-up: it reads the table in pages of
 *       ascending ids from the cursor to the end of the table, and re-reads every gap it knows.
 *       A request never waits for it; it answers from the array as it is and asks for a
 *       catch-up when the array lags behind the tick it was asked about.</li>
 *   <li>Forks: a run forked from another continues the id counter of its parent run, so its
 *       first organisms name parents no row of this run will ever hold. The boundary B is the
 *       number of organisms created up to the first recorded tick of the fork, 0 for a run that
 *       began fresh. A parent id at or below B whose row is absent makes its child a founder.</li>
 *   <li>Gaps: a range of ids above B that a page skipped. Under competing consumers the rows of
 *       a chunk can arrive after those of a later chunk, so a gap is not a founder but unknown
 *       until its rows are read. Gaps are kept as ranges and re-read with the same paged read.</li>
 *   <li>States: {@link State#LOADING} while B is not known or neither the cursor nor B reaches
 *       the requested tick's total, {@link State#READY} once every id up to that total has been
 *       read at least once, {@link State#FAILED} when the last catch-up threw.</li>
 *   <li>Connections: every page is read with a reader of its own, taken from the provider and
 *       closed again, so a catch-up over millions of rows never holds a pooled connection for
 *       longer than one page.</li>
 *   <li>Stopping: a catch-up checks the stop signal of its {@link AncestryIndexes} between pages
 *       and between gaps and ends there; it is never interrupted, because an interrupt inside the
 *       database's file I/O would close the file channel for every connection.</li>
 * </ul>
 * <p>
 * <strong>Thread safety.</strong> The array is written by one catch-up at a time and read by the
 * request threads without locking. That there is only one writer is guaranteed by the index's
 * monitor, which a running catch-up holds, not by the executor it runs on. Readers take the
 * version, the gap-fill counter, the cursor and the array reference from {@code volatile} fields,
 * in that order, through {@link #snapshot()}. The writer fills a page into the array first, then
 * publishes the array reference (a larger copy when the page does not fit), then the cursor, then
 * a new version. A reader that has seen a cursor therefore sees every entry at or below it; an
 * entry above the cursor belongs to a page in progress and is reported as {@link Ancestry#UNREAD}. A gap
 * filled later is written in place and published by the gap-fill counter and the version, which
 * readers use as cache keys. A reader holding an older array reference sees an older but
 * consistent state.
 * <p>
 * Cost: four bytes per organism of the run, kept for the life of the process.
 */
public final class AncestryIndex {

    /** Number of rows one read of the table returns at most. */
    static final int PAGE_SIZE = 100_000;

    /**
     * Least time between the end of a catch-up and a catch-up asked for by
     * {@link #requestGapReread()}. It is the server's own protection: a gap that never fills costs
     * at most one re-read per cooldown, whatever the clients do.
     */
    static final long GAP_REREAD_COOLDOWN_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

    private static final Logger LOGGER = LoggerFactory.getLogger(AncestryIndex.class);

    private static final int INITIAL_CAPACITY = 1024;

    /**
     * The state of an index as reported to the client.
     */
    public enum State {
        /** The boundary is not known yet, or the array has not reached the requested tick. */
        LOADING,
        /** Every id up to the requested tick's total has been read at least once. */
        READY,
        /** The last catch-up threw; the cause is kept and the next request retries. */
        FAILED;

        /**
         * The name of the state as it travels in the JSON answer.
         *
         * @return The lower-case name
         */
        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * A consistent view of the array at one moment, taken by {@link #snapshot()}.
     * <p>
     * Package-private because its {@link Ancestry} reads the live array: the array may receive
     * further entries, but none at or below the cursor changes except a gap entry going from
     * {@link Ancestry#UNREAD} to its parent. Nothing outside this package may write into it.
     *
     * @param version  The version counter when the view was taken
     * @param gapFills The number of catch-ups that filled a gap, when the view was taken
     * @param boundary The boundary B, or -1 while it is not known
     * @param ancestry The array as far as the cursor, with the walks over it; its limit is the
     *                 cursor
     * @param failure  The failure of the last catch-up, {@code null} if it succeeded or none ran
     */
    record Snapshot(long version, long gapFills, int boundary, Ancestry ancestry, Throwable failure) {

        /**
         * Every id at or below the cursor has been read at least once.
         *
         * @return The cursor when the view was taken
         */
        int cursor() {
            return ancestry.limit();
        }

        /**
         * The state of the index for a tick, as this view shows it.
         * <p>
         * Ids at or below B that have no row belong to the parent run of a fork and are never
         * read, so the index has reached a tick once the cursor or B reaches its total.
         *
         * @param tickTotal Organisms created up to the requested tick
         * @return {@link State#FAILED} while the last catch-up's failure stands, else
         *         {@link State#LOADING} or {@link State#READY}
         */
        State stateFor(final int tickTotal) {
            if (failure != null) {
                return State.FAILED;
            }
            if (boundary < 0 || Math.max(cursor(), boundary) < tickTotal) {
                return State.LOADING;
            }
            return State.READY;
        }

        /**
         * The parent of an organism as far as this view knows it.
         *
         * @param id Organism id (must be &gt; 0)
         * @return The parent id, {@link Ancestry#NO_PARENT} for a founder, {@link Ancestry#UNREAD}
         *         for an id this view has not read
         */
        int parentOf(final int id) {
            return ancestry.parentOf(id);
        }
    }

    private final String runId;
    private final IDatabaseReaderProvider provider;
    private final Executor executor;
    private final int pageSize;
    private final BooleanSupplier stopping;

    private volatile int[] parents;
    private volatile int cursor;
    private volatile int boundary = -1;
    private volatile long version;
    private volatile long gapFills;
    private volatile int newestTotal;
    private volatile Throwable failure;

    private final AtomicBoolean pending = new AtomicBoolean();
    private final LongSupplier clock;
    // Clock reading at the end of the last catch-up; meaningful only once one has ended
    private volatile long lastCatchUpEnd;
    private volatile boolean catchUpEnded;

    // Written and read only by the running catch-up, under the index's monitor
    private IntArrayList gapFrom = new IntArrayList();
    private IntArrayList gapTo = new IntArrayList();

    /**
     * Creates an empty index; nothing is read until a request asks for it.
     *
     * @param runId    Run whose organisms the index holds
     * @param provider Source of the reader each catch-up takes and closes again
     * @param executor Executor the catch-ups run on; shared by the indexes of all runs
     * @param pageSize Rows per read (must be &gt; 0)
     * @param clock    Monotonic clock in nanoseconds, {@link System#nanoTime()} outside tests
     * @param stopping Tells a catch-up to end at the next page or gap boundary
     * @throws IllegalArgumentException if {@code pageSize} is not positive
     */
    AncestryIndex(final String runId, final IDatabaseReaderProvider provider, final Executor executor,
                  final int pageSize, final LongSupplier clock, final BooleanSupplier stopping) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        this.runId = runId;
        this.provider = provider;
        this.executor = executor;
        this.pageSize = pageSize;
        this.clock = clock;
        this.stopping = stopping;
        final int[] initial = new int[INITIAL_CAPACITY];
        Arrays.fill(initial, Ancestry.UNREAD);
        this.parents = initial;
    }

    /**
     * Takes a consistent view of the array.
     * <p>
     * <strong>Thread safety:</strong> safe from any thread, lock-free.
     *
     * @return The view
     */
    Snapshot snapshot() {
        final long v = version;
        final long g = gapFills;
        final int c = cursor;
        final int b = boundary;
        return new Snapshot(v, g, b, new Ancestry(parents, c), failure);
    }

    /**
     * How far the index has come, relative to the boundary: {@code (cursor - B) / (T - B)}, where
     * T is the number of organisms created up to the run's newest tick as the last catch-up found
     * it. For a run that began fresh B is 0 and this is the cursor over T; for a fork, the ids at
     * or below B belong to the parent run and do not count as progress.
     *
     * @param view A view taken by {@link #snapshot()}
     * @return A share clamped to [0, 1]; 0 while B or T is not known
     */
    double progressOf(final Snapshot view) {
        final int total = newestTotal;
        final int b = view.boundary();
        if (b < 0 || total <= b) {
            return 0.0;
        }
        final double share = (double) (view.cursor() - b) / (total - b);
        return Math.max(0.0, Math.min(1.0, share));
    }

    /**
     * The organisms created up to the run's newest tick, as the last catch-up found it.
     *
     * @return The total, 0 while no catch-up has read it
     */
    int newestTotal() {
        return newestTotal;
    }

    /**
     * Asks for a catch-up if the index lags behind a tick, has not found its boundary yet, or
     * failed last time. Returns at once; the catch-up runs on the executor.
     * <p>
     * At most one catch-up per index is pending; a request while one is pending adds nothing.
     * <p>
     * <strong>Thread safety:</strong> safe from any thread.
     *
     * @param tickTotal Organisms created up to the requested tick
     */
    public void requestCatchUp(final int tickTotal) {
        if (failure == null && boundary >= 0 && Math.max(cursor, boundary) >= tickTotal) {
            return;
        }
        submit();
    }

    /**
     * Asks for a catch-up whatever the cursor says, for a request that met an organism whose
     * ancestry is unknown: the catch-up re-reads every gap, so rows of a chunk indexed after the
     * index passed it are picked up even when the run no longer grows. Returns at once.
     * <p>
     * Nothing is submitted within {@link #GAP_REREAD_COOLDOWN_NANOS} of the end of the last
     * catch-up, so a gap that never fills is re-read once per cooldown however many requests meet
     * it. At most one catch-up per index is pending; a request while one is pending adds nothing.
     * <p>
     * <strong>Thread safety:</strong> safe from any thread.
     */
    public void requestGapReread() {
        if (catchUpEnded && clock.getAsLong() - lastCatchUpEnd < GAP_REREAD_COOLDOWN_NANOS) {
            return;
        }
        submit();
    }

    /**
     * Schedules one catch-up unless one is pending already.
     */
    private void submit() {
        if (!pending.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::runCatchUp);
        } catch (RejectedExecutionException e) {
            pending.set(false);
            LOGGER.debug("Catch-up of the ancestry index of run '{}' not scheduled: executor is shut down", runId);
        }
    }

    /**
     * One catch-up: reads, and records the outcome.
     * <p>
     * An {@link OutOfMemoryError} is recorded as the failure like an exception, so that the
     * index reports it instead of loading forever; every other {@link Error} propagates.
     */
    private synchronized void runCatchUp() {
        pending.set(false);
        if (stopping.getAsBoolean()) {
            LOGGER.debug("Catch-up of the ancestry index of run '{}' skipped: stopping", runId);
            return;
        }
        try {
            catchUp();
            failure = null;
        } catch (Exception | OutOfMemoryError e) {
            final boolean first = failure == null;
            failure = e;
            if (first) {
                LOGGER.error("Reading the ancestry of run '{}' failed", runId, e);
            } else {
                LOGGER.debug("Reading the ancestry of run '{}' failed again: {}", runId, e.getMessage());
            }
        } finally {
            lastCatchUpEnd = clock.getAsLong();
            catchUpEnded = true;
        }
    }

    private void catchUp() throws SQLException, TickNotFoundException {
        try (IDatabaseReader reader = provider.createReader(runId)) {
            if (boundary < 0) {
                final int found = readBoundary(reader);
                if (found < 0) {
                    LOGGER.debug("Ancestry index of run '{}' waits for the first recorded tick of the run", runId);
                    return;
                }
                boundary = found;
            }
            final TickRange range = reader.getOrganismTickRange();
            if (range != null) {
                newestTotal = reader.readTotalOrganismsCreated(range.maxTick());
            }
        }
        if (!readTail() || !rereadGaps()) {
            LOGGER.debug("Catch-up of the ancestry index of run '{}' stopped at id {}", runId, cursor);
            return;
        }
        LOGGER.debug("Ancestry index of run '{}' read up to id {}, {} gaps", runId, cursor, gapFrom.size());
    }

    /**
     * Reads one page with a reader of its own, which is closed again before the page is used.
     */
    private ParentRows readPage(final int afterId, final int limit) throws SQLException {
        try (IDatabaseReader reader = provider.createReader(runId)) {
            return reader.readParents(afterId, limit);
        }
    }

    /**
     * Finds the boundary B: 0 for a run that began fresh, the organisms created up to the first
     * tick of the fork for a forked run.
     *
     * @return B, or -1 while the metadata or the fork's first tick is not indexed yet
     */
    private static int readBoundary(final IDatabaseReader reader) throws SQLException {
        if (!reader.hasMetadata()) {
            return -1;
        }
        final SimulationMetadata metadata;
        try {
            metadata = reader.getMetadata();
        } catch (MetadataNotFoundException e) {
            return -1;
        }
        if (!metadata.hasFork()) {
            return 0;
        }
        try {
            return reader.readTotalOrganismsCreated(metadata.getFork().getFirstTick());
        } catch (TickNotFoundException e) {
            return -1;
        }
    }

    /**
     * Reads pages from the cursor to the end of the table, publishing each.
     *
     * @return {@code false} if the index was told to stop between two pages
     */
    private boolean readTail() throws SQLException {
        while (true) {
            if (stopping.getAsBoolean()) {
                return false;
            }
            final int after = cursor;
            final ParentRows page = readPage(after, pageSize);
            final int n = page.size();
            if (n == 0) {
                return true;
            }
            final int[] ids = page.ids();
            final int last = ids[n - 1];
            int[] array = parents;
            if (last >= array.length) {
                array = grow(array, last + 1);
            }
            int previous = after;
            for (int i = 0; i < n; i++) {
                final int id = ids[i];
                if (id > previous + 1) {
                    addGap(gapFrom, gapTo, previous + 1, id - 1);
                }
                array[id] = normalise(array, page.parents()[i]);
                previous = id;
            }
            parents = array;
            cursor = last;
            version++;
            if (n < pageSize) {
                return true;
            }
        }
    }

    /**
     * Re-reads every known gap; what is still missing becomes the new set of gaps. A gap not
     * reached because the index was told to stop is kept as it was.
     *
     * @return {@code false} if the index was told to stop between two gaps
     */
    private boolean rereadGaps() throws SQLException {
        if (gapFrom.isEmpty()) {
            return true;
        }
        boolean complete = true;
        final IntArrayList remainingFrom = new IntArrayList();
        final IntArrayList remainingTo = new IntArrayList();
        boolean filled = false;
        try {
            for (int g = 0; g < gapFrom.size(); g++) {
                if (stopping.getAsBoolean()) {
                    remainingFrom.addAll(gapFrom.subList(g, gapFrom.size()));
                    remainingTo.addAll(gapTo.subList(g, gapTo.size()));
                    complete = false;
                    break;
                }
                filled |= rereadGap(gapFrom.getInt(g), gapTo.getInt(g), remainingFrom, remainingTo);
            }
            // Replaced only when every gap has been gone through; after a failure the old ranges
            // stay and are read again, which finds the entries filled so far already present.
            gapFrom = remainingFrom;
            gapTo = remainingTo;
        } finally {
            if (filled) {
                gapFills++;
                version++;
            }
        }
        return complete;
    }

    /**
     * Re-reads one gap in pages bounded to it, filling what has arrived in place.
     *
     * @return {@code true} if at least one entry was filled
     */
    private boolean rereadGap(final int from, final int to,
                              final IntArrayList remainingFrom, final IntArrayList remainingTo)
            throws SQLException {
        final int[] array = parents;
        boolean filled = false;
        int previous = from - 1;
        int after = from - 1;
        while (after < to) {
            if (after >= from && stopping.getAsBoolean()) {
                // Between two pages of one gap: the rest of it is kept below
                break;
            }
            final int limit = (int) Math.min(pageSize, (long) to - after);
            final ParentRows page = readPage(after, limit);
            final int n = page.size();
            boolean beyond = false;
            for (int i = 0; i < n; i++) {
                final int id = page.ids()[i];
                if (id > to) {
                    beyond = true;
                    break;
                }
                if (id > previous + 1) {
                    addGap(remainingFrom, remainingTo, previous + 1, id - 1);
                }
                array[id] = normalise(array, page.parents()[i]);
                previous = id;
                filled = true;
            }
            if (beyond || n < limit) {
                break;
            }
            after = page.ids()[n - 1];
        }
        if (previous < to) {
            addGap(remainingFrom, remainingTo, previous + 1, to);
        }
        return filled;
    }

    /**
     * Records a range of missing ids, as far as it lies above the boundary.
     */
    private void addGap(final IntArrayList from, final IntArrayList to, final int first, final int last) {
        final int start = Math.max(first, boundary + 1);
        if (start <= last) {
            from.add(start);
            to.add(last);
        }
    }

    /**
     * The entry for a parent as read: a parent at or below the boundary whose row is absent was
     * created before the run's first recorded tick and makes its child a founder.
     * <p>
     * Rows arrive in ascending id order and a parent's id is below its child's, so a parent row
     * that exists at all has been entered before its child is.
     */
    private int normalise(final int[] array, final int parent) {
        if (parent == Ancestry.NO_PARENT) {
            return Ancestry.NO_PARENT;
        }
        if (parent <= boundary && array[parent] == Ancestry.UNREAD) {
            return Ancestry.NO_PARENT;
        }
        return parent;
    }

    private static int[] grow(final int[] array, final int needed) {
        final int capacity = Math.max(needed, array.length + (array.length >> 1));
        final int[] larger = Arrays.copyOf(array, capacity);
        Arrays.fill(larger, array.length, capacity, Ancestry.UNREAD);
        return larger;
    }
}
