package org.evochora.datapipeline.api.analytics;

/**
 * How a column's values are carried from the recordings of a run into a coarser level of detail.
 * <p>
 * A level of detail covers a window of recordings - {@code lodFactor^level} of them - and writes
 * one row for it. What that row has to hold depends on what the column measures, and this is where
 * a plugin says which of the two it is.
 * <p>
 * <strong>How a level's rows are read.</strong> A window that reaches the end of a batch leaves
 * its rows there and the next batch opens a new window, so a window can carry more than one row.
 * A reader therefore adds up the rows falling into a range instead of picking one of them, which
 * is what a bucketing query over the loaded rows does anyway.
 */
public enum Aggregation {

    /**
     * The value of the recording the level's row stands on, the other recordings of the window
     * left out.
     * <p>
     * Right for everything that is a state or a rate at a point in time - a population count, an
     * average energy, a percentile - where the value of one recording stands for its
     * neighbourhood, and adding the recordings of a window would produce a number nothing
     * measures.
     */
    SAMPLE,

    /**
     * The values of every recording of the window added up.
     * <p>
     * Right for counts of events - births, mutations, deaths - which happen between two
     * recordings rather than at one. Sampling such a column keeps one recording's events and
     * drops the rest of the window, so the coarser level would show a fraction of what happened;
     * summing keeps every event at every level.
     * <p>
     * A column declared this way must be {@link ColumnType#INTEGER} or {@link ColumnType#BIGINT},
     * and its type has to hold the sums of a whole window, not only one recording's count.
     */
    SUM
}
