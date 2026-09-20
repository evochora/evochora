package org.evochora.datapipeline.services.analytics.plugins;

import java.util.List;

/**
 * The one definition of a percentile the analytics plugins of this package report.
 * <p>
 * A percentile answers the same question wherever it appears in a run - the age distribution and
 * the spread of energy and entropy read as one picture only if p50 means the same thing in each of
 * them - so every plugin that reports one takes it from here.
 */
final class Percentiles {

    private Percentiles() {
    }

    /**
     * Returns the P-th percentile of a sorted list of values, by the nearest-rank method:
     * the value at index {@code round((n - 1) * P / 100)}.
     *
     * @param sortedValues the values in ascending order; an empty list yields 0
     * @param percentile   the percentile to read, clamped to the smallest value at or below 0
     *                     and to the largest at or above 100
     * @return the value at that percentile
     */
    static int of(List<Integer> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) return 0;

        if (percentile <= 0) return sortedValues.get(0);
        if (percentile >= 100) return sortedValues.get(sortedValues.size() - 1);

        int index = (int) Math.round((sortedValues.size() - 1) * (percentile / 100.0));
        return sortedValues.get(index);
    }
}
