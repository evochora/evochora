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
 *   <li>Main thread spins for a bounded budget and then parks until a worker wakes it</li>
 * </ol>
 * <p>
 * No thread holds a core for long while it waits: workers park between dispatches, and the main
 * thread spins only for a bounded budget before it parks as well. Per dispatch this costs one
 * volatile write, one unpark per active worker and, for waits that outlast the budget, one
 * park/unpark pair for the main thread — against the ~20-25µs a ForkJoinPool spends on task
 * allocation, deque handling and join synchronization.
 * <p>
 * <b>Thread safety:</b> {@link #dispatch(int, ChunkTask)} must not be called concurrently; the
 * thread driving a dispatch is the one the workers wake at its end, and it need not be the thread
 * that created the pool. {@link #shutdown()} is idempotent and safe to call from any thread.
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
    /**
     * The thread that is driving the current dispatch and that the workers wake when they are done.
     * Written before the phase counter is incremented, so every worker that reads the new phase
     * reads this thread as well. It is not the thread that created the pool: a service typically
     * builds its simulation in one thread and ticks it in another.
     */
    private Thread dispatchThread;

    /**
     * Spin iterations the dispatching thread runs before it looks at the clock for the first time.
     * A wave whose workers finish within this window costs no time measurement at all, which is
     * what makes the barrier cheap on systems where reading the clock is a system call.
     */
    private static final int SPINS_BEFORE_CLOCK = 64;

    /** Spin iterations between two clock readings once the budget is being watched. */
    private static final int SPINS_PER_CLOCK_CHECK = 64;

    /**
     * How long the dispatching thread may keep a core busy waiting before it parks. This is a
     * budget, not a calibration: it caps what a wave may burn while it waits, whatever the
     * hardware. Waits shorter than this stay in the spin, where they are cheaper than a park;
     * longer ones are the waits that matter for the rest of the machine, and they go to sleep.
     */
    private static final long SPIN_BUDGET_NANOS = 10_000;

    /**
     * Set by the dispatching thread before it checks the completion count and cleared when it
     * leaves the barrier. Together with the count it forms the handshake that decides who does the
     * waking: both sides write their own flag before reading the other's, so a worker that misses
     * the flag has already been seen by the dispatching thread, which then does not park.
     */
    private volatile boolean mainParked;

    private volatile int phase;
    private volatile int workSize;
    private volatile int activeThreadCount;
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
     * Equivalent to {@code dispatch(totalSize, totalThreads, task)}.
     *
     * @param totalSize the total number of work items (must be &gt;= 0)
     * @param task      the task to execute on each chunk
     * @throws RuntimeException wrapping any exception thrown by a worker thread
     */
    public void dispatch(int totalSize, ChunkTask task) {
        dispatch(totalSize, totalThreads, task);
    }

    /**
     * Dispatches work across a subset of threads and blocks until completion.
     * <p>
     * The work range [0, {@code totalSize}) is divided into {@code activeThreads}
     * roughly equal chunks. The main thread processes chunk 0 while worker
     * threads 1 through {@code activeThreads - 1} process the remaining chunks.
     * Workers with index &gt;= {@code activeThreads} stay parked.
     * <p>
     * If any thread (including the main thread) throws an exception, it is
     * propagated to the caller after all other threads have finished their
     * current chunk. The first exception wins; subsequent exceptions are suppressed.
     * <p>
     * The pool exists for the parallel wave of a tick and for nothing else: every task runs
     * with {@link ParallelWave#isActive()} set, on the main thread for the duration of its
     * chunk and on worker threads permanently.
     * <p>
     * Drives the dispatch from the calling thread, which is also the thread the workers wake at
     * its end. Not reentrant and never to be called by two threads at once.
     *
     * @param totalSize     the total number of work items (must be &gt;= 0)
     * @param activeThreads how many threads to use (1 = main only, up to {@code totalThreads}).
     *                      Clamped to [1, totalThreads].
     * @param task          the task to execute on each chunk
     * @throws RuntimeException wrapping any exception thrown by a worker thread
     */
    public void dispatch(int totalSize, int activeThreads, ChunkTask task) {
        if (totalSize <= 0) return;
        if (stopped) throw new IllegalStateException("Cannot dispatch after shutdown");


        int active = Math.max(1, Math.min(activeThreads, totalThreads));
        int activeWorkers = active - 1;

        this.workSize = totalSize;
        this.activeThreadCount = active;
        this.task = task;
        workerException.set(null);
        workersCompleted.set(0);
        dispatchThread = Thread.currentThread();

        // Volatile write — happens-before for all workers reading phase and dispatchThread
        phase++;

        // Unpark only active workers
        for (int i = 0; i < activeWorkers; i++) {
            LockSupport.unpark(workers[i]);
        }

        // Main thread executes chunk 0
        THREAD_INDEX.set(0);
        Throwable mainException = null;
        ParallelWave.enter();
        try {
            int chunkSize = (totalSize + active - 1) / active;
            int to = Math.min(chunkSize, totalSize);
            task.run(0, to);
        } catch (Throwable t) {
            mainException = t;
        } finally {
            ParallelWave.leave();
        }

        awaitWorkers(activeWorkers);

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
     * Waits until all active workers have reported completion.
     * <p>
     * A short spin covers the waits that are over before a park would have paid for itself. Once
     * the spin budget is spent, the thread parks and the worker that fills the completion count
     * wakes it, so a long wait leaves its core to the rest of the machine instead of holding it.
     * <p>
     * The park flag goes up before the count is read, so a worker finishing in between still sees
     * it and unparks; a stale permit from such a race only costs one extra loop iteration in a
     * later dispatch. An interrupt on the dispatching thread makes park return immediately, so the
     * loop then busy-waits for the short moment until the workers see the interrupt themselves and
     * leave their chunks.
     *
     * @param activeWorkers the number of workers expected to report completion
     */
    private void awaitWorkers(int activeWorkers) {
        for (int i = 0; i < SPINS_BEFORE_CLOCK; i++) {
            if (workersCompleted.get() >= activeWorkers) {
                return;
            }
            Thread.onSpinWait();
        }

        long deadline = System.nanoTime() + SPIN_BUDGET_NANOS;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < SPINS_PER_CLOCK_CHECK; i++) {
                if (workersCompleted.get() >= activeWorkers) {
                    return;
                }
                Thread.onSpinWait();
            }
        }

        mainParked = true;
        try {
            while (workersCompleted.get() < activeWorkers) {
                LockSupport.park();
            }
        } finally {
            mainParked = false;
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
        ParallelWave.enter(); // a worker only ever executes the parallel wave
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

            // Skip if not active in this dispatch (spurious unpark from OS)
            int active = activeThreadCount;
            if (workerIndex >= active) {
                continue;
            }

            try {
                int chunkSize = (workSize + active - 1) / active;
                int from = workerIndex * chunkSize;
                int to = Math.min(from + chunkSize, workSize);
                if (from < workSize) {
                    task.run(from, to);
                }
            } catch (Throwable t) {
                workerException.compareAndSet(null, t);
            } finally {
                if (workersCompleted.incrementAndGet() == active - 1 && mainParked) {
                    LockSupport.unpark(dispatchThread);
                }
            }
        }
    }
}
