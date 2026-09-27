package org.evochora.node.processes.http.api.visualizer.descent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ancestry indexes of all runs a web server has been asked about, and the one thread that
 * builds and updates them.
 * <p>
 * Key features:
 * <ul>
 *   <li>One {@link AncestryIndex} per run, created on the first request for the run and kept for
 *       the life of the process: the heap cost is the sum of the runs looked at since the
 *       start.</li>
 *   <li>One shared single-thread executor for the catch-ups of all runs, so however many runs are
 *       opened, reading them costs one thread and one database connection at a time.</li>
 *   <li>{@link #close()} tells the catch-ups to stop, shuts the executor down and waits for a
 *       running page to end, which the paged reads keep short; it has to run before the database
 *       pool closes. The catch-up thread is never interrupted: an interrupt inside the database's
 *       file I/O would close the file channel for every connection.</li>
 * </ul>
 * <p>
 * <strong>Thread safety:</strong> thread-safe; {@link #forRun(String)} may be called from any
 * number of request threads.
 */
public final class AncestryIndexes implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AncestryIndexes.class);

    /**
     * How long {@link #close()} waits for a running catch-up to reach the end of its page. A page
     * takes a fraction of a second, so close normally returns at once; the bound only covers a
     * database that blocks.
     */
    static final long CLOSE_TIMEOUT_SECONDS = 5;

    private final IDatabaseReaderProvider provider;
    private final ExecutorService executor;
    private final int pageSize;
    private final LongSupplier clock;
    private final int attributionArrays;
    private final ConcurrentHashMap<String, DescentQuery> runs = new ConcurrentHashMap<>();
    private volatile boolean closing;

    /**
     * Creates the indexes with their own catch-up thread, which starts with the first catch-up.
     *
     * @param provider          Source of the readers the catch-ups take
     * @param attributionArrays Roots per run whose attribution array is kept for incremental
     *                          counting of the line sizes (must be &gt;= 0)
     * @throws IllegalArgumentException if {@code attributionArrays} is negative
     */
    public AncestryIndexes(final IDatabaseReaderProvider provider, final int attributionArrays) {
        this(provider, Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "ancestry-index");
            thread.setDaemon(true);
            return thread;
        }), AncestryIndex.PAGE_SIZE, System::nanoTime, attributionArrays);
    }

    /**
     * Creates the indexes on a given executor and page size.
     *
     * @param provider Source of the readers the catch-ups take
     * @param executor Executor the catch-ups of all runs run on; must run one task at a time
     * @param pageSize Rows per read (must be &gt; 0)
     * @param clock    Monotonic clock in nanoseconds for the gap re-read cooldown
     * @param attributionArrays Roots per run whose attribution array is kept (must be &gt;= 0)
     * @throws IllegalArgumentException if {@code attributionArrays} is negative
     */
    AncestryIndexes(final IDatabaseReaderProvider provider, final ExecutorService executor, final int pageSize,
                    final LongSupplier clock, final int attributionArrays) {
        if (attributionArrays < 0) {
            throw new IllegalArgumentException("attributionArrays must not be negative: " + attributionArrays);
        }
        this.provider = provider;
        this.executor = executor;
        this.pageSize = pageSize;
        this.clock = clock;
        this.attributionArrays = attributionArrays;
    }

    /**
     * The queries on the index of one run, creating the index on first use. Creating it reads
     * nothing; the first request's view asks for the first catch-up.
     *
     * @param runId Run to answer for
     * @return The run's queries
     */
    public DescentQuery forRun(final String runId) {
        return runs.computeIfAbsent(runId,
            id -> new DescentQuery(new AncestryIndex(id, provider, executor, pageSize, clock, () -> closing),
                attributionArrays));
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
