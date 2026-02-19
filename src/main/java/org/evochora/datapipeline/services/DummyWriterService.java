package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.StreamingWriteResult;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test service that writes dummy TickDataChunk batches to storage using the chunk batch API.
 * Used for integration testing of storage resources.
 */
public class DummyWriterService extends AbstractService {
    private final IBatchStorageWrite storage;
    private final int intervalMs;
    private final int chunksPerWrite;
    private final int ticksPerChunk;
    private final int maxWrites;
    private final String keyPrefix;

    private final AtomicLong totalChunksWritten = new AtomicLong(0);
    private final AtomicLong totalBytesWritten = new AtomicLong(0);
    private final AtomicLong writeOperations = new AtomicLong(0);
    private final AtomicLong writeErrors = new AtomicLong(0);
    private long currentTick = 0;

    public DummyWriterService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.storage = getRequiredResource("storage", IBatchStorageWrite.class);
        this.intervalMs = options.hasPath("intervalMs") ? options.getInt("intervalMs") : 1000;
        this.chunksPerWrite = options.hasPath("messagesPerWrite") ? options.getInt("messagesPerWrite") : 10;
        this.ticksPerChunk = options.hasPath("ticksPerChunk") ? options.getInt("ticksPerChunk") : 10;
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

            // Collect a batch of chunks
            List<TickDataChunk> batch = new ArrayList<>();
            long firstTick = currentTick;
            
            for (int c = 0; c < chunksPerWrite; c++) {
                TickDataChunk chunk = generateDummyChunk(currentTick, ticksPerChunk);
                batch.add(chunk);
                totalBytesWritten.addAndGet(chunk.getSerializedSize());
                currentTick += ticksPerChunk;
            }
            
            try {
                StreamingWriteResult result = storage.writeChunkBatchStreaming(batch.iterator());
                totalChunksWritten.addAndGet(batch.size());
                writeOperations.incrementAndGet();
                log.debug("Wrote chunk batch {} with {} chunks (ticks {}-{})",
                    result.path(), result.chunkCount(), result.firstTick(), result.lastTick());

            } catch (IOException e) {
                long lastTick = currentTick - 1;
                log.warn("Failed to write chunk batch (ticks {}-{})", firstTick, lastTick);
                writeErrors.incrementAndGet();
                recordError(
                    "WRITE_BATCH_ERROR",
                    "Failed to write chunk batch",
                    String.format("Ticks: %d-%d", firstTick, lastTick)
                );
            }

            writeCount++;
            Thread.sleep(intervalMs);
        }
    }

    private TickDataChunk generateDummyChunk(long startTick, int tickCount) {
        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(keyPrefix + "_run")
            .setTickNumber(startTick)
            .setCaptureTimeMs(System.currentTimeMillis())
            .build();
        
        return TickDataChunk.newBuilder()
            .setSimulationRunId(keyPrefix + "_run")
            .setFirstTick(startTick)
            .setLastTick(startTick + tickCount - 1)
            .setTickCount(tickCount)
            .setSnapshot(snapshot)
            .build();
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        
        metrics.put("chunks_written", totalChunksWritten.get());
        metrics.put("bytes_written", totalBytesWritten.get());
        metrics.put("write_operations", writeOperations.get());
        metrics.put("write_errors", writeErrors.get());
    }
}
