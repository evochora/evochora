package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.contracts.TickData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test service that writes dummy TickData batches to storage using the batch API.
 * Used for integration testing of storage resources.
 */
public class DummyWriterService extends AbstractService {
    private final IBatchStorageWrite storage;
    private final int intervalMs;
    private final int messagesPerWrite;
    private final int maxWrites;
    private final String keyPrefix;

    private final AtomicLong totalMessagesWritten = new AtomicLong(0);
    private final AtomicLong totalBytesWritten = new AtomicLong(0);
    private final AtomicLong writeOperations = new AtomicLong(0);
    private final AtomicLong writeErrors = new AtomicLong(0);
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

        // Check both isStopRequested() (graceful) and isInterrupted() (forced)
        while (!isStopRequested() && !Thread.currentThread().isInterrupted()) {
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
                StoragePath path = storage.writeBatch(batch, firstTick, lastTick);
                totalMessagesWritten.addAndGet(batch.size());
                writeOperations.incrementAndGet();
                log.debug("Wrote batch {} with {} messages (ticks {}-{})",
                    path, batch.size(), firstTick, lastTick);

            } catch (IOException e) {
                log.warn("Failed to write batch (ticks {}-{})", firstTick, lastTick);
                writeErrors.incrementAndGet();
                recordError(
                    "WRITE_BATCH_ERROR",
                    "Failed to write batch",
                    String.format("Ticks: %d-%d", firstTick, lastTick)
                );
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
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        
        metrics.put("messages_written", totalMessagesWritten.get());
        metrics.put("bytes_written", totalBytesWritten.get());
        metrics.put("write_operations", writeOperations.get());
        metrics.put("write_errors", writeErrors.get());
    }
}
