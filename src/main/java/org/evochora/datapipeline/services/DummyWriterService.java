package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.OperationalError;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test service that writes dummy TickData batches to storage using the batch API.
 * Used for integration testing of storage resources.
 */
public class DummyWriterService extends AbstractService implements IMonitorable {
    private final IBatchStorageWrite storage;
    private final int intervalMs;
    private final int messagesPerWrite;
    private final int maxWrites;
    private final String keyPrefix;

    private final AtomicLong totalMessagesWritten = new AtomicLong(0);
    private final AtomicLong totalBytesWritten = new AtomicLong(0);
    private final AtomicLong writeOperations = new AtomicLong(0);
    private final AtomicLong writeErrors = new AtomicLong(0);
    private final List<OperationalError> errors = Collections.synchronizedList(new ArrayList<>());
    private long currentTick = 0;

    public DummyWriterService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.storage = getRequiredResource("storage", IBatchStorageWrite.class);
        this.intervalMs = options.hasPath("intervalMs") ? options.getInt("intervalMs") : 1000;
        this.messagesPerWrite = options.hasPath("messagesPerWrite") ? options.getInt("messagesPerWrite") : 10;
        this.maxWrites = options.hasPath("maxWrites") ? options.getInt("maxWrites") : -1;
        this.keyPrefix = options.hasPath("keyPrefix") ? options.getString("keyPrefix") : "test";
    }

    @Override
    protected void run() throws InterruptedException {
        int writeCount = 0;

        while (!Thread.currentThread().isInterrupted()) {
            checkPause();

            if (maxWrites > 0 && writeCount >= maxWrites) {
                log.info("Reached maxWrites ({}), stopping", maxWrites);
                break;
            }

            // Collect a batch of messages
            List<TickData> batch = new ArrayList<>();
            long firstTick = currentTick;
            for (int i = 0; i < messagesPerWrite; i++) {
                TickData tick = generateDummyTickData(currentTick++);
                batch.add(tick);
                totalBytesWritten.addAndGet(tick.getSerializedSize());
            }
            long lastTick = currentTick - 1;

            try {
                // Write batch using batch API
                String filename = storage.writeBatch(batch, firstTick, lastTick);
                totalMessagesWritten.addAndGet(batch.size());
                writeOperations.incrementAndGet();
                log.debug("Wrote batch {} with {} messages (ticks {}-{})",
                    filename, batch.size(), firstTick, lastTick);

            } catch (IOException e) {
                log.error("Failed to write batch (ticks {}-{})", firstTick, lastTick, e);
                writeErrors.incrementAndGet();
                errors.add(new OperationalError(
                    Instant.now(),
                    "WRITE_BATCH_ERROR",
                    "Failed to write batch (ticks " + firstTick + "-" + lastTick + ")",
                    e.getMessage()
                ));
            }

            writeCount++;
            Thread.sleep(intervalMs);
        }
    }

    private TickData generateDummyTickData(long tickNumber) {
        return TickData.newBuilder()
            .setSimulationRunId(keyPrefix + "_run")
            .setTickNumber(tickNumber)
            .setCaptureTimeMs(System.currentTimeMillis())
            .build();
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "messages_written", totalMessagesWritten.get(),
            "bytes_written", totalBytesWritten.get(),
            "write_operations", writeOperations.get(),
            "write_errors", writeErrors.get()
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
        writeErrors.set(0);
    }
}
