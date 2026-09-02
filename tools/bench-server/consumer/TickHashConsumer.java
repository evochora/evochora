package org.evochora.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.StreamingBatch;
import org.evochora.datapipeline.services.AbstractService;

import com.typesafe.config.Config;

/**
 * Measurement-only consumer: drains TickDataChunks from its input queue and folds
 * every chunk into a running FNV-1a hash instead of persisting anything. The run id
 * is cleared before hashing, so two runs with identical simulation behaviour report
 * the identical hash regardless of their generated run ids. Progress is logged per
 * chunk; the last line before shutdown carries the final fingerprint.
 *
 * <p>When the optional {@code dumpDir} option is set, the normalized bytes of every
 * chunk (run id and capture times cleared) are additionally written to that directory
 * as one file per chunk, named {@code chunk_<seq>_<lastTick>.pb}. Two runs can then
 * be compared file by file to locate the first divergent chunk and decode it.</p>
 */
public final class TickHashConsumer extends AbstractService {

    private final IInputQueueResource<TickDataChunk> input;
    private final Path dumpDir;

    private long hash = 0xcbf29ce484222325L;
    private long chunkCount = 0;
    private long lastTick = -1;

    @SuppressWarnings("unchecked")
    public TickHashConsumer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.input = (IInputQueueResource<TickDataChunk>) getRequiredResource("input", IInputQueueResource.class);
        if (options.hasPath("dumpDir")) {
            this.dumpDir = Path.of(options.getString("dumpDir"));
            try {
                Files.createDirectories(this.dumpDir);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create dumpDir " + this.dumpDir, e);
            }
        } else {
            this.dumpDir = null;
        }
    }

    @Override
    protected void run() throws InterruptedException {
        while (!isStopRequested() && !Thread.currentThread().isInterrupted()) {
            checkPause();
            try (StreamingBatch<TickDataChunk> batch = input.receiveBatch(10, 5, TimeUnit.SECONDS)) {
                if (batch.size() == 0) {
                    continue;
                }
                for (TickDataChunk chunk : batch) {
                    // Strip run id and wall-clock capture times: they differ between two
                    // deterministic runs and must not influence the fingerprint.
                    TickDataChunk.Builder cb = chunk.toBuilder().clearSimulationRunId();
                    if (cb.hasSnapshot()) {
                        cb.getSnapshotBuilder().clearSimulationRunId().clearCaptureTimeMs();
                    }
                    for (int i = 0; i < cb.getDeltasCount(); i++) {
                        cb.getDeltasBuilder(i).clearCaptureTimeMs();
                    }
                    byte[] bytes = cb.build().toByteArray();
                    for (byte b : bytes) {
                        hash ^= (b & 0xffL);
                        hash *= 0x100000001b3L;
                    }
                    chunkCount++;
                    lastTick = chunk.getLastTick();
                    if (dumpDir != null) {
                        Path file = dumpDir.resolve(String.format("chunk_%06d_%d.pb", chunkCount, lastTick));
                        try {
                            Files.write(file, bytes);
                        } catch (IOException e) {
                            throw new UncheckedIOException("Cannot write " + file, e);
                        }
                    }
                    int orgs = chunk.hasSnapshot() ? chunk.getSnapshot().getOrganismsCount() : -1;
                    long created = chunk.hasSnapshot() ? chunk.getSnapshot().getTotalOrganismsCreated() : -1;
                    log.info("TICKHASH chunks={} lastTick={} orgs={} created={} hash={}",
                            chunkCount, lastTick, orgs, created, String.format("%016x", hash));
                }
                batch.commit();
            }
        }
    }
}
