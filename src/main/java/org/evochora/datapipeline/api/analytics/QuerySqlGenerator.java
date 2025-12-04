package org.evochora.datapipeline.api.analytics;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates SQL from QuerySpec for DuckDB execution.
 * <p>
 * Converts the declarative QuerySpec into executable SQL that works with both
 * server-side DuckDB and client-side DuckDB WASM.
 * <p>
 * <strong>Generated SQL Structure:</strong>
 * <pre>
 * WITH base AS (
 *     SELECT * FROM {table}
 * ),
 * computed AS (
 *     SELECT
 *         base.*,
 *         computed_col_1 AS name1,
 *         computed_col_2 AS name2
 *     FROM base
 * )
 * SELECT output_col1, output_col2, ...
 * FROM computed
 * ORDER BY tick
 * </pre>
 * <p>
 * The {@code {table}} placeholder is replaced by the actual table/file reference
 * at query execution time.
 */
public class QuerySqlGenerator {

    /** Placeholder for table reference in generated SQL */
    public static final String TABLE_PLACEHOLDER = "{table}";

    /**
     * Generates SQL from a QuerySpec.
     *
     * @param spec The query specification
     * @return SQL string with {table} placeholder
     */
    public static String generateSql(QuerySpec spec) {
        if (spec == null || spec.getComputedColumns().isEmpty()) {
            // No transformations - simple passthrough
            return generatePassthroughSql(spec);
        }
        
        return generateTransformSql(spec);
    }

    /**
     * Generates simple passthrough SQL (no transformations).
     */
    private static String generatePassthroughSql(QuerySpec spec) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        
        if (spec != null && !spec.getOutputColumns().isEmpty()) {
            sql.append(String.join(", ", spec.getOutputColumns()));
        } else {
            sql.append("*");
        }
        
        sql.append("\nFROM ").append(TABLE_PLACEHOLDER);
        
        if (spec != null && spec.getOrderBy() != null) {
            sql.append("\nORDER BY ").append(spec.getOrderBy());
        }
        
        return sql.toString();
    }

    /**
     * Generates SQL with computed columns using CTEs.
     */
    private static String generateTransformSql(QuerySpec spec) {
        StringBuilder sql = new StringBuilder();
        String orderBy = spec.getOrderBy() != null ? spec.getOrderBy() : "tick";
        
        // CTE for base data
        sql.append("WITH base AS (\n");
        sql.append("    SELECT * FROM ").append(TABLE_PLACEHOLDER).append("\n");
        sql.append("),\n");
        
        // CTE for computed columns
        sql.append("computed AS (\n");
        sql.append("    SELECT\n");
        
        // Include base columns
        List<String> selectParts = new ArrayList<>();
        for (String baseCol : spec.getBaseColumns()) {
            selectParts.add("        " + baseCol);
        }
        
        // Add computed columns
        for (QuerySpec.ComputedColumn cc : spec.getComputedColumns()) {
            String sqlExpr = cc.toSql(orderBy);
            selectParts.add("        " + sqlExpr + " AS " + cc.getName());
        }
        
        sql.append(String.join(",\n", selectParts));
        sql.append("\n    FROM base\n");
        sql.append(")\n");
        
        // Final SELECT with output columns
        sql.append("SELECT ");
        if (!spec.getOutputColumns().isEmpty()) {
            sql.append(String.join(", ", spec.getOutputColumns()));
        } else {
            sql.append("*");
        }
        sql.append("\nFROM computed\n");
        sql.append("ORDER BY ").append(orderBy);
        
        return sql.toString();
    }

    /**
     * Replaces the table placeholder with an actual table/file reference.
     *
     * @param sql SQL with {table} placeholder
     * @param tableReference Actual table name or file path
     * @return SQL ready for execution
     */
    public static String withTable(String sql, String tableReference) {
        return sql.replace(TABLE_PLACEHOLDER, tableReference);
    }

    /**
     * Wraps a file path for DuckDB's read_parquet function.
     *
     * @param filePath Path to Parquet file
     * @return read_parquet('path') expression
     */
    public static String readParquet(String filePath) {
        return "read_parquet('" + filePath.replace("'", "''") + "')";
    }

    /**
     * Wraps multiple file paths for DuckDB's read_parquet function.
     *
     * @param filePaths List of Parquet file paths
     * @return read_parquet(['path1', 'path2', ...]) expression
     */
    public static String readParquetMultiple(List<String> filePaths) {
        StringBuilder sb = new StringBuilder("read_parquet([");
        for (int i = 0; i < filePaths.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("'").append(filePaths.get(i).replace("'", "''")).append("'");
        }
        sb.append("])");
        return sb.toString();
    }
}

