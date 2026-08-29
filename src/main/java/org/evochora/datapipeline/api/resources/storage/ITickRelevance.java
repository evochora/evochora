package org.evochora.datapipeline.api.resources.storage;

/**
 * Which parts of a recorded tick a reader consumes.
 * <p>
 * A chunk holds every recorded tick, but a reader rarely needs all of them: an analytics plugin
 * sampling every tenth recording leaves nine out of ten untouched. Answering these two questions
 * while a chunk is being read lets the parser skip those payloads at the wire level, so they never
 * become Java objects.
 * <p>
 * <strong>The answers must depend on the tick number alone.</strong> Chunks arrive in arbitrary
 * order and are distributed across competing consumers, so an answer derived from a counter or
 * from a previously seen chunk would differ between instances and between runs.
 * <p>
 * <strong>Only intent, no format knowledge.</strong> A reader states which ticks it looks at.
 * That the environment at one tick is reconstructed from a snapshot and a chain of deltas - and
 * that those deltas therefore have to be materialized too - is derived by whoever reads the chunk,
 * not by whoever answers here.
 */
public interface ITickRelevance {

    /**
     * Whether the organism list of this tick is read.
     *
     * @param tickNumber the recorded tick
     * @return {@code true} if the organisms must be materialized
     */
    boolean readsOrganismsAt(long tickNumber);

    /**
     * Whether the environment at this tick is read.
     * <p>
     * This states what the reader looks at, not which stored cells that requires. Turning it into
     * a set of deltas is the job of whoever knows how the environment is reconstructed.
     *
     * @param tickNumber the recorded tick
     * @return {@code true} if the reader reads the environment at this tick
     */
    boolean readsCellsAt(long tickNumber);

    /** Everything is relevant: the behaviour of every reader that does not narrow it down. */
    ITickRelevance EVERYTHING = new ITickRelevance() {
        @Override
        public boolean readsOrganismsAt(long tickNumber) {
            return true;
        }

        @Override
        public boolean readsCellsAt(long tickNumber) {
            return true;
        }

        @Override
        public String toString() {
            return "ITickRelevance.EVERYTHING";
        }
    };
}
