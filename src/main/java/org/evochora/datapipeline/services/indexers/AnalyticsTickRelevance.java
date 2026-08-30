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
     * @throws IllegalArgumentException if an interval is not positive
     */
    AnalyticsTickRelevance(List<Long> organismIntervals, List<Long> cellIntervals) {
        this.organismIntervals = checkedIntervals(organismIntervals, "organisms");
        this.cellIntervals = checkedIntervals(cellIntervals, "the environment");
    }

    /**
     * Converts the intervals, refusing any that cannot be a divisor.
     * <p>
     * A zero would reach the divisibility test as a division by zero, and a negative interval
     * would pass it with a meaning nobody intended - both far from here, in the middle of parsing
     * a chunk, where a configuration mistake is the last thing a reader would suspect.
     *
     * @param intervals the intervals to convert
     * @param readers   what the plugins behind them read, for the message
     * @return the intervals
     * @throws IllegalArgumentException if one of them is not positive
     */
    private static long[] checkedIntervals(List<Long> intervals, String readers) {
        return intervals.stream().mapToLong(interval -> {
            if (interval == null || interval < 1) {
                throw new IllegalArgumentException(
                    "A plugin reading " + readers + " states a sampling interval of " + interval
                    + " ticks. An interval is how many ticks lie between two rows and is at least 1.");
            }
            return interval;
        }).toArray();
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
