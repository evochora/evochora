package org.evochora.datapipeline.api.analytics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Specification for query-time transformations on analytics data.
 * <p>
 * Plugins define what data to store (raw columns) and how to compute derived columns
 * at query time. This enables stateless plugins while still supporting complex calculations
 * like deltas, cumulative sums, and window functions.
 * <p>
 * <strong>Why Query-Time Transforms?</strong>
 * <ul>
 *   <li>Plugins remain stateless → competing consumers work correctly</li>
 *   <li>Raw data is always correct and complete</li>
 *   <li>Derived values are computed when all data is available</li>
 *   <li>Same transform logic works server-side (DuckDB) and client-side (DuckDB WASM)</li>
 * </ul>
 * <p>
 * <strong>Example:</strong>
 * <pre>{@code
 * QuerySpec spec = QuerySpec.builder()
 *     .baseColumn("tick")
 *     .baseColumn("total_born")
 *     .baseColumn("alive_count")
 *     .computedColumn("births", ComputedColumn.delta("total_born"))
 *     .computedColumn("deaths", ComputedColumn.expression(
 *         "(LAG(alive_count) OVER (ORDER BY tick) + births) - alive_count"))
 *     .outputColumns("tick", "births", "deaths")
 *     .build();
 * }</pre>
 */
public class QuerySpec {

    private final List<String> baseColumns;
    private final List<ComputedColumn> computedColumns;
    private final List<String> outputColumns;
    private final String orderBy;

    private QuerySpec(Builder builder) {
        this.baseColumns = Collections.unmodifiableList(new ArrayList<>(builder.baseColumns));
        this.computedColumns = Collections.unmodifiableList(new ArrayList<>(builder.computedColumns));
        this.outputColumns = builder.outputColumns.isEmpty() 
            ? Collections.unmodifiableList(new ArrayList<>(builder.baseColumns))
            : Collections.unmodifiableList(new ArrayList<>(builder.outputColumns));
        this.orderBy = builder.orderBy;
    }

    /**
     * Returns the base columns stored in Parquet.
     *
     * @return List of base column names
     */
    public List<String> getBaseColumns() {
        return baseColumns;
    }

    /**
     * Returns the computed columns to be calculated at query time.
     *
     * @return List of computed column definitions
     */
    public List<ComputedColumn> getComputedColumns() {
        return computedColumns;
    }

    /**
     * Returns the columns to include in the output.
     * <p>
     * Can include both base columns and computed columns.
     * Defaults to base columns if not explicitly set.
     *
     * @return List of output column names
     */
    public List<String> getOutputColumns() {
        return outputColumns;
    }

    /**
     * Returns the ORDER BY clause for the query.
     *
     * @return Column name to order by, or null for no ordering
     */
    public String getOrderBy() {
        return orderBy;
    }

    /**
     * Creates a passthrough QuerySpec that returns raw data without transformations.
     *
     * @param schema The schema to create passthrough spec for
     * @return QuerySpec that passes through all columns unchanged
     */
    public static QuerySpec passthrough(ParquetSchema schema) {
        Builder builder = builder();
        for (ParquetSchema.Column col : schema.getColumns()) {
            builder.baseColumn(col.name());
        }
        return builder.build();
    }

    /**
     * Creates a new QuerySpec builder.
     *
     * @return New builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for QuerySpec.
     */
    public static class Builder {
        private final List<String> baseColumns = new ArrayList<>();
        private final List<ComputedColumn> computedColumns = new ArrayList<>();
        private final List<String> outputColumns = new ArrayList<>();
        private String orderBy = "tick";

        /**
         * Adds a base column (stored in Parquet).
         *
         * @param name Column name
         * @return This builder
         */
        public Builder baseColumn(String name) {
            baseColumns.add(name);
            return this;
        }

        /**
         * Adds multiple base columns.
         *
         * @param names Column names
         * @return This builder
         */
        public Builder baseColumns(String... names) {
            for (String name : names) {
                baseColumns.add(name);
            }
            return this;
        }

