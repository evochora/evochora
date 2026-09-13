package org.evochora.datapipeline.api.resources.database.dto;

/**
 * How much of a run's environment data is indexed: how many chunks the index holds, how far they
 * reach, and how many ticks they carry altogether.
 * <p>
 * All three numbers come from a single aggregate query over the chunk index, which is far cheaper
 * than reading the index itself. They are what a caller compares to find out whether a result it
 * derived from the index still holds: an indexer that only appends chunks raises the count and the
 * sample count by exactly what it appended, so a caller that knows what it saw before can tell an
 * appended index from a rewritten one and read only the chunks that are new.
 *
 * @param chunkCount Number of indexed chunks; zero when the run has no environment data
 * @param maxLastTick The highest tick any chunk reaches, or 0 when there are no chunks
 * @param sampleCount The ticks all chunks hold together, or 0 when there are no chunks
 */
public record ChunkIndexSummary(long chunkCount, long maxLastTick, long sampleCount) {
}
