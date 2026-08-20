package org.evochora.runtime;

/**
 * Marks, per thread, whether the calling thread is executing the parallel wave of a tick — the
 * phase in which organisms are planned and their local instructions executed, possibly on several
 * threads at once against a snapshot of the environment.
 * <p>
 * Components that must not be used in that phase (shared randomness, environment mutation) query
 * {@link #isActive()} to fail fast instead of silently making a run depend on thread scheduling.
 * Entering and leaving the wave is the tick loop's business and therefore package-private.
 * <p>
 * Worker threads of the tick pool are inside the wave for their whole lifetime, because they never
 * do anything else; the simulation thread enters the wave only while it processes its own share
 * of the organisms and leaves it afterwards.
 */
public final class ParallelWave {

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private ParallelWave() {
        // Static utility
    }

    /**
     * @return {@code true} while the calling thread is inside the parallel wave
     */
    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /** Marks the calling thread as inside the parallel wave. */
    static void enter() {
        ACTIVE.set(Boolean.TRUE);
    }

    /** Marks the calling thread as outside the parallel wave. */
    static void leave() {
        ACTIVE.set(Boolean.FALSE);
    }
}
