package org.evochora.datapipeline.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.*;
import org.evochora.datapipeline.api.resources.database.IMetadataDatabase;
import org.evochora.datapipeline.utils.monitoring.LatencyBucket;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

public class MetadataDatabaseWrapper implements IMetadataDatabase, IWrappedResource, IMonitorable {
    private final AbstractDatabaseResource database;
    private final ResourceContext context;
    private final Object dedicatedConnection;
    private String currentSimulationRunId;
    private final AtomicLong schemasCreated = new AtomicLong(0);
    private final AtomicLong metadataInserts = new AtomicLong(0);
    private final AtomicLong operationErrors = new AtomicLong(0);
    private final Map<String, LatencyBucket> operationLatencies = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
    private static final int MAX_ERRORS = 100;

    MetadataDatabaseWrapper(AbstractDatabaseResource db, ResourceContext context) {
        this.database = db;
        this.context = context;
        try {
            this.dedicatedConnection = db.acquireDedicatedConnection();
        } catch (Exception e) {
            throw new RuntimeException("Failed to acquire database connection", e);
        }
        operationLatencies.put("create_simulation_run", new LatencyBucket());
        operationLatencies.put("set_simulation_run", new LatencyBucket());
        operationLatencies.put("insert_metadata", new LatencyBucket());
    }

    @Override
    public void setSimulationRun(String simulationRunId) {
        long startNanos = System.nanoTime();
        try {
            String schemaName = database.toSchemaName(simulationRunId);
            database.doSetSchema(dedicatedConnection, schemaName);
            this.currentSimulationRunId = simulationRunId;
            operationLatencies.get("set_simulation_run").record(System.nanoTime() - startNanos);
        } catch (Exception e) {
            handleException("SET_SCHEMA_FAILED", "Failed to set simulation run", simulationRunId, e);
        }
    }

    @Override
    public void createSimulationRun(String simulationRunId) {
        long startNanos = System.nanoTime();
        try {
            String schemaName = database.toSchemaName(simulationRunId);
            database.doCreateSchema(dedicatedConnection, schemaName);
            schemasCreated.incrementAndGet();
            operationLatencies.get("create_simulation_run").record(System.nanoTime() - startNanos);
        } catch (Exception e) {
            handleException("CREATE_SCHEMA_FAILED", "Failed to create simulation run", simulationRunId, e);
        }
    }

    @Override
    public void insertMetadata(SimulationMetadata metadata) {
        long startNanos = System.nanoTime();
        try {
            database.doInsertMetadata(dedicatedConnection, metadata);
            metadataInserts.incrementAndGet();
            operationLatencies.get("insert_metadata").record(System.nanoTime() - startNanos);
        } catch (Exception e) {
            handleException("INSERT_METADATA_FAILED", "Failed to insert metadata", metadata.getSimulationRunId(), e);
        }
    }

    private void handleException(String code, String message, String runId, Exception e) {
        operationErrors.incrementAndGet();
        String details = "RunId: " + runId + ", Error: " + e.getMessage();
        recordError(code, message, details);
        throw new RuntimeException(message + ": " + runId, e);
    }

    private void recordError(String code, String message, String details) {
        errors.add(new OperationalError(Instant.now(), code, message, details));
        if (errors.size() > MAX_ERRORS) {
            errors.pollFirst();
        }
    }

    @Override
    public String getResourceName() {
        return database.getResourceName();
    }

    public Map<String, Number> getUsageStats() {
        return Map.of(
                "schemas_created", schemasCreated.get(),
                "metadata_inserts", metadataInserts.get(),
                "operation_errors", operationErrors.get()
        );
    }

    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new LinkedHashMap<>();
        metrics.put("schemas_created", schemasCreated.get());
        metrics.put("metadata_inserts", metadataInserts.get());
        metrics.put("operation_errors", operationErrors.get());
        metrics.put("error_count", errors.size());
        metrics.put("current_simulation_run_set", currentSimulationRunId != null ? 1 : 0);
        for (Map.Entry<String, LatencyBucket> entry : operationLatencies.entrySet()) {
            String op = entry.getKey();
            LatencyBucket bucket = entry.getValue();
            metrics.put(op + "_latency_p50", bucket.getPercentile(50) / 1_000_000.0);
            metrics.put(op + "_latency_p95", bucket.getPercentile(95) / 1_000_000.0);
            metrics.put(op + "_latency_p99", bucket.getPercentile(99) / 1_000_000.0);
        }
        return metrics;
    }

    @Override
    public boolean isHealthy() {
        return database.isHealthy();
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
    public IResource.UsageState getUsageState(String usageType) {
        return database.getUsageState(usageType);
    }
}