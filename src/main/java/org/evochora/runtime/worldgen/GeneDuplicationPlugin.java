package org.evochora.runtime.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IRandomProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * Gene duplication birth handler inspired by Ohno's (1970) model of evolution through gene duplication.
 * <p>
 * Called once per newborn organism in the post-Execute phase of each tick. With configurable probability,
 * copies a code block starting at a randomly selected LABEL into an empty (CODE:0) region within the
 * organism's body. The duplicated block is immediately neutral (redundant) but provides raw material
 * for later divergence through point mutation.
 * <p>
 * The algorithm groups owned cells by scan lines perpendicular to the organism's direction vector (DV),
 * ensuring equal selection probability for each scan line regardless of cell density. A random scan line
 * is chosen as the target for NOP area search, and a random LABEL is selected as the source via reservoir
 * sampling.
 * <p>
 * <strong>Performance:</strong> Near-zero allocation after warmup. Reusable coordinate buffers,
 * ScanLineInfo pooling, direct bit extraction from packed molecule ints, and self-computed strides
 * for flat index calculation minimize GC pressure. The owned-cell iteration is O(n) where n is
 * typically 1000-3000, running at most a few times per tick.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Runs in the sequential post-Execute phase of
 * {@code Simulation.tick()}.
 *
 * @see org.evochora.runtime.spi.IBirthHandler
 */
