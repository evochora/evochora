package org.evochora.runtime.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.GenomeFrame;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MutationRecord;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.ScanLineArc;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IRandomProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * Gene duplication birth handler inspired by Ohno's (1970) model of evolution through gene duplication.
 * <p>
 * Called once per newborn organism in the post-Execute phase of each tick. With configurable probability,
 * copies whole blocks starting at a randomly selected LABEL into an empty (CODE:0) region within the
 * organism's body. The duplicated blocks are immediately neutral (redundant) but provide raw material
 * for later divergence through point mutation.
 * <p>
 * <strong>What a block is:</strong> the stretch from one of the newborn's LABEL cells along the
 * direction vector to the next of its LABEL cells on the same scan line, or, where no further label
 * follows, to the end of the newborn's extent on that line. A LABEL cell opens a block only where it
 * is an entry point in the machine's reading frame ({@link GenomeFrame}); a LABEL molecule standing
 * in another instruction's operand list is that instruction's argument and opens no block.
 * <p>
 * <strong>What a copy holds:</strong> whole blocks only. The copy starts at the chosen label and
 * takes as many consecutive blocks as the target's empty run holds. Where the rest of the source
 * line fits into that run, it is copied as it stands, a trailing instruction included; otherwise
 * the copy is cut back to the last block boundary that fits entirely, never inside a block. A run
 * that cannot hold even the first block is not written to at all.
 * <p>
 * What a copy carries of those blocks is what the machine reads as code: the cells of an
 * instruction, the labels that open a block, and the empty cells between them. A CODE molecule
 * whose value is no registered opcode is none: the machine reads it as a no-operation of one cell,
 * and the cells behind it are no operands. Every other
 * occupied cell is written as an empty cell instead. A line's extent ends at the outermost cell the
 * organism owns there, which for a body with a shell is a shell cell, and data a program keeps
 * between its instructions is no gene either; carried into the middle of a body, such a cell is
 * read as code by whoever reads that body afterwards. The empty cell left in its place is where a
 * later insertion can write. Cells at the end of a copy that would be written empty are not copied
 * at all, so that a copy asks for no room it does not use.
 * <p>
 * <strong>The reading frame</strong> is built once per duplication, for the newborn whose body is
 * copied. It is meant to serve more than this handler: as soon as a second birth handler needs to
 * know the code structure of a body, it should be built once per birth and handed to the handlers
 * instead, so that they share one answer rather than each paying for its own.
 * <p>
 * The algorithm groups owned cells by scan lines perpendicular to the organism's direction vector (DV),
 * ensuring equal selection probability for each scan line regardless of cell density. A random scan line
 * is chosen as the target for NOP area search, and a random LABEL is selected as the source via reservoir
 * sampling.
 * <p>
 * <strong>Performance:</strong> Near-zero allocation after warmup. The owned cells are visited
 * through the environment's cell views; reusable coordinate buffers, ScanLineInfo pooling and direct
 * bit extraction from packed molecule ints minimize GC pressure. The only per-call allocations are
 * one {@code getShape()} defensive copy, the two visitor lambdas (one per owned-cell pass), when a
 * copy is applied the {@link MutationRecord} handed to the newborn, and, only where a copy has to
 * be cut back to a block boundary, the defensive copy of the newborn's initial position together
 * with what one {@link GenomeFrame} build costs. The owned-cell iteration is O(n) where n is
 * typically 1000-3000, running at most a few times per tick.
 * <p>
 * <strong>What it records:</strong> an applied duplication reports itself on the newborn as a
 * {@link MutationRecord} of kind {@code "duplication"}. Its cells are the target cells that
 * received a non-empty molecule, in the order the copy loop wrote them; the old value is what
 * stood at the target cell before the write, the new value the copied molecule. Its one parameter
 * is the flat index of the first source cell, which is the chosen label, because the source of a
 * copy cannot be recovered from the copied values alone. A run that finds no label, no NOP run
 * long enough, or no run that holds the first block writes nothing and records nothing.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Runs in the sequential post-Execute phase of
 * {@code Simulation.tick()}.
 *
 * @see org.evochora.runtime.spi.IBirthHandler
 */
