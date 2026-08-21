package org.evochora.runtime;

/**
 * Signals that code running inside the parallel wave of a tick used a facility reserved for the
 * sequential parts of a tick — for example, drew from the simulation's root random provider.
 * <p>
 * Such a violation makes the run depend on thread scheduling and therefore irreproducible, so it
 * is never downgraded to a warning: the tick loop lets it propagate even where it otherwise logs
 * and tolerates plugin failures.
 */
public final class ParallelWaveViolation extends IllegalStateException {

    /**
     * @param message describes the facility that was used and what to use instead
     */
    public ParallelWaveViolation(String message) {
        super(message);
    }
}
