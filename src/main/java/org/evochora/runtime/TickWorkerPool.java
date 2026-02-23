package org.evochora.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * A lightweight thread pool optimized for repeated barrier-synchronized bulk dispatches.
 * <p>
 * Unlike {@link java.util.concurrent.ForkJoinPool}, this pool keeps {@code P-1} daemon
 * threads permanently alive between dispatches, using {@link LockSupport#park()} for
 * zero-overhead waiting. The main thread participates as worker 0, so the total
 * parallelism is P threads (1 main + P-1 workers).
 * <p>
 * <b>Synchronization protocol:</b>
 * <ol>
 *   <li>Main thread sets work parameters and increments the volatile {@code phase} counter</li>
 *   <li>Main thread unparks all workers and executes its own chunk (index 0)</li>
 *   <li>Workers wake, read the new phase, execute their chunks, and increment {@code workersCompleted}</li>
 *   <li>Main thread spin-waits until all workers have acknowledged completion</li>
 * </ol>
 * <p>
 * Per-dispatch overhead is ~2-5µs (volatile write + unpark + spin-wait), compared to
 * ~20-25µs for ForkJoinPool (task allocation + park/unpark syscalls + join synchronization).
 * <p>
 * <b>Thread safety:</b> {@link #dispatch(int, ChunkTask)} must only be called from the
 * main thread (the thread that created this pool). Multiple concurrent dispatches are
 * not supported. {@link #shutdown()} is idempotent and safe to call from any thread.
 */
public class TickWorkerPool {

    /**
     * A task that processes a contiguous chunk of work items.
     */
    @FunctionalInterface
    public interface ChunkTask {
        /**
         * Processes items in the range [{@code fromInclusive}, {@code toExclusive}).
         *
         * @param fromInclusive start index (inclusive)
         * @param toExclusive   end index (exclusive)
         */
        void run(int fromInclusive, int toExclusive);
    }

    private static final ThreadLocal<Integer> THREAD_INDEX = new ThreadLocal<>();

    private final Thread[] workers;
    private final int totalThreads;

    private volatile int phase;
    private volatile int workSize;
    private volatile ChunkTask task;
    private volatile boolean stopped;
    private final AtomicInteger workersCompleted = new AtomicInteger();
    private final AtomicReference<Throwable> workerException = new AtomicReference<>();

    private final AtomicInteger readyWorkers = new AtomicInteger();

    /**
     * Creates a new TickWorkerPool with the specified parallelism.
     * <p>
     * Spawns {@code parallelism - 1} daemon threads and waits until all have
     * read their initial phase snapshot and are parked. This startup barrier
     * prevents a race where {@link #dispatch(int, ChunkTask)} could increment
     * {@code phase} before a worker has read it, causing the worker to treat
     * the first dispatch as a spurious wakeup.
     *
     * @param parallelism total number of threads (including the main thread).
     *                    Must be &gt;= 2.
     * @throws IllegalArgumentException if parallelism &lt; 2
     */
    public TickWorkerPool(int parallelism) {
        if (parallelism < 2) {
            throw new IllegalArgumentException("Parallelism must be >= 2, got " + parallelism);
        }
        this.totalThreads = parallelism;
        this.workers = new Thread[parallelism - 1];

        for (int i = 0; i < workers.length; i++) {
            int workerIndex = i + 1;
            workers[i] = new Thread(() -> workerLoop(workerIndex), "tick-worker-" + workerIndex);
            workers[i].setDaemon(true);
            workers[i].start();
        }

        // Wait for all workers to have read their initial phase and be ready to park
        while (readyWorkers.get() < workers.length) {
            Thread.onSpinWait();
        }
    }

    /**
     * Returns the thread index of the calling thread within the current dispatch.
     * <p>
     * Index 0 is the main thread, indices 1 through P-1 are worker threads.
     * Only valid during an active {@link #dispatch(int, ChunkTask)} call.
     *
     * @return the thread index (0-based)
     */
    public static int getThreadIndex() {
        return THREAD_INDEX.get();
    }

    /**
     * Dispatches work across all threads and blocks until completion.
     * <p>
     * The work range [0, {@code totalSize}) is divided into {@link #totalThreads}
     * roughly equal chunks. The main thread processes chunk 0 while worker
     * threads process chunks 1 through P-1.
     * <p>
     * If any thread (including the main thread) throws an exception, it is
     * propagated to the caller after all other threads have finished their
     * current chunk. The first exception wins; subsequent exceptions are suppressed.
     * <p>
     * Must be called from the main thread only. Not reentrant.
     *
     * @param totalSize the total number of work items (must be &gt;= 0)
     * @param task      the task to execute on each chunk
     * @throws RuntimeException wrapping any exception thrown by a worker thread
     */
    public void dispatch(int totalSize, ChunkTask task) {
        if (totalSize <= 0) return;

        this.workSize = totalSize;
        this.task = task;
        workerException.set(null);
        workersCompleted.set(0);

        // Volatile write — happens-before for all workers reading phase
        phase++;

        // Unpark all workers
        for (Thread worker : workers) {
            LockSupport.unpark(worker);
        }

        // Main thread executes chunk 0
        THREAD_INDEX.set(0);
        Throwable mainException = null;
        try {
            int chunkSize = (totalSize + totalThreads - 1) / totalThreads;
            int to = Math.min(chunkSize, totalSize);
            task.run(0, to);
        } catch (Throwable t) {
            mainException = t;
        }

        // Wait for all workers to finish
        while (workersCompleted.get() < workers.length) {
            Thread.onSpinWait();
        }

        // Check for exceptions (worker exceptions take precedence if main also failed)
        Throwable workerEx = workerException.get();
        if (workerEx != null) {
            if (mainException != null) {
                workerEx.addSuppressed(mainException);
            }
            if (workerEx instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Worker thread failed", workerEx);
        }
        if (mainException != null) {
            if (mainException instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Main thread failed during dispatch", mainException);
        }
    }

    /**
     * Shuts down the pool, interrupting and joining all worker threads.
     * <p>
     * Idempotent — safe to call multiple times. Blocks until all workers
     * have terminated or the join timeout (5 seconds per thread) expires.
     */
    public void shutdown() {
        stopped = true;
        for (Thread worker : workers) {
            LockSupport.unpark(worker);
        }
        for (Thread worker : workers) {
            try {
                worker.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The main loop executed by each worker thread.
     * <p>
     * Workers park between dispatches and wake when the main thread increments
     * the phase counter and calls unpark. Spurious wakeups are handled by
     * comparing the local phase snapshot with the current phase.
     *
     * @param workerIndex the 1-based index of this worker
     */
    private void workerLoop(int workerIndex) {
        THREAD_INDEX.set(workerIndex);
        int lastPhase = phase;
        readyWorkers.incrementAndGet();

        while (!stopped) {
            LockSupport.park();

            if (stopped) break;

            int currentPhase = phase;
            if (currentPhase == lastPhase) {
                // Spurious wakeup
                continue;
            }
            lastPhase = currentPhase;

            try {
                int chunkSize = (workSize + totalThreads - 1) / totalThreads;
                int from = workerIndex * chunkSize;
                int to = Math.min(from + chunkSize, workSize);
                if (from < workSize) {
                    task.run(from, to);
                }
            } catch (Throwable t) {
                workerException.compareAndSet(null, t);
            }

            workersCompleted.incrementAndGet();
        }
    }
}
