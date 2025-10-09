package org.evochora.datapipeline.resources.database;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.*;
import org.evochora.datapipeline.api.resources.database.IMetadataDatabase;
import org.evochora.datapipeline.resources.AbstractResource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for database resources.
 */
public abstract class AbstractDatabaseResource extends AbstractResource
        implements IMetadataDatabase, IContextualResource, IMonitorable {

    protected final AtomicLong queriesExecuted = new AtomicLong(0);
    protected final AtomicLong rowsInserted = new AtomicLong(0);
    protected final AtomicLong writeErrors = new AtomicLong(0);
    protected final AtomicLong readErrors = new AtomicLong(0);
    protected final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
    private static final int MAX_ERRORS = 100;

    protected AbstractDatabaseResource(String name, Config options) {
        super(name, options);
    }

    @Override
    public IWrappedResource getWrappedResource(ResourceContext context) {
        String usageType = context.usageType();
        return switch (usageType) {
            case "database-metadata" -> new MetadataDatabaseWrapper(this, context);
            default -> throw new IllegalArgumentException(
                    "Unknown database usage type: " + usageType + ". Supported: database-metadata");
        };
    }

    protected abstract Object acquireDedicatedConnection() throws Exception;

    protected abstract String toSchemaName(String simulationRunId);

    protected abstract void doInsertMetadata(Object connection, SimulationMetadata metadata) throws Exception;

    protected abstract void doSetSchema(Object connection, String schemaName) throws Exception;

    protected abstract void doCreateSchema(Object connection, String schemaName) throws Exception;

    @Override
    public void setSimulationRun(String simulationRunId) {
        throw new UnsupportedOperationException("This operation must be called on a wrapped resource.");
    }

    @Override
    public void createSimulationRun(String simulationRunId) {
        throw new UnsupportedOperationException("This operation must be called on a wrapped resource.");
    }

    @Override
    public void insertMetadata(SimulationMetadata metadata) {
        throw new UnsupportedOperationException("This operation must be called on a wrapped resource.");
    }

    protected void recordError(String code, String message, String details) {
        errors.add(new OperationalError(Instant.now(), code, message, details));
        while (errors.size() > MAX_ERRORS) {
            errors.pollFirst();
        }
    }

    @Override
    public boolean isHealthy() {
        // A resource is healthy if it has no errors.
        // Concrete implementations can add more specific checks.
        return errors.isEmpty();
    }

    @Override
    public List<OperationalError> getErrors() {
        return new ArrayList<>(errors);
    }

    @Override
    public void clearErrors() {
        errors.clear();
    }

    @Override
    public final Map<String, Number> getMetrics() {
        Map<String, Number> metrics = getBaseMetrics();
        addCustomMetrics(metrics);
        return metrics;
    }

    protected final Map<String, Number> getBaseMetrics() {
        Map<String, Number> metrics = new LinkedHashMap<>();
        metrics.put("queries_executed", queriesExecuted.get());
        metrics.put("rows_inserted", rowsInserted.get());
        metrics.put("write_errors", writeErrors.get());
        metrics.put("read_errors", readErrors.get());
        metrics.put("error_count", errors.size());
        return metrics;
    }

    protected void addCustomMetrics(Map<String, Number> metrics) {
        // Default implementation does nothing.
    }

    @Override
    public UsageState getUsageState(String usageType) {
        return isHealthy() ? UsageState.ACTIVE : UsageState.FAILED;
    }
}