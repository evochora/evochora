package org.evochora.datapipeline.api.resources.database;

import java.sql.SQLException;

import org.evochora.datapipeline.api.contracts.TickDataChunk;

/**
 * A chunk read that has everything it needs from the database and can be carried out without it.
 * <p>
 * Finding a chunk is a question to the database — which chunk holds this tick, and where does it
 * lie — and takes a query over two numbers. Reading it is hundreds of megabytes from disk and as
 * much again through the parser. Holding a pooled connection across the second starves the pool
 * for as long as the slowest disk takes, which is how a single reader can exhaust it.
 * <p>
 * Splitting the two lets a caller release the connection between them:
 * <pre>
 * PendingChunkRead pending;
 * try (IDatabaseReader reader = provider.createReader(runId)) {
 *     pending = reader.prepareChunkRead(tickNumber);
 * }
 * TickDataChunk chunk = pending.read();
 * </pre>
 * <p>
 * A storage strategy that keeps chunks inside the database rather than beside it has nothing to
 * gain here: it reads while it still holds the connection and hands back a read that only returns
 * what it already has.
 */
@FunctionalInterface
public interface PendingChunkRead {

    /**
     * Carries out the read.
     * <p>
     * Needs no database connection.
     *
     * @return The chunk
     * @throws SQLException if reading or parsing fails
     * @throws TickNotFoundException if the chunk has meanwhile disappeared
     */
    TickDataChunk read() throws SQLException, TickNotFoundException;
}