public class GeneDuplicationPlugin implements IBirthHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GeneDuplicationPlugin.class);

    /** The kind this plugin reports its writes under. */
    private static final String MUTATION_KIND = "duplication";

    private final Random random;
    private final double duplicationRate;
    private final int minNopSize;

    // Reusable buffers (lazy-initialized on first duplicate() call)
    private int[] coordBuffer;
    private int[] sourcePos;
    private int[] targetPos;
    private int[] perpStrides;

    // Scan line map and pool (reused across duplicate() calls)
    /**
     * Finds a scan line by its perpendicular key. Only for lookups: the map is reused across births
     * and keeps the table size it once grew to, so its iteration order depends on the bodies this
     * instance processed before, and a choice made in that order would differ between a run and
     * its resumed or forked continuation.
     */
    private final Int2ObjectOpenHashMap<ScanLineInfo> scanLineMap = new Int2ObjectOpenHashMap<>();
    /**
     * The scan lines of the current newborn at indices {@code 0} to {@code poolIndex - 1}, in the
     * order in which the flat-index visit of its cells first reached them - an order set by the
     * body alone. Every pass over the scan lines runs in this order.
     */
    private final ArrayList<ScanLineInfo> scanLinePool = new ArrayList<>();
    private int poolIndex;

    // DV coordinate collector for arc resolution (reused)
    private int[] dvCoordCollector;

    /** Receives the ends of a scan line's arc; reused so that resolving a line allocates nothing. */
    private final ScanLineArc.Result arc = new ScanLineArc.Result();

    /** Collects the record of a copy; reused so that a birth allocates only the record itself. */
    private final MutationRecord.Builder recordBuilder = new MutationRecord.Builder();

    /**
     * The newborn's genome in the machine's reading frame; built only where a copy has to be cut
     * back to a block boundary, and kept so that such a build reuses the buffers of the one before.
     */
    private final GenomeFrame frame = new GenomeFrame();

    /**
     * Mutable scan line info for grouping owned cells by perpendicular coordinates.
     * Pooled and reused across duplicate() calls to avoid allocation.
     */
    static class ScanLineInfo {
        /** Minimum DV-dimension coordinate on this scan line. */
        int minDv;
        /** Maximum DV-dimension coordinate on this scan line. */
        int maxDv;
        /** The flat index of one owned cell on this scan line, for coordinate reconstruction. */
        int sampleFlatIndex;
        /** Number of owned cells on this scan line. */
        int count;
        /** Start of the arc this line's owned cells span (inclusive), see {@link ScanLineArc}. */
        int walkStart;
        /** End of the arc this line's owned cells span (inclusive), see {@link ScanLineArc}. */
        int walkEnd;
        /** Start of this line's segment in the shared DV coordinate buffer while walk ranges are resolved. */
        int segmentStart;
        /** Number of DV coordinates already placed in this line's segment. */
        int segmentFill;
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
     * @param minNopSize Minimum contiguous empty cells required as duplication target; a run that
     *                   does not hold the first whole block is left unwritten however long it is.
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
     * finds the largest NOP area on a random scan line, and copies the blocks starting at the label
     * into the NOP area, as many whole blocks as that area holds. A copy that is applied is
     * recorded on the child.
     *
     * @param child The newborn organism.
     * @param env The simulation environment.
     */
    void duplicate(Organism child, Environment env) {
        int childId = child.getId();
        if (env.countCellsOwnedBy(childId) == 0) {
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
        computePerpStrides(shape, dims, dvDim);

        // --- Step 2: Group owned cells by scan line + reservoir sample a label ---
        scanLineMap.clear();
        poolIndex = 0;

        // Reservoir sampling state for label selection
        final int[] labelState = new int[3]; // [0]=perpKey, [1]=dvCoord, [2]=labelCount
        labelState[2] = 0; // labelCount

        final int dvDimFinal = dvDim;

        // The visit runs in flat-index order: the reservoir choice below must not depend on write history
        env.visitCellsOwnedBy(childId, cell -> {
            System.arraycopy(cell.coordinate(), 0, coordBuffer, 0, coordBuffer.length);

            int perpKey = computePerpKey(coordBuffer, dvDimFinal);
            int dvCoord = coordBuffer[dvDimFinal];

            ScanLineInfo scanLine = scanLineMap.get(perpKey);
            if (scanLine == null) {
                scanLine = acquireFromPool();
                scanLine.reset(dvCoord, env.properties.toFlatIndex(coordBuffer));
                scanLineMap.put(perpKey, scanLine);
            } else {
                scanLine.update(dvCoord);
            }

            // Reservoir sampling for label selection
            int moleculeInt = cell.moleculeInt();
            if ((moleculeInt & Config.TYPE_MASK) == Config.TYPE_LABEL) {
                labelState[2]++;
                if (random.nextInt(labelState[2]) == 0) {
                    labelState[0] = perpKey;
                    labelState[1] = dvCoord;
                }
            }
        });

        resolveWalkRanges(childId, env, dvDimFinal, shape[dvDimFinal]);

        int labelCount = labelState[2];
        if (labelCount == 0) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("tick={} Organism {} selected for duplication: {} owned cells, {} scan lines, 0 labels — skipping",
                        child.getBirthTick(), childId, env.countCellsOwnedBy(childId), scanLineMap.size());
            }
            return; // no labels found
        }

        int selectedLabelPerpKey = labelState[0];
        int selectedLabelDvCoord = labelState[1];

        // --- Step 3: Scan ALL scan lines for NOP areas ---
        int candidateCount = 0;
        for (int i = 0; i < poolIndex; i++) {
            ScanLineInfo line = scanLinePool.get(i);
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
        for (int i = 0; i < poolIndex; i++) {
            ScanLineInfo line = scanLinePool.get(i);
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
        // Build source position from label's scan line
        env.properties.flatIndexToCoordinates(labelLine.sampleFlatIndex, sourcePos);
        sourcePos[dvDimFinal] = selectedLabelDvCoord;

        int room = targetLine.bestNopLength;
        // The reading frame says of every source cell whether the machine reads it as part of an
        // instruction, which decides what the copy carries, and which LABEL cells are the block
        // boundaries a cut-back copy may end at.
        frame.build(env, childId, child.getInitialPosition(), dv);
        int copyLength;
        if (availableSource <= room) {
            copyLength = availableSource;
        } else {
            // The room ends inside the source, so the copy is cut back to a whole number of blocks
            copyLength = lastBlockBoundaryWithin(env, childId, room, dvStep, dvDimFinal, shapeDvDim);
            sourcePos[dvDimFinal] = selectedLabelDvCoord;
            if (copyLength == 0) {
                LOG.debug("tick={} Organism {} selected for duplication: NOP run of {} cells does not hold the first block — skipping",
                        child.getBirthTick(), childId, room);
                return;
            }
        }

        // Cells the copy would write as empty carry nothing, so the copy does not reserve room for
        // them: cutting them off lets a copy fit that would otherwise be cut back or skipped.
        copyLength = withoutTrailingEmptyCells(env, copyLength, dvStep, dvDimFinal, shapeDvDim);
        sourcePos[dvDimFinal] = selectedLabelDvCoord;
        if (copyLength == 0) {
            LOG.debug("tick={} Organism {} selected for duplication: the source holds no code to copy — skipping",
                    child.getBirthTick(), childId);
            return;
        }

        // --- Step 5: Copy ---
        // Build target position from target scan line, adjusting start for DV direction
        env.properties.flatIndexToCoordinates(targetLine.sampleFlatIndex, targetPos);
        if (dvStep < 0) {
            targetPos[dvDimFinal] = (targetLine.bestNopStart + copyLength - 1) % shapeDvDim;
        } else {
            targetPos[dvDimFinal] = targetLine.bestNopStart;
        }

        // The source walk starts at the chosen label, which the copy cannot recover from its values
        recordBuilder.start(getClass().getName(), MUTATION_KIND, dv)
                .param(env.properties.toFlatIndex(sourcePos));

        for (int i = 0; i < copyLength; i++) {
            int srcMoleculeInt = carriedByACopy(env, sourcePos) ? env.getMoleculeIntAt(sourcePos) : 0;
            Molecule molecule = Molecule.fromInt(srcMoleculeInt);
            int ownerId = (srcMoleculeInt == 0) ? 0 : childId;
            if (srcMoleculeInt != 0) {
                recordBuilder.cell(env.properties.toFlatIndex(targetPos),
                        env.getMoleculeIntAt(targetPos), molecule.toInt());
            }
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

        child.recordBirthMutation(recordBuilder.build());

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
     * Whether a copy carries the cell at a position as it stands.
     * <p>
     * A copy carries what the machine reads as code: the cells of an instruction, the labels that
     * open a block, and the empty cells between them. Every other occupied cell — the organism's shell where a line
     * ends at it, data a program keeps between its instructions — is written as an empty cell
     * instead, because it is not a gene; the space it leaves is where a later insertion can write.
     *
     * @param env The environment the cell is read from.
     * @param position The cell's position.
     * @return {@code true} if the copy takes the molecule as it stands.
     */
    private boolean carriedByACopy(Environment env, int[] position) {
        int moleculeInt = env.getMoleculeIntAt(position);
        if (moleculeInt == 0) {
            return true;
        }
        if ((moleculeInt & Config.TYPE_MASK) == Config.TYPE_LABEL) {
            return true;
        }
        return frame.slot(env.properties.toFlatIndex(position)) != GenomeFrame.Slot.NONE;
    }

    /**
     * Shortens a copy so that it does not end in cells that would be written as empty ones.
     * <p>
     * Such cells carry nothing into the copy, and a copy that does not ask for room for them fits
     * into a shorter run of empty cells. Reads the source from the cell the caller has put into
     * {@code sourcePos} and leaves that buffer somewhere along the source.
     *
     * @param env The environment the source is read from.
     * @param copyLength The length the copy would have.
     * @param dvStep The step along the direction vector, 1 or -1.
     * @param dvDim The dimension the direction vector runs along.
     * @param shapeDvDim The size of the world along that dimension.
     * @return The length up to and including the last cell the copy carries, 0 if it carries none.
     */
    private int withoutTrailingEmptyCells(Environment env, int copyLength, int dvStep, int dvDim, int shapeDvDim) {
        int carried = 0;
        int dvPos = sourcePos[dvDim];
        for (int offset = 0; offset < copyLength; offset++) {
            sourcePos[dvDim] = dvPos;
            if (env.getMoleculeIntAt(sourcePos) != 0 && carriedByACopy(env, sourcePos)) {
                carried = offset + 1;
            }
            dvPos += dvStep;
            if (dvPos >= shapeDvDim) {
                dvPos -= shapeDvDim;
            } else if (dvPos < 0) {
                dvPos += shapeDvDim;
            }
        }
        return carried;
    }

    /**
     * Reports how many cells of the source hold whole blocks and fit into the target's room.
     * <p>
     * The source is walked from the chosen label, whose coordinates the caller has put into the
     * source buffer, along the direction vector up to and including the first cell past the room,
     * because a block that ends exactly where the room ends has its successor's label there. The
     * answer is the offset of the last block start found on the way, which is the length of the
     * stretch of whole blocks before it, and zero where the room does not even hold the first block. A block
     * start is a LABEL cell the newborn owns that the reading frame does not read as part of an
     * instruction; a LABEL cell standing in an operand list is read as that operand and passed over.
     * <p>
     * The walk leaves the source buffer standing on the last cell it looked at; the caller restores
     * it to the chosen label.
     *
     * @param env The simulation environment.
     * @param childId The newborn whose cells and whose reading frame the blocks belong to.
     * @param room The number of cells the target's empty run offers.
     * @param dvStep The step along the DV dimension, {@code +1} or {@code -1}.
     * @param dvDim The DV dimension index.
     * @param shapeDvDim The environment size along the DV dimension.
     * @return The number of cells to copy, or {@code 0} if no block fits.
     */
    private int lastBlockBoundaryWithin(Environment env, int childId, int room, int dvStep, int dvDim, int shapeDvDim) {
        int boundary = 0;
        int dvPos = sourcePos[dvDim];
        for (int offset = 1; offset <= room; offset++) {
            dvPos += dvStep;
            if (dvPos >= shapeDvDim) {
                dvPos -= shapeDvDim;
            } else if (dvPos < 0) {
                dvPos += shapeDvDim;
            }
            sourcePos[dvDim] = dvPos;
            if ((env.getMoleculeIntAt(sourcePos) & Config.TYPE_MASK) != Config.TYPE_LABEL) {
                continue;
            }
            if (env.getOwnerIdAt(sourcePos) != childId) {
                continue;
            }
            if (frame.slot(env.properties.toFlatIndex(sourcePos)) == GenomeFrame.Slot.NONE) {
                boundary = offset;
            }
        }
        return boundary;
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
            perpStrides = new int[dims];
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
     * Scans a scan line for the largest contiguous run of empty cells (CODE:0, marker:0).
     * Results are stored in the ScanLineInfo's bestNopStart/bestNopLength fields.
     * <p>
     * Walks along the scan line's arc ({@link ScanLineInfo#walkStart} to
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
            int moleculeInt = env.getMoleculeIntAt(coordBuffer);

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
     * Determines the walk range of each scan line, the arc the newborn spans on that line.
     * <p>
     * A line whose owned cells cannot reach around the world edge spans the arc from its smallest
     * to its largest DV coordinate, which the grouping pass already knows. Only a line that can
     * reach around it needs the coordinates in between: for those this method collects the DV
     * coordinates of the owned cells, sorts them and hands them to {@link ScanLineArc}, which
     * decides where the body ends and the outside begins.
     *
     * @param childId The newborn whose owned cells were grouped into scan lines.
     * @param env The simulation environment.
     * @param dvDim The DV dimension index.
     * @param shapeDvDim The environment size along the DV dimension.
     */
    private void resolveWalkRanges(int childId, Environment env, int dvDim, int shapeDvDim) {
        boolean toroidal = env.properties.isToroidal();
        boolean anyWrapping = false;
        for (int i = 0; i < poolIndex; i++) {
            ScanLineInfo line = scanLinePool.get(i);
            line.walkStart = line.minDv;
            line.walkEnd = line.maxDv;
            if (ScanLineArc.largestGapRuleApplies(line.minDv, line.maxDv, shapeDvDim, toroidal)) {
                anyWrapping = true;
            }
        }

        if (!anyWrapping) {
            return;
        }

        // One pass over the child's cells groups the DV coordinates by scan line: every line owns
        // a segment of one shared buffer, starting at its offset, sized by its cell count.
        int total = 0;
        for (int i = 0; i < poolIndex; i++) {
            ScanLineInfo line = scanLinePool.get(i);
            line.segmentStart = total;
            line.segmentFill = 0;
            total += line.count;
        }
        ensureDvCollector(total);
        final int dvDimF = dvDim;
        env.visitCellsOwnedBy(childId, cell -> {
            System.arraycopy(cell.coordinate(), 0, coordBuffer, 0, coordBuffer.length);
            ScanLineInfo line = scanLineMap.get(computePerpKey(coordBuffer, dvDimF));
            dvCoordCollector[line.segmentStart + line.segmentFill++] = coordBuffer[dvDimF];
        });

        for (int i = 0; i < poolIndex; i++) {
            ScanLineInfo line = scanLinePool.get(i);
            if (!ScanLineArc.largestGapRuleApplies(line.minDv, line.maxDv, shapeDvDim, toroidal)) {
                continue;
            }
            int from = line.segmentStart;
            int count = line.count;
            Arrays.sort(dvCoordCollector, from, from + count);
            ScanLineArc.resolve(dvCoordCollector, from, count, shapeDvDim, toroidal, arc);
            line.walkStart = arc.start;
            line.walkEnd = arc.end;
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
