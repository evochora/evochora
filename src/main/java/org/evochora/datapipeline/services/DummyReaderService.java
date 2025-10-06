package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.IStorageReadResource;
import org.evochora.datapipeline.api.resources.storage.MessageReader;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.services.IService.State;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

public class DummyReaderService extends AbstractService implements IMonitorable {
    private final IStorageReadResource storage;
    private final String keyPrefix;
    private final int intervalMs;
    private final boolean validateData;

    private final AtomicLong totalMessagesRead = new AtomicLong(0);
    private final AtomicLong totalBytesRead = new AtomicLong(0);
    private final AtomicLong readOperations = new AtomicLong(0);
    private final AtomicLong validationErrors = new AtomicLong(0);
    private final AtomicLong readFailed = new AtomicLong(0);
    private final Set<String> processedFiles = ConcurrentHashMap.newKeySet();

    // Error tracking
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();

    public DummyReaderService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.storage = getRequiredResource("storage", IStorageReadResource.class);
        this.keyPrefix = options.hasPath("keyPrefix") ? options.getString("keyPrefix") : "test";
        this.intervalMs = options.hasPath("intervalMs") ? options.getInt("intervalMs") : 1000;
        this.validateData = options.hasPath("validateData") ? options.getBoolean("validateData") : true;
    }

    @Override
    protected void run() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            checkPause();

            List<String> batchFiles = storage.listKeys(keyPrefix + "/batch_");
            Collections.sort(batchFiles);

            for (String batchFile : batchFiles) {
                if (processedFiles.contains(batchFile)) {
                    continue;
                }

                try {
                    long expectedTick = -1;
                    try (MessageReader<TickData> reader = storage.openReader(batchFile, TickData.parser())) {
                        while (reader.hasNext()) {
                            TickData tick = reader.next();

                            totalMessagesRead.incrementAndGet();
                            totalBytesRead.addAndGet(tick.getSerializedSize());

                            if (validateData && expectedTick >= 0) {
                                if (tick.getTickNumber() != expectedTick) {
                                    log.warn("Tick sequence error in {}: expected {}, got {}",
                                        batchFile, expectedTick, tick.getTickNumber());
                                    validationErrors.incrementAndGet();
                                    errors.add(new OperationalError(
                                        Instant.now(),
                                        "VALIDATION_ERROR",
                                        "Tick sequence error detected",
                                        String.format("File: %s, Expected: %d, Got: %d", batchFile, expectedTick, tick.getTickNumber())
                                    ));
                                }
                            }
                            expectedTick = tick.getTickNumber() + 1;
                        }
                    }
                    readOperations.incrementAndGet();
                    processedFiles.add(batchFile);
                    log.debug("Read and validated batch {}", batchFile);

                } catch (IOException e) {
                    log.error("Failed to read batch {}", batchFile, e);
                    readFailed.incrementAndGet();
                    errors.add(new OperationalError(
                        Instant.now(),
                        "READ_FAILED",
                        "Failed to read batch from storage",
                        String.format("File: %s, Error: %s", batchFile, e.getMessage())
                    ));
                }
            }

            // Sleep between read operations - Thread.sleep respects interruption
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
            "reads_failed", readFailed.get(),
            "files_processed", processedFiles.size()
        );
    }

    @Override
    public boolean isHealthy() {
        return getCurrentState() != State.ERROR;
    }

    @Override
    public List<OperationalError> getErrors() {
        return new ArrayList<>(errors);
    }

    @Override
    public void clearErrors() {
        errors.clear();
    }
}