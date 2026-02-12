package org.evochora.datapipeline.api.memory;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SimulationParameters}.
 */
@Tag("unit")
class SimulationParametersTest {

    // ========================================================================
    // Factory Methods
    // ========================================================================

    @Test
    void of_withShapeAndOrganisms_usesDefaults() {
        SimulationParameters params = SimulationParameters.of(new int[]{100, 100}, 1000);

        assertEquals(10000, params.totalCells());
        assertEquals(1000, params.maxOrganisms());
        assertEquals(SimulationParameters.DEFAULT_SAMPLING_INTERVAL, params.samplingInterval());
        assertEquals(SimulationParameters.DEFAULT_ACCUMULATED_DELTA_INTERVAL, params.accumulatedDeltaInterval());
        assertEquals(SimulationParameters.DEFAULT_SNAPSHOT_INTERVAL, params.snapshotInterval());
        assertEquals(SimulationParameters.DEFAULT_CHUNK_INTERVAL, params.chunkInterval());
        assertEquals(SimulationParameters.DEFAULT_ESTIMATED_DELTA_RATIO, params.estimatedDeltaRatio());
    }

    @Test
    void of_withAllParameters_usesProvided() {
        SimulationParameters params = SimulationParameters.of(
            new int[]{100, 100}, 1000,
            2, 10, 50, 2, 0.05
        );

        assertEquals(10000, params.totalCells());
        assertEquals(2, params.samplingInterval());
        assertEquals(10, params.accumulatedDeltaInterval());
        assertEquals(50, params.snapshotInterval());
        assertEquals(2, params.chunkInterval());
        assertEquals(0.05, params.estimatedDeltaRatio());
    }

    // ========================================================================
    // Chunk Size Calculations
    // ========================================================================

    @Test
    void samplesPerChunk_withDefaults_returns100() {
        SimulationParameters params = SimulationParameters.of(new int[]{100, 100}, 1000);

        // 5 * 20 * 1 = 100
        assertEquals(100, params.samplesPerChunk());
    }

    @Test
    void samplesPerChunk_independentOfSamplingInterval() {
        SimulationParameters params = SimulationParameters.of(
            new int[]{100, 100}, 1000,
            10000, 5, 5, 1, 0.01
        );

        // 5 * 5 * 1 = 25 (samplingInterval does NOT affect sample count)
        assertEquals(25, params.samplesPerChunk());
    }

    @Test
    void simulationTicksPerChunk_withDefaults_returns100() {
        SimulationParameters params = SimulationParameters.of(new int[]{100, 100}, 1000);

        // 1 * 5 * 20 * 1 = 100
        assertEquals(100, params.simulationTicksPerChunk());
    }

    @Test
    void simulationTicksPerChunk_withCustomValues_calculatesCorrectly() {
        SimulationParameters params = SimulationParameters.of(
            new int[]{100, 100}, 1000,
            2, 10, 50, 2, 0.01
        );

        // 2 * 10 * 50 * 2 = 2000
        assertEquals(2000, params.simulationTicksPerChunk());
    }

    @Test
    void simulationTicksPerChunk_equalsSamplingInterval_timesSamplesPerChunk() {
        SimulationParameters params = SimulationParameters.of(
            new int[]{100, 100}, 1000,
            10000, 5, 5, 1, 0.01
        );

        assertEquals(params.samplingInterval() * params.samplesPerChunk(),
            params.simulationTicksPerChunk());
        // 10000 * 25 = 250000
        assertEquals(250000, params.simulationTicksPerChunk());
    }

    @Test
    void snapshotsPerChunk_equalsChunkInterval() {
        SimulationParameters params = SimulationParameters.of(
            new int[]{100, 100}, 1000,
            1, 5, 20, 3, 0.01
        );

        assertEquals(3, params.snapshotsPerChunk());
    }

    @Test
    void accumulatedDeltasPerChunk_calculatesCorrectly() {
        // With snapshotInterval=20 and chunkInterval=1:
        // Each snapshot has 19 accumulated deltas (20-1)
        SimulationParameters params = SimulationParameters.of(new int[]{100, 100}, 1000);

        // 1 * (20 - 1) = 19
        assertEquals(19, params.accumulatedDeltasPerChunk());
    }

    @Test
    void incrementalDeltasPerChunk_calculatesCorrectly() {
        SimulationParameters params = SimulationParameters.of(new int[]{100, 100}, 1000);

        // Samples: 100
        // Snapshots: 1
        // Accumulated deltas: 19
        // Incremental: 100 - 1 - 19 = 80
        assertEquals(80, params.incrementalDeltasPerChunk());
    }

    @Test
    void incrementalDeltasPerChunk_withHighSamplingInterval_usesSamplesNotTicks() {
        SimulationParameters params = SimulationParameters.of(
            new int[]{100, 100}, 1000,
            10000, 5, 5, 1, 0.01
        );

        // Samples per chunk: 5 * 5 * 1 = 25
        // Snapshots: 1
        // Accumulated deltas: 1 * (5 - 1) = 4
        // Incremental: 25 - 1 - 4 = 20
        assertEquals(20, params.incrementalDeltasPerChunk());
    }

    @Test
    void deltaBreakdown_sumsToSamplesPerChunk() {
        SimulationParameters params = SimulationParameters.of(
            new int[]{100, 100}, 1000,
            1, 5, 20, 2, 0.01
        );

        int total = params.snapshotsPerChunk()
                  + params.accumulatedDeltasPerChunk()
                  + params.incrementalDeltasPerChunk();

        assertEquals(params.samplesPerChunk(), total);
    }