public class GeneDuplicationPlugin implements IBirthHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GeneDuplicationPlugin.class);

    private final Random random;
    private final double duplicationRate;
    private final int minNopSize;

    // Reusable buffers (lazy-initialized on first duplicate() call)
    private int[] coordBuffer;
    private int[] sourcePos;
    private int[] targetPos;
    private int[] strides;
    private int[] perpStrides;

    // Scan line map and pool (reused across duplicate() calls)
    private final Int2ObjectOpenHashMap<ScanLineInfo> scanLineMap = new Int2ObjectOpenHashMap<>();
    private final ArrayList<ScanLineInfo> scanLinePool = new ArrayList<>();
    private int poolIndex;

    // DV coordinate collector for shortest-arc computation (reused)
    private int[] dvCoordCollector;

    /**
     * Mutable scan line info for grouping owned cells by perpendicular coordinates.
     * Pooled and reused across duplicate() calls to avoid allocation.
     */
    static class ScanLineInfo {
        /** Minimum DV-dimension coordinate on this scan line. */
        int minDv;
        /** Maximum DV-dimension coordinate on this scan line. */
        int maxDv;
        /** Any flat index on this scan line, for coordinate reconstruction. */
        int sampleFlatIndex;
        /** Number of owned cells on this scan line. */
        int count;
        /** Start of the shortest arc containing all owned cells (inclusive). */
        int walkStart;
        /** End of the shortest arc containing all owned cells (inclusive). */
        int walkEnd;
        /** Start of the largest NOP run (DV coordinate), or -1 if none found. */
        int bestNopStart;
        /** Length of the largest NOP run, or 0 if none found. */
        int bestNopLength;

        /**
         * Resets this info for a new scan line.
         *
         * @param dvCoord The DV-dimension coordinate of the first cell seen.
         * @param flatIndex The flat index of the first cell seen.
         */
        void reset(int dvCoord, int flatIndex) {
            this.minDv = dvCoord;
            this.maxDv = dvCoord;
            this.sampleFlatIndex = flatIndex;
            this.count = 1;
            this.bestNopStart = -1;
            this.bestNopLength = 0;
        }

        /**
         * Updates min/max tracking with a new DV coordinate.
         *
         * @param dvCoord The DV-dimension coordinate of a cell on this scan line.
         */
        void update(int dvCoord) {
            if (dvCoord < minDv) minDv = dvCoord;
            if (dvCoord > maxDv) maxDv = dvCoord;
            count++;
        }
    }

    /**
     * Creates a gene duplication plugin.
     *
     * @param randomProvider Source of randomness.
     * @param config Configuration containing duplicationRate and minNopSize.
     */
    public GeneDuplicationPlugin(IRandomProvider randomProvider, com.typesafe.config.Config config) {
        this.random = randomProvider.asJavaRandom();
        this.duplicationRate = config.getDouble("duplicationRate");
        this.minNopSize = config.getInt("minNopSize");
        if (duplicationRate < 0.0 || duplicationRate > 1.0) {
            throw new IllegalArgumentException("duplicationRate must be in [0.0, 1.0], got: " + duplicationRate);
        }
        if (minNopSize < 1) {
            throw new IllegalArgumentException("minNopSize must be positive, got: " + minNopSize);
        }
    }

    /**
     * Convenience constructor for tests.
     *
     * @param randomProvider Source of randomness.
     * @param duplicationRate Probability of duplication per newborn (0.0 to 1.0).
     * @param minNopSize Minimum contiguous empty cells required as duplication target.
     */
    GeneDuplicationPlugin(IRandomProvider randomProvider, double duplicationRate, int minNopSize) {
        this.random = randomProvider.asJavaRandom();
        this.duplicationRate = duplicationRate;
        this.minNopSize = minNopSize;
    }

    /** {@inheritDoc} */
    @Override
    public void onBirth(Organism child, Environment environment) {
        if (random.nextDouble() >= duplicationRate) {
            return;
        }
        duplicate(child, environment);
    }

    /**
     * Performs gene duplication for a single newborn organism.
     * <p>
     * Groups owned cells by scan line, selects a random LABEL via reservoir sampling,
     * finds the largest NOP area on a random scan line, and copies the code block
     * starting at the label into the NOP area.
     *
     * @param child The newborn organism.
     * @param env The simulation environment.
     */
    void duplicate(Organism child, Environment env) {
        int childId = child.getId();
        IntOpenHashSet owned = env.getCellsOwnedBy(childId);
        if (owned == null || owned.isEmpty()) {
            LOG.debug("tick={} Organism {} selected for duplication but has no owned cells", child.getBirthTick(), childId);
            return;
        }

        int[] dv = child.getDv();
        int[] shape = env.getShape();
        int dims = shape.length;

        // Find the DV dimension (first non-zero component)
        int dvDim = -1;
        for (int i = 0; i < dims; i++) {
            if (dv[i] != 0) {
                dvDim = i;
                break;
            }
        }
        if (dvDim == -1) {
            LOG.debug("tick={} Organism {} gene duplication: degenerate DV", child.getBirthTick(), childId);
            return;
        }

        ensureBuffers(dims);
        computeStrides(shape, dims);
        computePerpStrides(shape, dims, dvDim);

        // --- Step 2: Group owned cells by scan line + reservoir sample a label ---
        scanLineMap.clear();
        poolIndex = 0;

        // Reservoir sampling state for label selection
        final int[] labelState = new int[3]; // [0]=perpKey, [1]=dvCoord, [2]=labelCount
        labelState[2] = 0; // labelCount

        final int dvDimFinal = dvDim;

        owned.forEach((int flatIndex) -> {
            env.properties.flatIndexToCoordinates(flatIndex, coordBuffer);

            int perpKey = computePerpKey(coordBuffer, dvDimFinal);
            int dvCoord = coordBuffer[dvDimFinal];

            ScanLineInfo scanLine = scanLineMap.get(perpKey);
            if (scanLine == null) {
                scanLine = acquireFromPool();
                scanLine.reset(dvCoord, flatIndex);
                scanLineMap.put(perpKey, scanLine);
            } else {
                scanLine.update(dvCoord);
            }

            // Reservoir sampling for label selection
            int moleculeInt = env.getMoleculeInt(flatIndex);
            if ((moleculeInt & Config.TYPE_MASK) == Config.TYPE_LABEL) {
                labelState[2]++;
                if (random.nextInt(labelState[2]) == 0) {
                    labelState[0] = perpKey;
                    labelState[1] = dvCoord;
                }
            }
        });

        resolveWalkRanges(owned, env, dvDimFinal, shape[dvDimFinal]);

        int labelCount = labelState[2];
        if (labelCount == 0) {
            LOG.debug("tick={} Organism {} selected for duplication: {} owned cells, {} scan lines, 0 labels — skipping",
                    child.getBirthTick(), childId, owned.size(), scanLineMap.size());
            return; // no labels found
        }

        int selectedLabelPerpKey = labelState[0];
        int selectedLabelDvCoord = labelState[1];

        // --- Step 3: Scan ALL scan lines for NOP areas ---
        int candidateCount = 0;
        for (ScanLineInfo line : scanLineMap.values()) {
            env.properties.flatIndexToCoordinates(line.sampleFlatIndex, coordBuffer);
            findBestNopRun(line, env, dvDimFinal, shape[dvDimFinal]);
            if (line.bestNopLength >= minNopSize) {
                candidateCount++;
            }
        }

        if (candidateCount == 0) {
            LOG.debug("tick={} Organism {} selected for duplication: {} labels, no scan line with NOP >= {} — skipping",
                    child.getBirthTick(), childId, labelCount, minNopSize);
            return;
        }

        // Pick a random candidate via reservoir sampling (zero allocation)
        ScanLineInfo targetLine = null;
        int seen = 0;
        for (ScanLineInfo line : scanLineMap.values()) {
            if (line.bestNopLength >= minNopSize) {
                seen++;
                if (random.nextInt(seen) == 0) {
                    targetLine = line;
                }
            }
        }

        // --- Step 4: Copy length (DV-direction-aware) ---
        ScanLineInfo labelLine = scanLineMap.get(selectedLabelPerpKey);
        if (labelLine == null) {
            return; // should not happen, defensive
        }

        int dvStep = dv[dvDimFinal];
        int shapeDvDim = shape[dvDimFinal];
        int availableSource;
        if (dvStep > 0) {
            availableSource = toroidalForwardDistance(selectedLabelDvCoord, labelLine.walkEnd, shapeDvDim);
        } else {
            availableSource = toroidalForwardDistance(labelLine.walkStart, selectedLabelDvCoord, shapeDvDim);
        }
        int copyLength = Math.min(availableSource, targetLine.bestNopLength);

        if (copyLength <= 0) {
            return;
        }

        // --- Step 5: Copy ---
        // Build source position from label's scan line
        env.properties.flatIndexToCoordinates(labelLine.sampleFlatIndex, sourcePos);
        sourcePos[dvDimFinal] = selectedLabelDvCoord;

        // Build target position from target scan line, adjusting start for DV direction
        env.properties.flatIndexToCoordinates(targetLine.sampleFlatIndex, targetPos);
        if (dvStep < 0) {
            targetPos[dvDimFinal] = (targetLine.bestNopStart + copyLength - 1) % shapeDvDim;
        } else {
            targetPos[dvDimFinal] = targetLine.bestNopStart;
        }

        for (int i = 0; i < copyLength; i++) {
            int srcFlatIdx = computeFlatIndex(sourcePos);
            int srcMoleculeInt = env.getMoleculeInt(srcFlatIdx);
            Molecule molecule = Molecule.fromInt(srcMoleculeInt);
            int ownerId = (srcMoleculeInt == 0) ? 0 : childId;
            env.setMolecule(molecule, ownerId, targetPos);

            // In-place advancement along DV
            sourcePos[dvDimFinal] += dvStep;
            targetPos[dvDimFinal] += dvStep;

            // Toroidal wrap
            if (sourcePos[dvDimFinal] >= shapeDvDim) {
                sourcePos[dvDimFinal] -= shapeDvDim;
            } else if (sourcePos[dvDimFinal] < 0) {
                sourcePos[dvDimFinal] += shapeDvDim;
            }
            if (targetPos[dvDimFinal] >= shapeDvDim) {
                targetPos[dvDimFinal] -= shapeDvDim;
            } else if (targetPos[dvDimFinal] < 0) {
                targetPos[dvDimFinal] += shapeDvDim;
            }
        }

        if (LOG.isDebugEnabled()) {
            env.properties.flatIndexToCoordinates(labelLine.sampleFlatIndex, sourcePos);
            sourcePos[dvDimFinal] = selectedLabelDvCoord;
            env.properties.flatIndexToCoordinates(targetLine.sampleFlatIndex, targetPos);
            targetPos[dvDimFinal] = (dvStep < 0)
                    ? (targetLine.bestNopStart + copyLength - 1) % shapeDvDim
                    : targetLine.bestNopStart;
            LOG.debug("tick={} Organism {} gene duplication: copied {} molecules from {} to {}",
                    child.getBirthTick(), childId, copyLength, Arrays.toString(sourcePos), Arrays.toString(targetPos));
        }
    }

    /**
     * Ensures reusable buffers are initialized for the given dimensionality.
     *
     * @param dims Number of dimensions.
     */
    private void ensureBuffers(int dims) {
        if (coordBuffer == null || coordBuffer.length != dims) {
            coordBuffer = new int[dims];
            sourcePos = new int[dims];
            targetPos = new int[dims];
            strides = new int[dims];
            perpStrides = new int[dims];
        }
    }

    /**
     * Computes row-major strides from the world shape.
     *
     * @param shape The world shape array.
     * @param dims Number of dimensions.
     */
    private void computeStrides(int[] shape, int dims) {
        strides[dims - 1] = 1;
        for (int i = dims - 2; i >= 0; i--) {
            strides[i] = strides[i + 1] * shape[i + 1];
        }
    }

    /**
     * Computes strides for the perpendicular key calculation, excluding the DV dimension.
     *
     * @param shape The world shape array.
     * @param dims Number of dimensions.
     * @param dvDim The DV dimension index.
     */
    private void computePerpStrides(int[] shape, int dims, int dvDim) {
        int stride = 1;
        for (int i = dims - 1; i >= 0; i--) {
            if (i != dvDim) {
                perpStrides[i] = stride;
                stride *= shape[i];
            } else {
                perpStrides[i] = 0;
            }
        }
    }

    /**
     * Computes a unique perpendicular key from coordinates, excluding the DV dimension.
     *
     * @param coord The coordinate array.
     * @param dvDim The DV dimension index to exclude.
     * @return A unique integer key for the perpendicular coordinate combination.
     */
    private int computePerpKey(int[] coord, int dvDim) {
        int key = 0;
        for (int i = 0; i < coord.length; i++) {
            key += coord[i] * perpStrides[i];
        }
        return key;
    }

    /**
     * Computes a flat index from coordinates using pre-computed strides.
     *
     * @param coord The coordinate array.
     * @return The flat index.
     */
    private int computeFlatIndex(int[] coord) {
        int index = 0;
        for (int i = 0; i < coord.length; i++) {
            index += coord[i] * strides[i];
        }
        return index;
    }

    /**
     * Scans a scan line for the largest contiguous run of empty cells (CODE:0, marker:0).
     * Results are stored in the ScanLineInfo's bestNopStart/bestNopLength fields.
     * <p>
     * Walks along the scan line's shortest arc ({@link ScanLineInfo#walkStart} to
     * {@link ScanLineInfo#walkEnd}), correctly handling toroidal wrapping.
     * Uses the shared coordBuffer (caller must have initialized it via flatIndexToCoordinates
     * with the scan line's sampleFlatIndex before calling).
     *
     * @param line The scan line to scan.
     * @param env The simulation environment.
     * @param dvDim The DV dimension index.
     * @param shapeDvDim The environment size along the DV dimension.
     */
    private void findBestNopRun(ScanLineInfo line, Environment env, int dvDim, int shapeDvDim) {
        int nopRunStart = -1;
        int nopRunLength = 0;
        line.bestNopStart = -1;
        line.bestNopLength = 0;

        int arcLength = (line.walkEnd >= line.walkStart)
                ? line.walkEnd - line.walkStart + 1
                : shapeDvDim - line.walkStart + line.walkEnd + 1;

        int dvPos = line.walkStart;
        for (int step = 0; step < arcLength; step++) {
            coordBuffer[dvDim] = dvPos;
            int flatIdx = computeFlatIndex(coordBuffer);
            int moleculeInt = env.getMoleculeInt(flatIdx);

            if (moleculeInt == 0) {
                if (nopRunStart == -1) {
                    nopRunStart = dvPos;
                }
                nopRunLength++;
            } else {
                if (nopRunLength > line.bestNopLength) {
                    line.bestNopStart = nopRunStart;
                    line.bestNopLength = nopRunLength;
                }
                nopRunLength = 0;
                nopRunStart = -1;
            }

            dvPos++;
            if (dvPos >= shapeDvDim) dvPos = 0;
        }
        if (nopRunLength > line.bestNopLength) {
            line.bestNopStart = nopRunStart;
            line.bestNopLength = nopRunLength;
        }
    }

    /**
     * Computes the shortest toroidal arc for each scan line's walk range.
     * <p>
     * For scan lines where the raw span (maxDv - minDv + 1) does not exceed half the axis size,
     * the shortest arc is trivially minDv to maxDv. For scan lines that span more than half the
     * axis, the organism wraps around the world boundary. In that case, this method collects the
     * DV coordinates of owned cells on that scan line, sorts them, and finds the largest gap to
     * determine the correct shortest arc.
     *
     * @param owned The child's owned cell set.
     * @param env The simulation environment.
     * @param dvDim The DV dimension index.
     * @param shapeDvDim The environment size along the DV dimension.
     */
    private void resolveWalkRanges(IntOpenHashSet owned, Environment env, int dvDim, int shapeDvDim) {
        boolean anyWrapping = false;
        for (ScanLineInfo line : scanLineMap.values()) {
            line.walkStart = line.minDv;
            line.walkEnd = line.maxDv;
            if (line.maxDv - line.minDv + 1 >= shapeDvDim - 1) {
                anyWrapping = true;
            }
        }

        if (!anyWrapping) {
            return;
        }

        final int dvDimF = dvDim;
        for (var entry : scanLineMap.int2ObjectEntrySet()) {
            ScanLineInfo line = entry.getValue();
            if (line.maxDv - line.minDv + 1 <= shapeDvDim / 2) {
                continue;
            }

            int perpKey = entry.getIntKey();
            ensureDvCollector(line.count);
            final int targetPK = perpKey;
            final int[] idx = {0};

            owned.forEach((int flatIndex) -> {
                env.properties.flatIndexToCoordinates(flatIndex, coordBuffer);
                if (computePerpKey(coordBuffer, dvDimF) == targetPK) {
                    dvCoordCollector[idx[0]++] = coordBuffer[dvDimF];
                }
            });

            int count = idx[0];
            Arrays.sort(dvCoordCollector, 0, count);

            int largestGap = 0;
            int gapAfterIdx = 0;
            for (int i = 1; i < count; i++) {
                int gap = dvCoordCollector[i] - dvCoordCollector[i - 1];
                if (gap > largestGap) {
                    largestGap = gap;
                    gapAfterIdx = i;
                }
            }

            int wrapGap = dvCoordCollector[0] + shapeDvDim - dvCoordCollector[count - 1];
            if (wrapGap > largestGap) {
                gapAfterIdx = 0;
            }

            line.walkStart = dvCoordCollector[gapAfterIdx];
            line.walkEnd = dvCoordCollector[(gapAfterIdx - 1 + count) % count];
        }
    }

    /**
     * Ensures the DV coordinate collector buffer has sufficient capacity.
     *
     * @param capacity Required minimum capacity.
     */
    private void ensureDvCollector(int capacity) {
        if (dvCoordCollector == null || dvCoordCollector.length < capacity) {
            dvCoordCollector = new int[capacity];
        }
    }

    /**
     * Computes the number of cells from {@code from} to {@code to} going in the positive
     * direction on a toroidal axis, inclusive of both endpoints.
     *
     * @param from Start coordinate.
     * @param to End coordinate.
     * @param axisSize Size of the toroidal axis.
     * @return The forward distance including both endpoints.
     */
    private static int toroidalForwardDistance(int from, int to, int axisSize) {
        int d = to - from;
        if (d < 0) d += axisSize;
        return d + 1;
    }

    /**
     * Acquires a ScanLineInfo from the pool, or creates a new one if the pool is exhausted.
     * After warmup (first few ticks), this method never allocates.
     *
     * @return A reusable ScanLineInfo instance.
     */
    private ScanLineInfo acquireFromPool() {
        if (poolIndex < scanLinePool.size()) {
            return scanLinePool.get(poolIndex++);
        }
        ScanLineInfo info = new ScanLineInfo();
        scanLinePool.add(info);
        poolIndex++;
        return info;
    }

    /** {@inheritDoc} */
    @Override
    public byte[] saveState() {
        return new byte[0];
    }

    /** {@inheritDoc} */
    @Override
    public void loadState(byte[] state) {
        // Stateless plugin - nothing to restore
    }
}
