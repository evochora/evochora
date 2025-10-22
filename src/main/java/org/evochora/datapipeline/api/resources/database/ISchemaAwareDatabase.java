package org.evochora.datapipeline.api.resources.database;

/**
 * Base capability for database operations that work within a simulation run schema.
 * <p>
 * All database capabilities that operate on run-specific data extend this interface.
 * The schema is set once per wrapper instance by {@link org.evochora.datapipeline.services.indexers.AbstractIndexer}
 * after run discovery.
 * <p>
 * <strong>Pure Capability Interface:</strong> This interface defines only the schema-setting
 * capability, without any resource management concerns (IResource, IMonitorable). Implementations
 * that ARE resources (like wrappers) will get those concerns from their base classes.
 * <p>
 * <strong>Lifecycle:</strong>
 * <ol>
 *   <li>AbstractIndexer discovers runId</li>
 *   <li>AbstractIndexer calls setSimulationRun(runId) on ALL ISchemaAwareDatabase resources</li>
 *   <li>All subsequent operations on these wrappers work within the set schema</li>
 * </ol>
 * <p>
 * <strong>Schema Isolation:</strong>
 * Each simulation run has its own database schema (e.g., SIM_20251006_UUID).
 * This provides complete data isolation between runs and eliminates the need for
 * run_id columns in tables.
 */
public interface ISchemaAwareDatabase {
    /**
     * Sets the active database schema for this wrapper's connection.
     * <p>
     * Must be called once before any schema-specific operations.
     * AbstractIndexer calls this automatically for all database resources after run discovery.
     * <p>
     * Implementation typically executes: {@code SET SCHEMA schema_name}
     *
     * @param simulationRunId Raw simulation run ID (sanitized internally to schema name)
     */
    void setSimulationRun(String simulationRunId);
}