    @Test
    void deltaBreakdown_sumsToSamplesPerChunk_withHighSamplingInterval() {
        SimulationParameters params = SimulationParameters.of(
            new int[]{100, 100}, 1000,
            10000, 5, 5, 1, 0.01
        );

        int total = params.snapshotsPerChunk()
                  + params.accumulatedDeltasPerChunk()
                  + params.incrementalDeltasPerChunk();

        // Should sum to 25 (not 250000!)
        assertEquals(25, total);
        assertEquals(params.samplesPerChunk(), total);
    }

    // ========================================================================
    // Byte Estimation
    // ========================================================================

    @Test
    void estimateBytesPerDelta_smallerThanSnapshot() {
        SimulationParameters params = SimulationParameters.of(new int[]{1000, 1000}, 1000);

        long snapshotBytes = params.estimateBytesPerTick();
        long deltaBytes = params.estimateBytesPerDelta();

        // Delta should be much smaller than snapshot (1% change rate)
        assertTrue(deltaBytes < snapshotBytes);
        // At 1% change, delta environment is ~1% of snapshot environment
        // But organisms are same size, so delta is not 1% total
    }

    @Test
    void estimateBytesPerChunk_reasonableSize() {
        SimulationParameters params = SimulationParameters.of(new int[]{1000, 1000}, 1000);

        long chunkBytes = params.estimateBytesPerChunk();
        long singleSnapshotBytes = params.estimateBytesPerTick();

        // Chunk should be larger than 1 snapshot (has 100 samples)
        assertTrue(chunkBytes > singleSnapshotBytes);

        // But much smaller than 100 snapshots (due to compression)
        long uncompressedBytes = 100L * singleSnapshotBytes;
        assertTrue(chunkBytes < uncompressedBytes);
    }

    @Test
    void estimateBytesPerChunk_withHighSamplingInterval_usesOnlySamples() {
        // samplingInterval=10000 should NOT inflate memory estimate
        SimulationParameters withHighSampling = SimulationParameters.of(
            new int[]{1000, 1000}, 1000,
            10000, 5, 5, 1, 0.01
        );
        SimulationParameters withDefaultSampling = SimulationParameters.of(
            new int[]{1000, 1000}, 1000,
            1, 5, 5, 1, 0.01
        );

        // Both have 25 samples per chunk, so memory estimate should be identical
        assertEquals(withDefaultSampling.estimateBytesPerChunk(),
            withHighSampling.estimateBytesPerChunk());
    }

    @Test
    void estimateCompressionRatio_withDefaults_achievesGoodCompression() {
        SimulationParameters params = SimulationParameters.of(new int[]{1000, 1000}, 100);

        double ratio = params.estimateCompressionRatio();

        // With 1% change rate and 100 samples/chunk, expect significant compression
        // Exact ratio depends on organism overhead, but should be > 5x
        assertTrue(ratio > 5.0, "Expected compression ratio > 5.0, got " + ratio);
    }

    @Test
    void estimateCompressionRatio_withNoCompression_returnsNear1() {
        // When snapshotInterval=1, every tick is a snapshot = no compression
        SimulationParameters params = SimulationParameters.of(
            new int[]{100, 100}, 100,
            1, 1, 1, 1, 0.01  // Every tick is a snapshot
        );

        double ratio = params.estimateCompressionRatio();

        // Should be close to 1 (no compression)
        assertEquals(1.0, ratio, 0.1);
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    void withLargeEnvironment_calculatesCorrectly() {
        // 10000 x 10000 = 100M cells
        SimulationParameters params = SimulationParameters.of(new int[]{10000, 10000}, 10000);

        assertEquals(100_000_000, params.totalCells());
        assertEquals(100, params.samplesPerChunk());
        assertEquals(100, params.simulationTicksPerChunk());

        // Should not overflow
        long bytesPerTick = params.estimateBytesPerTick();
        assertTrue(bytesPerTick > 0);

        long bytesPerChunk = params.estimateBytesPerChunk();
        assertTrue(bytesPerChunk > 0);
    }

    @Test
    void with3dEnvironment_calculatesCorrectly() {
        SimulationParameters params = SimulationParameters.of(new int[]{100, 100, 100}, 1000);

        assertEquals(1_000_000, params.totalCells());
    }

    // ========================================================================
    // Existing Functionality (Regression)
    // ========================================================================

    @Test
    void worstCaseCellsPerTick_returnsTotalCells() {
        SimulationParameters params = SimulationParameters.of(new int[]{100, 100}, 1000);
        assertEquals(10000, params.worstCaseCellsPerTick());
    }

    @Test
    void worstCaseOrganismsPerTick_returnsMaxOrganisms() {
        SimulationParameters params = SimulationParameters.of(new int[]{100, 100}, 1000);
        assertEquals(1000, params.worstCaseOrganismsPerTick());
    }

    @Test
    void estimateBytesPerTick_includesAllComponents() {
        SimulationParameters params = SimulationParameters.of(new int[]{100, 100}, 1000);

        long expected = params.estimateEnvironmentBytesPerTick()
                      + params.estimateOrganismBytesPerTick()
                      + SimulationParameters.TICKDATA_WRAPPER_OVERHEAD;

        assertEquals(expected, params.estimateBytesPerTick());
    }

    @Test
    void formatBytes_formatsCorrectly() {
        assertEquals("500 B", SimulationParameters.formatBytes(500));
        assertEquals("1.0 KB", SimulationParameters.formatBytes(1024));
        assertEquals("1.5 MB", SimulationParameters.formatBytes(1024 * 1024 + 512 * 1024));
        assertEquals("2.00 GB", SimulationParameters.formatBytes(2L * 1024 * 1024 * 1024));
    }
}
