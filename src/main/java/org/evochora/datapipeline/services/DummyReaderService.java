package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.contracts.TickData;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test service that reads and validates TickData batches from storage using the batch API.
 * Used for integration testing of storage resources.
 * 
 * <h3>Configuration Options:</h3>
 * <ul>
 *   <li><b>keyPrefix</b>: Prefix for filtering files (default: "test").</li>
 *   <li><b>intervalMs</b>: Milliseconds between polling cycles (default: 1000).</li>
 *   <li><b>validateData</b>: Enable data validation (default: true).</li>
 *   <li><b>maxFiles</b>: Maximum files to process, -1 for unlimited (default: -1).</li>
 * </ul>
 */
public class DummyReaderService extends AbstractService {
    private final IBatchStorageRead storage;
    private final String keyPrefix;
    private final int intervalMs;
    private final boolean validateData;
    private final int maxFiles;

    private final AtomicLong totalMessagesRead = new AtomicLong(0);
    private final AtomicLong totalBytesRead = new AtomicLong(0);
    private final AtomicLong readOperations = new AtomicLong(0);
    private final AtomicLong validationErrors = new AtomicLong(0);
    private final AtomicLong readErrors = new AtomicLong(0);
    private final Set<String> processedFiles = ConcurrentHashMap.newKeySet();

    // Track expected tick range for validation
    private long minTickSeen = Long.MAX_VALUE;
    private long maxTickSeen = Long.MIN_VALUE;

    public DummyReaderService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.storage = getRequiredResource("storage", IBatchStorageRead.class);
        this.keyPrefix = options.hasPath("keyPrefix") ? options.getString("keyPrefix") : "test";
        this.intervalMs = options.hasPath("intervalMs") ? options.getInt("intervalMs") : 1000;
        this.validateData = options.hasPath("validateData") ? options.getBoolean("validateData") : true;
        this.maxFiles = options.hasPath("maxFiles") ? options.getInt("maxFiles") : -1;
    }

    @Override
    protected void run() throws InterruptedException {
        int filesProcessed = 0;
        
        while (!Thread.currentThread().isInterrupted() 
               && (maxFiles == -1 || filesProcessed < maxFiles)) {
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
                        // Check if max files limit reached
                        if (maxFiles != -1 && filesProcessed >= maxFiles) {
                            break;
                        }
                        
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
                            filesProcessed++;

                            log.debug("Read batch {} with {} ticks", filename, ticks.size());

                        } catch (IOException e) {
                            log.warn("Failed to read batch {}", filename);
                            readErrors.incrementAndGet();
                            recordError(
                                "READ_BATCH_ERROR",
                                "Failed to read batch",
                                String.format("Filename: %s", filename)
                            );
                        }
                    }
                    
                    // Break pagination if max files limit reached
                    if (maxFiles != -1 && filesProcessed >= maxFiles) {
                        break;
                    }

                    continuationToken = result.getNextContinuationToken();

                } while (continuationToken != null);

                if (filesFoundThisIteration > 0) {
                    log.debug("Processed {} new files this iteration", filesFoundThisIteration);
                }

            } catch (IOException e) {
                log.warn("Failed to list batch files");
                readErrors.incrementAndGet();
                recordError(
                    "LIST_FILES_ERROR",
                    "Failed to list batch files",
                    String.format("Key prefix: %s", keyPrefix)
                );
            }

            Thread.sleep(intervalMs);
        }
        
        if (maxFiles != -1 && filesProcessed >= maxFiles) {
            log.info("Reached max file limit of {}. Stopping service.", maxFiles);
        }
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        
        metrics.put("messages_read", totalMessagesRead.get());
        metrics.put("bytes_read", totalBytesRead.get());
        metrics.put("read_operations", readOperations.get());
        metrics.put("validation_errors", validationErrors.get());
        metrics.put("read_errors", readErrors.get());
        metrics.put("files_processed", processedFiles.size());
    }
}
