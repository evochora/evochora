package org.evochora.cli.rendering.frame.descent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
import org.evochora.datapipeline.api.resources.storage.CheckedConsumer;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;

/**
 * A synthetic run held in memory for the tests of the {@code descent} renderer: recorded ticks
 * built as protobuf messages, packed into chunks and batch files, and served by a mocked storage.
 * <p>
 * A tick is written as a list of organisms, each {@code id:parent@x,y}, with a trailing
 * {@code +} for a dead one; parent 0 is a founder. An organism may carry its genome hash as
 * {@code #genome} and its parent's genome hash as {@code ^parentGenome} after the position:
 * {@code 5:3@50,50#7^3}; without them the genome hash is 0 and the parent genome hash absent.
 */
final class DescentRunFixture {

    /** Batch files of the run, each a list of chunks. */
    final List<List<TickDataChunk>> batches = new ArrayList<>();

    /** Paths of the batch files, parallel to {@link #batches}. */
    final List<StoragePath> paths = new ArrayList<>();

    /** Paths of the batch files the storage was asked for, in order; asked from any thread. */
    final List<StoragePath> read = Collections.synchronizedList(new ArrayList<>());

    /**
     * Adds a batch file holding one chunk of consecutive recorded ticks.
     *
     * @param firstTick The tick of the chunk's snapshot
     * @param ticks     The organisms of each recorded tick, one tick apart
     * @return This fixture
     */
    DescentRunFixture batch(final long firstTick, final String... ticks) {
        batches.add(List.of(chunk(firstTick, ticks)));
        paths.add(StoragePath.of("run/raw/batch_" + firstTick + "_" + (firstTick + ticks.length - 1) + ".pb"));
        return this;
    }

    /**
     * A storage that serves the batch files, only through a read that skips environment cells.
     *
     * @return The mocked storage
     * @throws Exception never; declared by the mocked method
     */
    IBatchStorageRead storage() throws Exception {
        final IBatchStorageRead storage = mock(IBatchStorageRead.class);
        doAnswer(invocation -> {
            final StoragePath path = invocation.getArgument(0);
            final CheckedConsumer<TickDataChunk> consumer = invocation.getArgument(2);
            read.add(path);
            for (final TickDataChunk chunk : batches.get(paths.indexOf(path))) {
                consumer.accept(chunk);
            }
            return null;
        }).when(storage).forEachChunk(any(StoragePath.class), eq(ChunkFieldFilter.SKIP_CELLS), any());
        return storage;
    }

    /**
     * Builds a chunk of consecutive recorded ticks.
     *
     * @param firstTick The tick of the snapshot
     * @param ticks     The organisms of each recorded tick
     * @return The chunk
     */
    static TickDataChunk chunk(final long firstTick, final String... ticks) {
        final TickDataChunk.Builder chunk = TickDataChunk.newBuilder()
            .setFirstTick(firstTick)
            .setLastTick(firstTick + ticks.length - 1)
            .setTickCount(ticks.length)
            .setSnapshot(snapshot(firstTick, ticks[0]));
        for (int i = 1; i < ticks.length; i++) {
            chunk.addDeltas(delta(firstTick + i, ticks[i]));
        }
        return chunk.build();
    }

    /**
     * Builds a snapshot without environment cells.
     *
     * @param tick      The tick number
     * @param organisms The organisms, as described in the class comment
     * @return The snapshot
     */
    static TickData snapshot(final long tick, final String organisms) {
        final List<OrganismState> list = organisms(organisms);
        return TickData.newBuilder()
            .setTickNumber(tick)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .addAllOrganisms(list)
            .setTotalOrganismsCreated(maxId(list))
            .build();
    }

    /**
     * Builds a delta without changed cells.
     *
     * @param tick      The tick number
     * @param organisms The organisms, as described in the class comment
     * @return The delta
     */
    static TickDelta delta(final long tick, final String organisms) {
        final List<OrganismState> list = organisms(organisms);
        return TickDelta.newBuilder()
            .setTickNumber(tick)
            .setChangedCells(CellDataColumns.getDefaultInstance())
            .addAllOrganisms(list)
            .setTotalOrganismsCreated(maxId(list))
            .build();
    }

    /**
     * Parses a list of organisms.
     *
     * @param spec Organisms separated by blanks, each {@code id:parent@x,y}, optionally followed
     *             by {@code #genome} and {@code ^parentGenome}, with an optional trailing
     *             {@code +} for a dead one
     * @return The organisms
     */
    static List<OrganismState> organisms(final String spec) {
        final List<OrganismState> list = new ArrayList<>();
        for (final String item : spec.trim().split("\\s+")) {
            if (item.isEmpty()) {
                continue;
            }
            final boolean dead = item.endsWith("+");
            String body = dead ? item.substring(0, item.length() - 1) : item;
            final int caret = body.indexOf('^');
            final String parentGenome = caret < 0 ? null : body.substring(caret + 1);
            body = caret < 0 ? body : body.substring(0, caret);
            final int hash = body.indexOf('#');
            final String genome = hash < 0 ? null : body.substring(hash + 1);
            body = hash < 0 ? body : body.substring(0, hash);
            final String[] idAndRest = body.split(":");
            final String[] parentAndPos = idAndRest[1].split("@");
            final String[] xy = parentAndPos[1].split(",");
            final OrganismState.Builder organism = OrganismState.newBuilder()
                .setOrganismId(Integer.parseInt(idAndRest[0]))
                .setIp(Vector.newBuilder().addComponents(Integer.parseInt(xy[0])).addComponents(Integer.parseInt(xy[1])))
                .setDv(Vector.newBuilder().addComponents(1).addComponents(0))
                .setIsDead(dead);
            if (genome != null) {
                organism.setGenomeHash(Long.parseLong(genome));
            }
            if (parentGenome != null) {
                organism.setParentGenomeHash(Long.parseLong(parentGenome));
            }
            final int parent = Integer.parseInt(parentAndPos[0]);
            if (parent != 0) {
                organism.setParentId(parent);
            }
            list.add(organism.build());
        }
        return list;
    }

    private static long maxId(final List<OrganismState> organisms) {
        long max = 0;
        for (final OrganismState organism : organisms) {
            max = Math.max(max, organism.getOrganismId());
        }
        return max;
    }
}
