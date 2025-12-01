package org.evochora.datapipeline.api.analytics;

/**
 * Supported column types for analytics Parquet schema.
 * <p>
 * Maps to DuckDB SQL types for schema generation.
 */
public enum ColumnType {
    /**
     * 64-bit signed integer. Use for tick numbers, counters, IDs.
     */
    BIGINT("BIGINT"),
    
    /**
     * 32-bit signed integer. Use for counts, small numbers.
     */
    INTEGER("INTEGER"),
    
    /**
     * 64-bit floating point. Use for averages, ratios, percentages.
     */
    DOUBLE("DOUBLE"),
    
    /**
     * Variable-length string. Use for labels, categories.
     */
    VARCHAR("VARCHAR"),
    
    /**
     * Boolean (true/false).
     */
    BOOLEAN("BOOLEAN");
    
    private final String sqlType;
    
    ColumnType(String sqlType) {
        this.sqlType = sqlType;
    }
    
    /**
     * Returns the DuckDB SQL type name.
     *
     * @return SQL type string (e.g., "BIGINT", "DOUBLE")
     */
    public String getSqlType() {
        return sqlType;
    }
}

