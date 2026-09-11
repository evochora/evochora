package org.evochora.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
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
 * <p>Every chunk also carries the organisms of each sampled tick, so the consumer adds up
 * how many ticks the organisms actually executed: one organism executes one instruction per
 * tick, which makes this the work a run performed, independent of how its population grew.
 * Wall time divided by {@code orgticks} compares two runs whose populations differ.</p>
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

    /** Birth tick of every organism not yet seen dead, by organism id. */
    private final Map<Integer, Long> living = new HashMap<>();
    private long settledOrganismTicks = 0;
    private long settledOrganisms = 0;
    private boolean accountingWarned = false;

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
                    if (chunk.hasSnapshot()) {
                        recordLifespans(chunk.getSnapshot().getTickNumber(), chunk.getSnapshot().getOrganismsList());
                    }
                    for (TickDelta delta : chunk.getDeltasList()) {
                        recordLifespans(delta.getTickNumber(), delta.getOrganismsList());
                    }
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
                    log.info("TICKHASH chunks={} lastTick={} orgs={} created={} orgticks={} hash={}",
                            chunkCount, lastTick, orgs, created, organismTicks(), String.format("%016x", hash));
                    warnOnceIfOrganismsUnaccounted(chunk);
                }
                batch.commit();
            }
        }
    }

    /**
     * Adds the organisms recorded at one sampled tick to the lifespan accounting.
     * <p>
     * An organism that appears dead is settled with its full lifespan, because the engine
     * serializes a dead organism once - carrying its death tick - and prunes it only afterwards.
     * One that is still alive is carried by its birth tick until it is settled or the run ends.
     *
     * @param tick the sampled tick these organisms were recorded at, used as the death tick of an
     *             organism that reports none
     * @param organisms every organism of that tick, dead ones included
     */
    private void recordLifespans(long tick, List<OrganismState> organisms) {
        for (OrganismState organism : organisms) {
            if (organism.getIsDead()) {
                living.remove(organism.getOrganismId());
                long deathTick = organism.hasDeathTick() ? organism.getDeathTick() : tick;
                settledOrganismTicks += deathTick - organism.getBirthTick();
                settledOrganisms++;
            } else {
                living.put(organism.getOrganismId(), organism.getBirthTick());
            }
        }
    }

    /**
     * Returns the ticks all organisms have executed so far: the settled lifespans plus the
     * lifespan every living organism has reached at the last tick seen.
     *
     * @return the number of organism ticks executed up to {@link #lastTick}
     */
    private long organismTicks() {
        long total = settledOrganismTicks;
        for (long birthTick : living.values()) {
            total += lastTick - birthTick;
        }
        return total;
    }

    /**
     * Warns once when the organisms counted do not add up to what the engine reports as created.
     * The lifespan sum is only correct while every organism is seen exactly once as dead, and a
     * count that silently drifts would make a measurement wrong without making it look wrong.
     *
     * @param chunk the chunk whose last recorded tick the count is checked against
     */
    private void warnOnceIfOrganismsUnaccounted(TickDataChunk chunk) {
        if (accountingWarned) {
            return;
        }
        long created = chunk.getDeltasCount() > 0
                ? chunk.getDeltas(chunk.getDeltasCount() - 1).getTotalOrganismsCreated()
                : chunk.hasSnapshot() ? chunk.getSnapshot().getTotalOrganismsCreated() : -1;
        long counted = settledOrganisms + living.size();
        if (created >= 0 && counted != created) {
            log.warn("Organism accounting is off at tick {}: counted {}, engine created {}. "
                    + "The orgticks sum of this run cannot be trusted.", lastTick, counted, created);
            accountingWarned = true;
        }
    }
}
