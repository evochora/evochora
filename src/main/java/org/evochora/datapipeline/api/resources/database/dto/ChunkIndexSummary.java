package org.evochora.datapipeline.api.resources.database.dto;

/**
 * How much of a run's environment data is indexed: how many chunks the index holds and how far
 * they reach.
 * <p>
 * Both numbers come from a single aggregate query over the chunk index, which is far cheaper than
 * reading the index itself. An indexer only appends chunks, so two reads that agree in both
 * numbers saw the same index - which is what lets a caller reuse a result it derived from the
 * index earlier instead of reading it again.
 *
 * @param chunkCount Number of indexed chunks; zero when the run has no environment data
 * @param maxLastTick The highest tick any chunk reaches, or 0 when there are no chunks
 */
public record ChunkIndexSummary(long chunkCount, long maxLastTick) {
}
