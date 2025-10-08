package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
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
                // Use paginated API to discover batch files
                // Note: Production indexers will use database coordinator for work distribution.
                // This is a test service that reads all files for validation purposes.

                String continuationToken = null;
                int filesFoundThisIteration = 0;

                do {
                    // Files are stored under simulationRunId (keyPrefix + "_run")
                    BatchFileListResult result = storage.listBatchFiles(keyPrefix + "_run/", continuationToken, 100);

                    for (String filename : result.getFilenames()) {
                        // Skip already processed files
                        if (processedFiles.contains(filename)) {
                            continue;
                        }

                        try {
                            List<TickData> ticks = storage.readBatch(filename);

                            long expectedTick = -1;
                            for (TickData tick : ticks) {
                                // Filter by simulation run ID
                                if (!tick.getSimulationRunId().equals(keyPrefix + "_run")) {
                                    continue;
                                }

                                totalMessagesRead.incrementAndGet();
                                totalBytesRead.addAndGet(tick.getSerializedSize());

                                // Validate sequential order within batch
                                if (validateData && expectedTick >= 0) {
                                    if (tick.getTickNumber() != expectedTick) {
                                        log.warn("Tick sequence error in {}: expected {}, got {}",
                                            filename, expectedTick, tick.getTickNumber());
                                        validationErrors.incrementAndGet();
                                    }
                                }

                                // Track tick range
                                if (tick.getTickNumber() < minTickSeen) {
                                    minTickSeen = tick.getTickNumber();
                                }
                                if (tick.getTickNumber() > maxTickSeen) {
                                    maxTickSeen = tick.getTickNumber();
                                }

                                expectedTick = tick.getTickNumber() + 1;
                            }

                            readOperations.incrementAndGet();
                            processedFiles.add(filename);
                            filesFoundThisIteration++;

                            log.debug("Read batch {} with {} ticks", filename, ticks.size());

                        } catch (IOException e) {
                            log.error("Failed to read batch {}", filename, e);
                            readErrors.incrementAndGet();
                            errors.add(new OperationalError(
                                Instant.now(),
                                "READ_BATCH_ERROR",
                                "Failed to read batch " + filename,
                                e.getMessage()
                            ));
                        }
                    }

                    continuationToken = result.getNextContinuationToken();

                } while (continuationToken != null);

                if (filesFoundThisIteration > 0) {
                    log.debug("Processed {} new files this iteration", filesFoundThisIteration);
                }

            } catch (IOException e) {
                log.error("Failed to list batch files", e);
                readErrors.incrementAndGet();
                errors.add(new OperationalError(
                    Instant.now(),
                    "LIST_FILES_ERROR",
                    "Failed to list batch files",
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
