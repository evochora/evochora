package org.evochora.runtime.worldgen;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IRandomProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Random;

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
            LOG.debug("Organism {} selected for duplication but has no owned cells", childId);
            return;
        }

        int[] dv = child.getDv();
        int dims = env.getShape().length;
        int[] shape = env.getShape();

        // Find the DV dimension (first non-zero component)
        int dvDim = -1;
        for (int i = 0; i < dims; i++) {
            if (dv[i] != 0) {
                dvDim = i;
                break;
            }
        }
        if (dvDim == -1) {
            return; // degenerate DV
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

        int labelCount = labelState[2];
        if (labelCount == 0) {
            LOG.debug("Organism {} selected for duplication: {} owned cells, {} scan lines, 0 labels — skipping",
                    childId, owned.size(), scanLineMap.size());
            return; // no labels found
        }

        int selectedLabelPerpKey = labelState[0];
        int selectedLabelDvCoord = labelState[1];

        // --- Step 3: Scan ALL scan lines for NOP areas ---
        int candidateCount = 0;
        for (ScanLineInfo line : scanLineMap.values()) {
            env.properties.flatIndexToCoordinates(line.sampleFlatIndex, coordBuffer);
            findBestNopRun(line, env, dvDimFinal);
            if (line.bestNopLength >= minNopSize) {
                candidateCount++;
            }
        }

        if (candidateCount == 0) {
            LOG.debug("Organism {} selected for duplication: {} labels, no scan line with NOP >= {} — skipping",
                    childId, labelCount, minNopSize);
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

        // --- Step 4: Copy length ---
        ScanLineInfo labelLine = scanLineMap.get(selectedLabelPerpKey);
        if (labelLine == null) {
            return; // should not happen, defensive
        }
        int availableSource = labelLine.maxDv - selectedLabelDvCoord + 1;
        int copyLength = Math.min(availableSource, targetLine.bestNopLength);

        if (copyLength <= 0) {
            return;
        }

        // --- Step 5: Copy ---
        // Build source position from label's scan line
        env.properties.flatIndexToCoordinates(labelLine.sampleFlatIndex, sourcePos);
        sourcePos[dvDimFinal] = selectedLabelDvCoord;

        // Build target position from target scan line
        env.properties.flatIndexToCoordinates(targetLine.sampleFlatIndex, targetPos);
        targetPos[dvDimFinal] = targetLine.bestNopStart;

        int dvStep = dv[dvDimFinal];

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
            if (sourcePos[dvDimFinal] >= shape[dvDimFinal]) {
                sourcePos[dvDimFinal] -= shape[dvDimFinal];
            } else if (sourcePos[dvDimFinal] < 0) {
                sourcePos[dvDimFinal] += shape[dvDimFinal];
            }
            if (targetPos[dvDimFinal] >= shape[dvDimFinal]) {
                targetPos[dvDimFinal] -= shape[dvDimFinal];
            } else if (targetPos[dvDimFinal] < 0) {
                targetPos[dvDimFinal] += shape[dvDimFinal];
            }
        }

        LOG.debug("Organism {} gene duplication: copied {} molecules from label at dvCoord={} to NOP area at dvCoord={}",
                childId, copyLength, selectedLabelDvCoord, targetLine.bestNopStart);
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
     * Acquires a ScanLineInfo from the pool, or creates a new one if the pool is exhausted.
     * After warmup (first few ticks), this method never allocates.
     *
     * @return A reusable ScanLineInfo instance.
     */
    /**
     * Scans a scan line for the largest contiguous run of empty cells (CODE:0, marker:0).
     * Results are stored in the ScanLineInfo's bestNopStart/bestNopLength fields.
     * Uses the shared coordBuffer (caller must have initialized it via flatIndexToCoordinates
     * with the scan line's sampleFlatIndex before calling).
     *
     * @param line The scan line to scan.
     * @param env The simulation environment.
     * @param dvDim The DV dimension index.
     */
    private void findBestNopRun(ScanLineInfo line, Environment env, int dvDim) {
        int nopRunStart = -1;
        int nopRunLength = 0;
        line.bestNopStart = -1;
        line.bestNopLength = 0;

        for (int dvPos = line.minDv; dvPos <= line.maxDv; dvPos++) {
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
        }
        if (nopRunLength > line.bestNopLength) {
            line.bestNopStart = nopRunStart;
            line.bestNopLength = nopRunLength;
        }
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

    @Override
    public byte[] saveState() {
        return new byte[0];
    }

    @Override
    public void loadState(byte[] state) {
        // Stateless plugin - nothing to restore
    }
}
