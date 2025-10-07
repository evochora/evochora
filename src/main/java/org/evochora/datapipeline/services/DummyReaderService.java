package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.BatchMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.OperationalError;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test service that reads and validates TickData batches from storage using the batch API.
 * Used for integration testing of storage resources.
 */
public class DummyReaderService extends AbstractService implements IMonitorable {
    private final IBatchStorageRead storage;
    private final String keyPrefix;
    private final int intervalMs;
    private final boolean validateData;

    private final AtomicLong totalMessagesRead = new AtomicLong(0);
    private final AtomicLong totalBytesRead = new AtomicLong(0);
    private final AtomicLong readOperations = new AtomicLong(0);
    private final AtomicLong validationErrors = new AtomicLong(0);
    private final AtomicLong readErrors = new AtomicLong(0);
    private final Set<String> processedFiles = ConcurrentHashMap.newKeySet();
    private final List<OperationalError> errors = Collections.synchronizedList(new ArrayList<>());

    // Track expected tick range for validation
    private long minTickSeen = Long.MAX_VALUE;
    private long maxTickSeen = Long.MIN_VALUE;

    public DummyReaderService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.storage = getRequiredResource("storage", IBatchStorageRead.class);
        this.keyPrefix = options.hasPath("keyPrefix") ? options.getString("keyPrefix") : "test";
        this.intervalMs = options.hasPath("intervalMs") ? options.getInt("intervalMs") : 1000;
        this.validateData = options.hasPath("validateData") ? options.getBoolean("validateData") : true;
    }

    @Override
    protected void run() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            checkPause();

            try {
                // Query for all batches in the tick range
                // Start with query from 0 to a large number to get all batches
                List<BatchMetadata> batches = storage.queryBatches(0, Long.MAX_VALUE);

                for (BatchMetadata metadata : batches) {
                    // Skip already processed files
                    if (processedFiles.contains(metadata.filename)) {
                        continue;
                    }

                    // Filter by key prefix (simulation run ID check)
                    // Skip if filename doesn't match our simulation
                    // Note: This is a simple filter; in real scenarios, storage metadata
                    // would include simulationRunId for proper filtering

                    try {
                        List<TickData> ticks = storage.readBatch(metadata.filename);

                        long expectedTick = -1;
                        for (TickData tick : ticks) {
                            // Filter by simulation run ID
                            if (!tick.getSimulationRunId().equals(keyPrefix + "_run")) {
                                continue; // Skip ticks from other simulations
                            }

                            totalMessagesRead.incrementAndGet();
                            totalBytesRead.addAndGet(tick.getSerializedSize());

                            // Validate sequential order within batch (before tracking tick range)
                            if (validateData && expectedTick >= 0) {
                                if (tick.getTickNumber() != expectedTick) {
                                    log.warn("Tick sequence error in {}: expected {}, got {}",
                                        metadata.filename, expectedTick, tick.getTickNumber());
                                    validationErrors.incrementAndGet();
                                }
                            }

                            // Track tick range across all batches
                            if (tick.getTickNumber() < minTickSeen) {
                                minTickSeen = tick.getTickNumber();
                            }
                            if (tick.getTickNumber() > maxTickSeen) {
                                maxTickSeen = tick.getTickNumber();
                            }

                            expectedTick = tick.getTickNumber() + 1;
                        }

                        readOperations.incrementAndGet();
                        processedFiles.add(metadata.filename);
                        log.debug("Read and validated batch {} with {} matching ticks",
                            metadata.filename, ticks.stream()
                                .filter(t -> t.getSimulationRunId().equals(keyPrefix + "_run"))
                                .count());

                    } catch (IOException e) {
                        log.error("Failed to read batch {}", metadata.filename, e);
                        readErrors.incrementAndGet();
                        errors.add(new OperationalError(
                            Instant.now(),
                            "READ_BATCH_ERROR",
                            "Failed to read batch " + metadata.filename,
                            e.getMessage()
                        ));
                    }
                }

            } catch (IOException e) {
                log.error("Failed to query batches", e);
                readErrors.incrementAndGet();
                errors.add(new OperationalError(
                    Instant.now(),
                    "QUERY_BATCHES_ERROR",
                    "Failed to query batches",
                    e.getMessage()
                ));
            }

            Thread.sleep(intervalMs);
        }
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "messages_read", totalMessagesRead.get(),
            "bytes_read", totalBytesRead.get(),
            "read_operations", readOperations.get(),
            "validation_errors", validationErrors.get(),
            "read_errors", readErrors.get(),
            "files_processed", processedFiles.size()
        );
    }

    @Override
    public boolean isHealthy() {
        return errors.isEmpty();
    }

    @Override
    public List<OperationalError> getErrors() {
        synchronized (errors) {
            return new ArrayList<>(errors);
        }
    }

    @Override
    public void clearErrors() {
        errors.clear();
        readErrors.set(0);
    }
}
