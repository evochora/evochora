package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.IStorageWriteResource;
import org.evochora.datapipeline.api.resources.storage.MessageWriter;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.OperationalError;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class DummyWriterService extends AbstractService implements IMonitorable {
    private final IStorageWriteResource storage;
    private final int intervalMs;
    private final int messagesPerWrite;
    private final int maxWrites;
    private final String keyPrefix;

    private final AtomicLong totalMessagesWritten = new AtomicLong(0);
    private final AtomicLong totalBytesWritten = new AtomicLong(0);
    private final AtomicLong writeOperations = new AtomicLong(0);
    private long currentTick = 0;

    public DummyWriterService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.storage = getRequiredResource("storage", IStorageWriteResource.class);
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

            long startTick = currentTick;
            long endTick = currentTick + messagesPerWrite - 1;
            String filename = String.format("batch_%019d_%019d.pb", startTick, endTick);
            String key = keyPrefix + "/" + filename;

            try {
                try (MessageWriter writer = storage.openWriter(key)) {
                    for (int i = 0; i < messagesPerWrite; i++) {
                        TickData tick = generateDummyTickData(currentTick++);
                        writer.writeMessage(tick);

                        totalMessagesWritten.incrementAndGet();
                        totalBytesWritten.addAndGet(tick.getSerializedSize());
                    }
                }
                writeOperations.incrementAndGet();
                log.debug("Wrote batch {} with {} messages", key, messagesPerWrite);

            } catch (IOException e) {
                log.error("Failed to write batch {}", key, e);
            }

            writeCount++;
            // Sleep between write operations - Thread.sleep respects interruption
            Thread.sleep(intervalMs);
        }
    }

    private TickData generateDummyTickData(long tickNumber) {
        return TickData.newBuilder()
            .setSimulationRunId("dummy_run")
            .setTickNumber(tickNumber)
            .setCaptureTimeMs(System.currentTimeMillis())
            .build();
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "messages_written", totalMessagesWritten.get(),
            "bytes_written", totalBytesWritten.get(),
            "write_operations", writeOperations.get()
        );
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public List<OperationalError> getErrors() {
        return Collections.emptyList();
    }

    @Override
    public void clearErrors() {
        // No-op
    }
}