        /**
         * Adds a computed column (calculated at query time).
         *
         * @param name Column name for the computed result
         * @param column Computed column definition
         * @return This builder
         */
        public Builder computedColumn(String name, ComputedColumn column) {
            computedColumns.add(column.withName(name));
            return this;
        }

        /**
         * Sets the output columns (what the query returns).
         * <p>
         * Can include both base and computed columns.
         * If not set, defaults to base columns only.
         *
         * @param names Column names to output
         * @return This builder
         */
        public Builder outputColumns(String... names) {
            for (String name : names) {
                outputColumns.add(name);
            }
            return this;
        }

        /**
         * Sets the column to order results by.
         * <p>
         * Default is "tick".
         *
         * @param column Column name to order by
         * @return This builder
         */
        public Builder orderBy(String column) {
            this.orderBy = column;
            return this;
        }

        /**
         * Builds the QuerySpec.
         *
         * @return Immutable QuerySpec instance
         */
        public QuerySpec build() {
            return new QuerySpec(this);
        }
    }

    /**
     * Represents a computed column calculated at query time.
     * <p>
     * Supports common patterns like deltas (difference from previous row)
     * and arbitrary SQL expressions for window functions.
     */
    public static class ComputedColumn {
        
        /**
         * Type of computation.
         */
        public enum Type {
            /** Difference from previous value: value - LAG(value) */
            DELTA,
            /** Previous row value: LAG(value) */
            LAG,
            /** Arbitrary SQL expression */
            EXPRESSION
        }

        private final String name;
        private final Type type;
        private final String sourceColumn;
        private final String expression;

        private ComputedColumn(String name, Type type, String sourceColumn, String expression) {
            this.name = name;
            this.type = type;
            this.sourceColumn = sourceColumn;
            this.expression = expression;
        }

        /**
         * Creates a delta computation: current - previous.
         * <p>
         * SQL: {@code source - LAG(source, 1, source) OVER (ORDER BY tick)}
         *
         * @param sourceColumn Column to compute delta for
         * @return ComputedColumn for delta
         */
        public static ComputedColumn delta(String sourceColumn) {
            return new ComputedColumn(null, Type.DELTA, sourceColumn, null);
        }

        /**
         * Creates a lag computation: previous row value.
         * <p>
         * SQL: {@code LAG(source) OVER (ORDER BY tick)}
         *
         * @param sourceColumn Column to get previous value for
         * @return ComputedColumn for lag
         */
        public static ComputedColumn lag(String sourceColumn) {
            return new ComputedColumn(null, Type.LAG, sourceColumn, null);
        }

        /**
         * Creates an arbitrary SQL expression.
         * <p>
         * The expression can reference base columns and other computed columns
         * defined before this one.
         *
         * @param sqlExpression SQL expression (e.g., "col1 + col2", window functions)
         * @return ComputedColumn for expression
         */
        public static ComputedColumn expression(String sqlExpression) {
            return new ComputedColumn(null, Type.EXPRESSION, null, sqlExpression);
        }

        /**
         * Returns a copy with the specified name.
         */
        ComputedColumn withName(String name) {
            return new ComputedColumn(name, this.type, this.sourceColumn, this.expression);
        }

        public String getName() {
            return name;
        }

        public Type getType() {
            return type;
        }

        public String getSourceColumn() {
            return sourceColumn;
        }

        public String getExpression() {
            return expression;
        }

        /**
         * Generates the SQL expression for this computed column.
         *
         * @param orderByColumn Column to use in OVER clause
         * @return SQL expression string
         */
        public String toSql(String orderByColumn) {
            return switch (type) {
                case DELTA -> String.format(
                    "COALESCE(%s - LAG(%s, 1, %s) OVER (ORDER BY %s), 0)",
                    sourceColumn, sourceColumn, sourceColumn, orderByColumn);
                case LAG -> String.format(
                    "LAG(%s) OVER (ORDER BY %s)",
                    sourceColumn, orderByColumn);
                case EXPRESSION -> expression;
            };
        }
    }
}

