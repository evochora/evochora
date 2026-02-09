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
         * Initialize scan-line bounds, sample index, and NOP-run tracking for a newly started scan line.
         *
         * @param dvCoord    the DV-dimension coordinate of the first cell encountered on the line
         * @param flatIndex  the flat (row-major) index corresponding to that first cell
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
     * Initialize the plugin with a randomness source and configuration.
     *
     * @param randomProvider source of randomness used for selection and sampling
     * @param config configuration containing the keys:
     *               - "duplicationRate": probability (0.0–1.0) of performing duplication on a newborn
     *               - "minNopSize": minimum length of a contiguous NOP (empty) region required as a duplication target
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

    /**
     * Possibly duplicates a gene block into a newborn organism with probability configured by this plugin.
     *
     * <p>If the random draw meets the configured duplication probability, performs the duplication operation
     * for the provided child within the given environment.</p>
     *
     * @param child the newborn organism that may receive duplicated genetic material
     * @param environment the environment providing world state and accessors required to perform duplication
     */
    @Override
    public void onBirth(Organism child, Environment environment) {
        if (random.nextDouble() >= duplicationRate) {
            return;
        }
        duplicate(child, environment);
    }

    /**
     * Copies a code block from a LABEL owned by the newborn into an available NOP region on one of
     * the newborn's scan lines, performing a single gene-duplication operation when a suitable
     * source label and target NOP run exist.
     *
     * @param child the newborn organism whose code may be duplicated
     * @param env the simulation environment used to read and modify world state
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
     * Compute row-major strides and store them in the instance `strides` array.
     *
     * The computed strides map multi-dimensional coordinates to flat indices for a
     * row-major layout: stride[i] is the product of shape[i+1]..shape[dims-1].
     *
     * @param shape the length of each dimension (must have at least `dims` entries)
     * @param dims  the number of dimensions to compute strides for
     */
    private void computeStrides(int[] shape, int dims) {
        strides[dims - 1] = 1;
        for (int i = dims - 2; i >= 0; i--) {
            strides[i] = strides[i + 1] * shape[i + 1];
        }
    }

    /**
     * Initialize the perpStrides array so each index (except the DV dimension) holds the multiplier
     * used to compute a unique perpendicular key across all dimensions except `dvDim`.
     *
     * @param shape the length of each world dimension
     * @param dims the number of dimensions in `shape`
     * @param dvDim the index of the DV dimension to exclude (its stride is set to 0)
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
     * Compute a unique integer key for the coordinates projected onto the subspace
     * excluding the DV dimension.
     *
     * @param coord the coordinate array
     * @param dvDim the DV dimension index (ignored by this method; perpStrides already encodes the exclusion)
     * @return an integer key representing the perpendicular coordinates (all dimensions except `dvDim`)
     */
    private int computePerpKey(int[] coord, int dvDim) {
        int key = 0;
        for (int i = 0; i < coord.length; i++) {
            key += coord[i] * perpStrides[i];
        }
        return key;
    }

    /**
     * Convert multi-dimensional coordinates to a flat (row-major) index using precomputed strides.
     *
     * @param coord the coordinate vector for each dimension
     * @return the flat array index corresponding to the given coordinates
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
     * Finds the largest contiguous run of empty cells (molecule code 0) along the scan line's DV range
     * and stores its start and length in the provided ScanLineInfo.
     *
     * The search covers DV coordinates from line.minDv to line.maxDv inclusive. Caller must have
     * populated coordBuffer with the perpendicular coordinates for this scan line (for example by
     * converting line.sampleFlatIndex to coordinates) before calling; this method will overwrite the
     * value at index dvDim as it iterates.
     *
     * @param line  the ScanLineInfo whose DV bounds will be scanned and whose bestNopStart/bestNopLength
     *              fields will be updated with the largest empty run found
     * @param env   the environment used to read molecules by flat index
     * @param dvDim the index of the DV dimension within coordBuffer that this method will advance
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

    /**
     * Provide the plugin's persisted state for snapshotting.
     *
     * @return an empty byte array because this plugin maintains no persistent state
     */
    @Override
    public byte[] saveState() {
        return new byte[0];
    }

    /**
     * Restore plugin state from a previously saved byte array; this plugin is stateless so the input is ignored.
     *
     * @param state the saved state previously returned by {@code saveState()}, ignored by this implementation
     */
    @Override
    public void loadState(byte[] state) {
        // Stateless plugin - nothing to restore
    }
}