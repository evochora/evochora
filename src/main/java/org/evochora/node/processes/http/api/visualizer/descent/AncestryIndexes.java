package org.evochora.node.processes.http.api.visualizer.descent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ancestry indexes of the runs a web server is asked about, and the one thread that builds and
 * updates them.
 * <p>
 * Key features:
 * <ul>
 *   <li>One {@link AncestryIndex} per run, created on the first request for the run. A request
 *       for a run drops the indexes of the other runs that nobody has asked for within the idle
 *       time; a run asked for again after that is read anew. The index of the run asked for is
 *       never dropped by its own request, however long it rested. The heap cost is the sum of the
 *       runs looked at within the idle time.</li>
 *   <li>One shared single-thread executor for the catch-ups of all runs, so however many runs are
 *       opened, reading them costs one thread and one database connection at a time.</li>
 *   <li>{@link #close()} tells the catch-ups to stop, shuts the executor down and waits for a
 *       running page to end, which the paged reads keep short; it has to run before the database
 *       pool closes. The catch-up thread is never interrupted: an interrupt inside the database's
 *       file I/O would close the file channel for every connection.</li>
 * </ul>
 * <p>
 * <strong>Thread safety:</strong> thread-safe; {@link #forRun(String)} may be called from any
 * number of request threads. A request that takes the queries of a run in the moment another
 * request drops the run's index is answered from the dropped index, whose catch-up has stopped;
 * the next request for the run finds a new one.
 */
public final class AncestryIndexes implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AncestryIndexes.class);

    /**
     * How long {@link #close()} waits for a running catch-up to reach the end of its page. A page
     * takes a fraction of a second, so close normally returns at once; the bound only covers a
     * database that blocks.
     */
    static final long CLOSE_TIMEOUT_SECONDS = 5;

    /** Idle time after which the index of a run may be dropped, where none is given. */
    static final long DEFAULT_KEEP_IDLE_NANOS = TimeUnit.MINUTES.toNanos(10);

    /** The index of one run with the time it was asked for last. */
    private static final class Kept {
        private final DescentQuery query;
        private final AtomicBoolean dropped;
        private volatile long lastAsked;

        private Kept(final DescentQuery query, final AtomicBoolean dropped, final long lastAsked) {
            this.query = query;
            this.dropped = dropped;
            this.lastAsked = lastAsked;
        }
    }

    private final IDatabaseReaderProvider provider;
    private final ExecutorService executor;
    private final int pageSize;
    private final LongSupplier clock;
    private final int attributionArrays;
    private final long keepIdleNanos;
    private final ConcurrentHashMap<String, Kept> runs = new ConcurrentHashMap<>();
    private volatile boolean closing;

    /**
     * Creates the indexes with their own catch-up thread, which starts with the first catch-up.
     *
     * @param provider          Source of the readers the catch-ups take
     * @param attributionArrays Roots per run whose attribution array is kept for incremental
     *                          counting of the line sizes (must be &gt;= 0)
     * @param keepIdleMinutes   Minutes without a request after which the index of a run may be
     *                          dropped (must be &gt;= 0; 0 keeps the run asked for last only)
     * @throws IllegalArgumentException if {@code attributionArrays} or {@code keepIdleMinutes} is
     *                                  negative
     */
    public AncestryIndexes(final IDatabaseReaderProvider provider, final int attributionArrays,
                           final int keepIdleMinutes) {
        this(provider, Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "ancestry-index");
            thread.setDaemon(true);
            return thread;
        }), AncestryIndex.PAGE_SIZE, System::nanoTime, attributionArrays,
            TimeUnit.MINUTES.toNanos(keepIdleMinutes));
    }

    /**
     * Creates the indexes on a given executor and page size, with the default idle time.
     *
     * @param provider Source of the readers the catch-ups take
     * @param executor Executor the catch-ups of all runs run on; must run one task at a time
     * @param pageSize Rows per read (must be &gt; 0)
     * @param clock    Monotonic clock in nanoseconds for the cooldown and the idle time
     * @param attributionArrays Roots per run whose attribution array is kept (must be &gt;= 0)
     * @throws IllegalArgumentException if {@code attributionArrays} is negative
     */
    AncestryIndexes(final IDatabaseReaderProvider provider, final ExecutorService executor, final int pageSize,
                    final LongSupplier clock, final int attributionArrays) {
        this(provider, executor, pageSize, clock, attributionArrays, DEFAULT_KEEP_IDLE_NANOS);
    }

    /**
     * Creates the indexes on a given executor, page size and idle time.
     *
     * @param provider Source of the readers the catch-ups take
     * @param executor Executor the catch-ups of all runs run on; must run one task at a time
     * @param pageSize Rows per read (must be &gt; 0)
     * @param clock    Monotonic clock in nanoseconds for the cooldown and the idle time
     * @param attributionArrays Roots per run whose attribution array is kept (must be &gt;= 0)
     * @param keepIdleNanos     Nanoseconds without a request after which the index of a run may be
     *                          dropped (must be &gt;= 0)
     * @throws IllegalArgumentException if {@code attributionArrays} or {@code keepIdleNanos} is
     *                                  negative
     */
    AncestryIndexes(final IDatabaseReaderProvider provider, final ExecutorService executor, final int pageSize,
                    final LongSupplier clock, final int attributionArrays, final long keepIdleNanos) {
        if (attributionArrays < 0) {
            throw new IllegalArgumentException("attributionArrays must not be negative: " + attributionArrays);
        }
        if (keepIdleNanos < 0) {
            throw new IllegalArgumentException("The idle time must not be negative");
        }
        this.keepIdleNanos = keepIdleNanos;
        this.provider = provider;
        this.executor = executor;
        this.pageSize = pageSize;
        this.clock = clock;
        this.attributionArrays = attributionArrays;
    }

    /**
     * The queries on the index of one run, creating the index on first use. Creating it reads
     * nothing; the first request's view asks for the first catch-up. The indexes of the other
     * runs that were not asked for within the idle time are dropped.
     *
     * @param runId Run to answer for
     * @return The run's queries
     */
    public DescentQuery forRun(final String runId) {
        final long now = clock.getAsLong();
        final Kept kept = runs.computeIfAbsent(runId, id -> {
            final AtomicBoolean dropped = new AtomicBoolean();
            return new Kept(new DescentQuery(new AncestryIndex(id, provider, executor, pageSize, clock,
                () -> closing || dropped.get()), attributionArrays), dropped, now);
        });
        kept.lastAsked = now;
        for (final Map.Entry<String, Kept> other : runs.entrySet()) {
            final Kept idle = other.getValue();
            if (idle != kept && now - idle.lastAsked > keepIdleNanos && runs.remove(other.getKey(), idle)) {
                // A catch-up of the dropped index ends at its next page or gap
                idle.dropped.set(true);
                LOGGER.debug("Ancestry index of run '{}' dropped: not asked for within the idle time",
                    other.getKey());
            }
        }
        return kept.query;
    }

    /**
     * The number of runs whose index is kept.
     *
     * @return The count
     */
    int keptRuns() {
        return runs.size();
    }

    /**
     * Stops the catch-up thread: a pending catch-up ends before it reads anything, a running one
     * ends after its current page or gap. Waits up to {@value #CLOSE_TIMEOUT_SECONDS} seconds for
     * that. A stop is not a failure and is logged at DEBUG only.
     */
    @Override
    public void close() {
        closing = true;
        executor.shutdown();
        LOGGER.debug("Stopping the ancestry index thread");
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("Ancestry index thread did not end within {} s", CLOSE_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            LOGGER.debug("Interrupted while waiting for the ancestry index thread to end");
            Thread.currentThread().interrupt();
        }
    }
}
