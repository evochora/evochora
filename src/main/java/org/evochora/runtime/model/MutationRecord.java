package org.evochora.runtime.model;

import java.util.Arrays;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * What one mutation plugin did to a newborn organism, as the plugin held it at the moment of the
 * write.
 * <p>
 * A plugin knows exactly which cells it touched, what stood there before and what stands there
 * now. Nothing downstream can reconstruct that from the result: a body diff against the parent
 * produces heuristics, and it cannot tell a plugin's write from any other change of the body. The
 * record carries the facts instead.
 * <p>
 * <strong>Absolute flat indices.</strong> {@link #cells()} holds the row-major flat index of every
 * touched cell, exactly as the plugin held it, without any conversion. Consumers that want a
 * position relative to the organism convert the index themselves, with the world shape they read
 * from the run's metadata.
 * <p>
 * <strong>Write order.</strong> The cells are listed in the order the plugin wrote them, which for
 * the plugins that walk a scan line is their walk along {@link #dv()}. The order is therefore part
 * of the observation and is never sorted away.
 * <p>
 * <strong>Kinds belong to the plugins.</strong> {@link #pluginClass()} names the plugin that
 * reported the record and {@link #kind()} is a short name the plugin chooses. Nothing in the
 * runtime interprets either of them; consumers group by them. A plugin is responsible for choosing
 * kind names that no other plugin uses, so that a grouping by kind stays a grouping by what
 * happened.
 * <p>
 * <strong>Thread Safety:</strong> Instances are immutable and safe to share. {@link Builder} is
 * not thread-safe and belongs to the plugin that owns it.
 */
public final class MutationRecord {

    private final String pluginClass;
    private final String kind;
    private final int[] cells;
    private final int[] oldValues;
    private final int[] newValues;
    private final long[] params;
    private final int[] dv;

    /**
     * Creates a record from the values a plugin collected. Every array is copied, so the caller
     * may keep writing into its own buffers.
     *
     * @param pluginClass fully qualified class name of the reporting plugin, never null
     * @param kind the plugin's short name for what it did, never null
     * @param cells absolute flat indices of the touched cells, in write order, never null
     * @param oldValues the packed molecule at each cell before the write, parallel to
     *                  {@code cells}, never null
     * @param newValues the packed molecule at each cell after the write, parallel to
     *                  {@code cells}, never null
     * @param params numbers whose meaning the reporting plugin documents, never null
     * @param dv the newborn's direction vector while the plugin ran, never null
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the three per-cell arrays differ in length
     */
    public MutationRecord(String pluginClass, String kind, int[] cells, int[] oldValues,
                          int[] newValues, long[] params, int[] dv) {
        if (cells.length != oldValues.length || cells.length != newValues.length) {
            throw new IllegalArgumentException("cells, oldValues and newValues describe the same "
                + "cells and must have the same length, but have " + cells.length + ", "
                + oldValues.length + " and " + newValues.length);
        }
        this.pluginClass = java.util.Objects.requireNonNull(pluginClass, "pluginClass");
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
        this.cells = cells.clone();
        this.oldValues = oldValues.clone();
        this.newValues = newValues.clone();
        this.params = params.clone();
        this.dv = dv.clone();
    }

    /**
     * Fully qualified class name of the plugin that reported this record, the same convention the
     * plugin state of a checkpoint uses.
     *
     * @return the reporting plugin's class name
     */
    public String pluginClass() {
        return pluginClass;
    }

    /**
     * The plugin's short name for what it did, for example {@code "substitution"}. The runtime
     * does not interpret it.
     *
     * @return the kind of mutation
     */
    public String kind() {
        return kind;
    }

    /**
     * Absolute flat indices of the cells the plugin wrote or cleared, in write order.
     *
     * @return a copy of the flat indices; empty for a record that changed no cell
     */
    public int[] cells() {
        return cells.clone();
    }

    /**
     * The packed molecule that stood at each cell before the write, parallel to {@link #cells()}.
     *
     * @return a copy of the molecules before the write
     */
    public int[] oldValues() {
        return oldValues.clone();
    }

    /**
     * The packed molecule that stands at each cell after the write, parallel to {@link #cells()}.
     *
     * @return a copy of the molecules after the write
     */
    public int[] newValues() {
        return newValues.clone();
    }

    /**
     * Numbers whose meaning the reporting plugin documents. They keep what the written cells do
     * not carry, such as where a copied block was read from.
     *
     * @return a copy of the parameters; empty when the plugin has nothing to add
     */
    public long[] params() {
        return params.clone();
    }

    /**
     * The newborn's direction vector while the plugin ran. The plugins that copy, delete or insert
     * a block choose their scan line and their walk direction by it, so it is what makes the order
     * of {@link #cells()} readable.
     *
     * @return a copy of the direction vector, one component per world dimension
     */
    public int[] dv() {
        return dv.clone();
    }

    /**
     * Number of cells the record names, without copying the arrays.
     *
     * @return the number of touched cells, 0 for a record that changed no cell
     */
    public int cellCount() {
        return cells.length;
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MutationRecord record)) {
            return false;
        }
        return pluginClass.equals(record.pluginClass)
            && kind.equals(record.kind)
            && Arrays.equals(cells, record.cells)
            && Arrays.equals(oldValues, record.oldValues)
            && Arrays.equals(newValues, record.newValues)
            && Arrays.equals(params, record.params)
            && Arrays.equals(dv, record.dv);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        int result = pluginClass.hashCode();
        result = 31 * result + kind.hashCode();
        result = 31 * result + Arrays.hashCode(cells);
        result = 31 * result + Arrays.hashCode(oldValues);
        result = 31 * result + Arrays.hashCode(newValues);
        result = 31 * result + Arrays.hashCode(params);
        result = 31 * result + Arrays.hashCode(dv);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "MutationRecord[" + kind + " by " + pluginClass + ", " + cells.length + " cells]";
    }

    /**
     * Collects the values of one record while a plugin writes.
     * <p>
     * The lists are primitive, so appending one cell inside a write loop boxes nothing, and a
     * plugin that keeps one builder as a field allocates nothing per birth beyond the record it
     * finally builds. {@link #start(String, String, int[])} clears the lists, so the same builder
     * serves every birth.
     * <p>
     * <strong>Thread Safety:</strong> Not thread-safe. Birth handlers run in the sequential
     * post-execute phase of a tick, one at a time.
     */
    public static final class Builder {

        private final IntArrayList cells = new IntArrayList();
        private final IntArrayList oldValues = new IntArrayList();
        private final IntArrayList newValues = new IntArrayList();
        private final LongArrayList params = new LongArrayList();
        private String pluginClass;
        private String kind;
        private int[] dv;

        /**
         * Begins a new record, discarding whatever a previous one left behind.
         *
         * @param pluginClass fully qualified class name of the reporting plugin, normally
         *                    {@code getClass().getName()}
         * @param kind the plugin's short name for what it does
         * @param dv the newborn's direction vector while the plugin runs; copied by
         *           {@link #build()}, so the caller keeps ownership of the array
         * @return this builder
         */
        public Builder start(String pluginClass, String kind, int[] dv) {
            this.pluginClass = pluginClass;
            this.kind = kind;
            this.dv = dv;
            cells.clear();
            oldValues.clear();
            newValues.clear();
            params.clear();
            return this;
        }

        /**
         * Appends one written cell. Call it in the order the cells are written.
         *
         * @param flatIndex the cell's absolute flat index
         * @param oldValue the packed molecule that stood there before the write
         * @param newValue the packed molecule that stands there after the write
         * @return this builder
         */
        public Builder cell(int flatIndex, int oldValue, int newValue) {
            cells.add(flatIndex);
            oldValues.add(oldValue);
            newValues.add(newValue);
            return this;
        }

        /**
         * Appends one parameter, in the order the reporting plugin documents.
         *
         * @param value the parameter
         * @return this builder
         */
        public Builder param(long value) {
            params.add(value);
            return this;
        }

        /**
         * Builds the record from what has been collected since {@link #start(String, String, int[])}.
         *
         * @return the record
         * @throws IllegalStateException if the builder was not started
         */
        public MutationRecord build() {
            if (pluginClass == null) {
                throw new IllegalStateException("start(pluginClass, kind, dv) must be called before build()");
            }
            return new MutationRecord(pluginClass, kind, cells.toIntArray(), oldValues.toIntArray(),
                newValues.toIntArray(), params.toLongArray(), dv);
        }
    }
}
