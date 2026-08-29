package org.evochora.datapipeline.services.indexers;

import java.util.List;

import org.evochora.datapipeline.api.resources.storage.ITickRelevance;

/**
 * States which recorded ticks the analytics plugins of a run look at.
 * <p>
 * A plugin writes a row whenever the tick number is a multiple of its interval, so the answer is a
 * divisibility test against the intervals of the configured plugins - those reading organisms for
 * one question, those reading the environment for the other.
 * <p>
 * What that means for the stored chunks - which deltas an environment reconstruction has to walk
 * through, and which of them may therefore be left unread - is not decided here. That follows from
 * how the environment is reconstructed, and is derived where the chunk is read.
 */
final class AnalyticsTickRelevance implements ITickRelevance {

    private final long[] organismIntervals;
    private final long[] cellIntervals;

    /**
     * @param organismIntervals absolute tick intervals of the plugins reading organisms
     * @param cellIntervals     absolute tick intervals of the plugins reading the environment
     */
    AnalyticsTickRelevance(List<Long> organismIntervals, List<Long> cellIntervals) {
        this.organismIntervals = organismIntervals.stream().mapToLong(Long::longValue).toArray();
        this.cellIntervals = cellIntervals.stream().mapToLong(Long::longValue).toArray();
    }

    @Override
    public boolean readsOrganismsAt(long tickNumber) {
        return anyInterval(organismIntervals, tickNumber);
    }

    @Override
    public boolean readsCellsAt(long tickNumber) {
        return anyInterval(cellIntervals, tickNumber);
    }

    private static boolean anyInterval(long[] intervals, long tickNumber) {
        for (long interval : intervals) {
            if (tickNumber % interval == 0) {
                return true;
            }
        }
        return false;
    }
}
