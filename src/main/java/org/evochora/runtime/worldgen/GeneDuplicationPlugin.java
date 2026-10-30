package org.evochora.runtime.worldgen;

import java.util.Arrays;
import java.util.Random;

import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.GenomeFlow;
import org.evochora.runtime.model.GenomeFrame;
import org.evochora.runtime.model.GenomeLines;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MutationRecord;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IRandomProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Gene duplication birth handler inspired by Ohno's (1970) model of evolution through gene duplication.
 * <p>
 * Called once per newborn organism in the post-Execute phase of each tick. With configurable probability,
 * copies whole blocks starting at a randomly selected LABEL into an empty (CODE:0) region within the
 * organism's body. The duplicated blocks are immediately neutral (redundant) but provide raw material
 * for later divergence through point mutation.
 * <p>
 * <strong>What a block is:</strong> the stretch from one of the newborn's LABEL cells along the
 * direction vector to the next of its LABEL cells on the same line, or, where no further label
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
 * <strong>How a cut-back copy ends.</strong> The source runs on from the last copied block into the
 * label the copy was cut at; the copy has nothing behind it. Where execution can leave the copied
 * stretch at its end ({@link GenomeFlow#endsOpen}) — its last instruction is no unconditional jump
 * and no return, or a conditional can skip it — the copy is closed with a {@code JMPI} to the value
 * of that label, so that it goes on where the source goes on. The jump takes two cells of the run;
 * where they are missing the copy is cut back further, to the last boundary that leaves them. A
 * copy that reaches the end of the source line is left as it is: the source has nothing behind it
 * there either.
 * <p>
 * <strong>Where a copy goes.</strong> Only into a run of empty cells execution does not run on into
 * ({@link GenomeFlow#reachedByFallThrough}). In a run other code passes through, that code would
 * enter the copy at its first label and leave with the copy's jumps, whatever was copied.
 * <p>
 * <strong>Labels that are no jump targets</strong> are copied like any other. A label a location
 * instruction addresses names a place for the data pointer; its copy is a second such place, and
 * the references to it are shared between the two by the label match. The original stays where it
 * is and keeps its share, so what this does to an organism depends on what its program keeps
 * there — it is left to selection, as the content of every copy is.
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
 * <strong>The lines and the reading frame</strong> are built once per duplication, for the
 * newborn whose body is copied. Every birth handler that reads a body's code structure builds its
 * own, because each handler that writes changes the grid the next one reads.
 * <p>
 * The newborn's cells are grouped into the lines its genome lies on along the direction vector
 * ({@link GenomeLines}), so that each line has the same chance to be the target regardless of how
 * many cells it holds. A random line is chosen among the lines that hold a qualifying NOP run, and
 * a random LABEL is selected as the source via reservoir sampling.
 * <p>
 * <strong>Performance:</strong> Near-zero allocation after warmup. The owned cells are grouped
 * into lines by {@link GenomeLines}, whose tables survive a birth, and the reading frame is built on
 * those lines; reusable coordinate buffers and direct bit extraction from packed molecule ints
 * minimize GC pressure. The only per-call allocations are one {@code getShape()} defensive copy,
 * the copy of the newborn's direction vector, the visitors of the lines' owned-cell visits, when a
 * copy is applied the {@link MutationRecord} handed to the newborn, the defensive copy of the
 * newborn's initial position together with what one {@link GenomeFrame} build costs, and, only
 * where a copy is closed, the two {@link Molecule} records of the jump. The frame is built for
 * every newborn that carries a label, before it is known whether a run qualifies, because which
 * runs qualify is read from it. The owned-cell iteration is O(n) where n is typically 1000-3000,
 * running at most a few times per tick.
 * <p>
 * <strong>What it records:</strong> an applied duplication reports itself on the newborn as a
 * {@link MutationRecord} of kind {@code "duplication"}. Its cells are the target cells that
 * received a non-empty molecule, in the order the copy loop wrote them; the old value is what
 * stood at the target cell before the write, the new value the copied molecule; the two cells of
 * a closing jump follow the copied ones. Its one parameter
 * is the flat index of the first source cell, which is the chosen label, because the source of a
 * copy cannot be recovered from the copied values alone. A run that finds no label, no NOP run
 * that is long enough and away from execution, or no run that holds the first block writes nothing
 * and records nothing.
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

    /** The instruction a cut-back copy is closed with, an unconditional jump to a label value. */
    private static final String JUMP_INSTRUCTION = "JMPI";

    /** Cells a closing jump takes: its opcode and its label reference. */
    private static final int CLOSING_JUMP_LENGTH = 2;

    private final Random random;
    private final double duplicationRate;
    private final int minNopSize;

    /** Opcode of the jump a cut-back copy is closed with. */
    private final int jumpOpcodeId;

    // Reusable buffers (lazy-initialized on first duplicate() call)
    private int[] coordBuffer;
    private int[] sourcePos;
    private int[] targetPos;

    /** The lines of the newborn's genome, rebuilt for every duplication. */
    private final GenomeLines lines = new GenomeLines();

    /** Start of the largest NOP run per line (DV coordinate), or -1 if none was found. */
    private int[] bestNopStart = new int[0];

    /** Length of the largest NOP run per line, or 0 if none was found. */
    private int[] bestNopLength = new int[0];

    /** Collects the record of a copy; reused so that a birth allocates only the record itself. */
    private final MutationRecord.Builder recordBuilder = new MutationRecord.Builder();

    /**
     * The newborn's genome in the machine's reading frame, built once per duplication and kept so
     * that a build reuses the buffers of the one before.
     */
    private final GenomeFrame frame = new GenomeFrame();

    /** What the instruction set says about control flow in that genome. */
    private final GenomeFlow flow = new GenomeFlow();

    /** Coordinates of the cell a NOP run is asked about; reused. */
    private int[] runEntryPos;

    /** The value of the label the last {@link #lastBlockBoundaryWithin} cut at. */
    private int cutLabelValue;

    /** Whether execution can leave the stretch the last {@link #cutBackCopyLength} returned at its end. */
    private boolean cutCopyEndsOpen;

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
        this.jumpOpcodeId = resolveJumpOpcode();
        if (duplicationRate < 0.0 || duplicationRate > 1.0) {
            throw new IllegalArgumentException("duplicationRate must be in [0.0, 1.0], got: " + duplicationRate);
        }
        if (minNopSize < 1) {
            throw new IllegalArgumentException("minNopSize must be positive, got: " + minNopSize);
        }
    }

    /**
     * Resolves the opcode of the jump a cut-back copy is closed with.
     *
     * @return The opcode ID of {@value #JUMP_INSTRUCTION}.
     * @throws IllegalStateException if the instruction set does not carry that instruction, which
     *                               means the registry was not initialized.
     */
    private static int resolveJumpOpcode() {
        Integer id = Instruction.getInstructionIdByName(JUMP_INSTRUCTION);
        if (id == null) {
            throw new IllegalStateException("The instruction set carries no " + JUMP_INSTRUCTION
                    + ", which a cut-back copy is closed with");
        }
        return id;
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
     * Builds the newborn's lines, selects a random LABEL via reservoir sampling, finds the largest
     * NOP area on a random line, and copies the blocks starting at the label
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

        lines.build(env, childId, dv, child.getInitialPosition());
        int dvDim = lines.dvDim();
        if (dvDim == -1) {
            LOG.debug("tick={} Organism {} gene duplication: degenerate DV", child.getBirthTick(), childId);
            return;
        }

        ensureBuffers(dims);

        // --- Step 2: Reservoir sample a label ---
        // The lines list the labels in flat-index order: the reservoir choice below must not
        // depend on write history
        int labelCount = lines.labelCount();
        int selectedLabelFlatIndex = -1;
        for (int i = 0; i < labelCount; i++) {
            if (random.nextInt(i + 1) == 0) {
                selectedLabelFlatIndex = lines.labelFlatIndex(i);
            }
        }
        if (labelCount == 0) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("tick={} Organism {} selected for duplication: {} owned cells, {} lines, 0 labels — skipping",
                        child.getBirthTick(), childId, env.countCellsOwnedBy(childId), lines.lineCount());
            }
            return; // no labels found
        }

        // The reading frame says of every cell whether the machine reads it as part of an
        // instruction: which runs execution runs on into, what the copy carries, which LABEL cells
        // are the block boundaries a cut-back copy may end at, and whether the copy ends open.
        frame.build(env, lines);

        // --- Step 3: Scan all lines for NOP areas ---
        ensureLineCapacity(lines.lineCount());
        int candidateCount = 0;
        for (int line = 0; line < lines.lineCount(); line++) {
            env.properties.flatIndexToCoordinates(lines.sampleFlatIndex(line), coordBuffer);
            findBestNopRun(line, env, dvDim, dv[dvDim], shape[dvDim]);
            if (bestNopLength[line] >= minNopSize) {
                candidateCount++;
            }
        }

        if (candidateCount == 0) {
            LOG.debug("tick={} Organism {} selected for duplication: {} labels, no line with a NOP run >= {} away from execution — skipping",
                    child.getBirthTick(), childId, labelCount, minNopSize);
            return;
        }

        // Pick a random candidate via reservoir sampling (zero allocation)
        int targetLine = -1;
        int seen = 0;
        for (int line = 0; line < lines.lineCount(); line++) {
            if (bestNopLength[line] >= minNopSize) {
                seen++;
                if (random.nextInt(seen) == 0) {
                    targetLine = line;
                }
            }
        }

        // --- Step 4: Copy length (DV-direction-aware) ---
        env.properties.flatIndexToCoordinates(selectedLabelFlatIndex, sourcePos);
        int selectedLabelDvCoord = sourcePos[dvDim];
        int labelLine = lines.lineOf(sourcePos);
        int availableSource = lines.cellsFrom(sourcePos);

        int dvStep = dv[dvDim];
        int shapeDvDim = shape[dvDim];

        int room = bestNopLength[targetLine];
        boolean cutBack = availableSource > room;
        int copyLength = cutBack
                ? cutBackCopyLength(env, childId, room, selectedLabelDvCoord, dvStep, dvDim)
                // Cells the copy would write as empty carry nothing, so the copy does not reserve
                // room for them
                : withoutTrailingEmptyCells(env, availableSource, dvStep, dvDim);
        sourcePos[dvDim] = selectedLabelDvCoord;
        if (copyLength == 0) {
            LOG.debug("tick={} Organism {} selected for duplication: NOP run of {} cells holds no code of the source — skipping",
                    child.getBirthTick(), childId, room);
            return;
        }
        // A copy that reaches the end of the source line ends where the source ends; only a
        // cut-back copy lacks what the source runs on into.
        boolean closed = cutBack && cutCopyEndsOpen;
        int writtenLength = closed ? copyLength + CLOSING_JUMP_LENGTH : copyLength;

        // --- Step 5: Copy ---
        // Build target position from the target line, adjusting start for DV direction
        env.properties.flatIndexToCoordinates(lines.sampleFlatIndex(targetLine), targetPos);
        if (dvStep < 0) {
            targetPos[dvDim] = (bestNopStart[targetLine] + writtenLength - 1) % shapeDvDim;
        } else {
            targetPos[dvDim] = bestNopStart[targetLine];
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
            sourcePos[dvDim] = env.properties.stepAlongAxis(sourcePos[dvDim], dvDim, dvStep);
            targetPos[dvDim] = env.properties.stepAlongAxis(targetPos[dvDim], dvDim, dvStep);
        }

        if (closed) {
            writeClosingJump(env, childId, dvStep, dvDim);
        }

        child.recordBirthMutation(recordBuilder.build());

        if (LOG.isDebugEnabled()) {
            env.properties.flatIndexToCoordinates(lines.sampleFlatIndex(labelLine), sourcePos);
            sourcePos[dvDim] = selectedLabelDvCoord;
            env.properties.flatIndexToCoordinates(lines.sampleFlatIndex(targetLine), targetPos);
            targetPos[dvDim] = (dvStep < 0)
                    ? (bestNopStart[targetLine] + writtenLength - 1) % shapeDvDim
                    : bestNopStart[targetLine];
            LOG.debug("tick={} Organism {} gene duplication: copied {} molecules from {} to {}",
                    child.getBirthTick(), childId, copyLength, Arrays.toString(sourcePos), Arrays.toString(targetPos));
        }
    }

    /**
     * Determines how many cells of the source a cut-back copy takes.
     * <p>
     * The copy ends at the last block boundary within the room, without the cells it would write
     * as empty. If execution can leave that stretch at its end, the copy is closed with a jump and
     * needs {@value #CLOSING_JUMP_LENGTH} cells more; where the room does not hold them, the copy
     * is cut back to the last boundary that leaves them. The label the copy was cut at is left in
     * {@link #cutLabelValue}, and whether execution can leave the returned stretch at its end in
     * {@link #cutCopyEndsOpen}. Leaves the source buffer somewhere along the source.
     *
     * @param env The simulation environment.
     * @param childId The newborn whose cells and whose reading frame the blocks belong to.
     * @param room The number of cells the target's empty run offers.
     * @param labelDvCoord The DV coordinate of the chosen label, where the source begins.
     * @param dvStep The step along the DV dimension, {@code +1} or {@code -1}.
     * @param dvDim The DV dimension index.
     * @return The number of source cells to copy, or {@code 0} if the room holds no whole block
     *         together with the jump it needs.
     */
    private int cutBackCopyLength(Environment env, int childId, int room, int labelDvCoord,
                                  int dvStep, int dvDim) {
        int available = room;
        while (available > 0) {
            sourcePos[dvDim] = labelDvCoord;
            int boundary = lastBlockBoundaryWithin(env, childId, available, dvStep, dvDim);
            if (boundary == 0) {
                return 0;
            }
            sourcePos[dvDim] = labelDvCoord;
            // At least the chosen label: a label is always carried
            int carried = withoutTrailingEmptyCells(env, boundary, dvStep, dvDim);
            sourcePos[dvDim] = labelDvCoord;
            cutCopyEndsOpen = flow.endsOpen(env, frame, sourcePos, carried);
            if (!cutCopyEndsOpen || carried + CLOSING_JUMP_LENGTH <= room) {
                return carried;
            }
            // The closing jump does not fit behind this stretch: look for a boundary further back
            available = boundary - 1;
        }
        return 0;
    }

    /**
     * Writes the jump that closes a cut-back copy, to the value of the label the copy was cut at,
     * and appends its two cells to {@link #recordBuilder}.
     * <p>
     * Writes at the cell the target buffer stands on, which after the copy loop is the first cell
     * behind the copy.
     *
     * @param env The simulation environment.
     * @param childId The child organism's ID.
     * @param dvStep The step along the DV dimension, {@code +1} or {@code -1}.
     * @param dvDim The DV dimension index.
     */
    private void writeClosingJump(Environment env, int childId, int dvStep, int dvDim) {
        writeBehindCopy(env, childId, new Molecule(Config.TYPE_CODE, jumpOpcodeId & Config.VALUE_MASK),
                dvStep, dvDim);
        writeBehindCopy(env, childId, new Molecule(Config.TYPE_LABELREF, cutLabelValue),
                dvStep, dvDim);
    }

    /**
     * Writes one molecule at the cell the target buffer stands on, appends it to
     * {@link #recordBuilder} and moves the target buffer one cell along the direction vector.
     *
     * @param env The simulation environment.
     * @param childId The child organism's ID.
     * @param molecule The molecule to write.
     * @param dvStep The step along the DV dimension, {@code +1} or {@code -1}.
     * @param dvDim The DV dimension index.
     */
    private void writeBehindCopy(Environment env, int childId, Molecule molecule,
                                 int dvStep, int dvDim) {
        recordBuilder.cell(env.properties.toFlatIndex(targetPos),
                env.getMoleculeIntAt(targetPos), molecule.toInt());
        env.setMolecule(molecule, childId, targetPos);
        targetPos[dvDim] = env.properties.stepAlongAxis(targetPos[dvDim], dvDim, dvStep);
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
     * @return The length up to and including the last cell the copy carries, 0 if it carries none.
     */
    private int withoutTrailingEmptyCells(Environment env, int copyLength, int dvStep, int dvDim) {
        int carried = 0;
        int dvPos = sourcePos[dvDim];
        for (int offset = 0; offset < copyLength; offset++) {
            sourcePos[dvDim] = dvPos;
            if (env.getMoleculeIntAt(sourcePos) != 0 && carriedByACopy(env, sourcePos)) {
                carried = offset + 1;
            }
            dvPos = env.properties.stepAlongAxis(dvPos, dvDim, dvStep);
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
     * The value of the label at that boundary is left in {@link #cutLabelValue}. The walk leaves
     * the source buffer standing on the last cell it looked at; the caller restores it to the
     * chosen label.
     *
     * @param env The simulation environment.
     * @param childId The newborn whose cells and whose reading frame the blocks belong to.
     * @param room The number of cells the target's empty run offers.
     * @param dvStep The step along the DV dimension, {@code +1} or {@code -1}.
     * @param dvDim The DV dimension index.
     * @return The number of cells to copy, or {@code 0} if no block fits.
     */
    private int lastBlockBoundaryWithin(Environment env, int childId, int room, int dvStep, int dvDim) {
        int boundary = 0;
        int dvPos = sourcePos[dvDim];
        for (int offset = 1; offset <= room; offset++) {
            dvPos = env.properties.stepAlongAxis(dvPos, dvDim, dvStep);
            sourcePos[dvDim] = dvPos;
            if ((env.getMoleculeIntAt(sourcePos) & Config.TYPE_MASK) != Config.TYPE_LABEL) {
                continue;
            }
            if (env.getOwnerIdAt(sourcePos) != childId) {
                continue;
            }
            if (frame.slot(env.properties.toFlatIndex(sourcePos)) == GenomeFrame.Slot.NONE) {
                boundary = offset;
                cutLabelValue = env.getMoleculeIntAt(sourcePos) & Config.VALUE_MASK;
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
            runEntryPos = new int[dims];
        }
    }

    /**
     * Sizes the per-line results for the number of lines of the current newborn.
     *
     * @param lineCount The number of lines.
     */
    private void ensureLineCapacity(int lineCount) {
        if (bestNopStart.length < lineCount) {
            bestNopStart = new int[lineCount];
            bestNopLength = new int[lineCount];
        }
    }

    /**
     * Scans a line for the largest contiguous run of empty cells (CODE:0, marker:0) that
     * execution does not run on into. Results are stored in {@link #bestNopStart} and
     * {@link #bestNopLength} at the line's number.
     * <p>
     * Walks along the line's extent ({@link GenomeLines#arcStart} to {@link GenomeLines#arcEnd}),
     * correctly handling toroidal wrapping.
     * Uses the shared coordBuffer (caller must have initialized it via flatIndexToCoordinates
     * with the line's {@link GenomeLines#sampleFlatIndex} before calling) and needs {@link #frame}
     * built.
     *
     * @param line The number of the line to scan.
     * @param env The simulation environment.
     * @param dvDim The DV dimension index.
     * @param dvStep The step along the DV dimension, {@code +1} or {@code -1}.
     * @param shapeDvDim The environment size along the DV dimension.
     */
    private void findBestNopRun(int line, Environment env, int dvDim, int dvStep, int shapeDvDim) {
        int nopRunStart = -1;
        int nopRunLength = 0;
        bestNopStart[line] = -1;
        bestNopLength[line] = 0;

        int arcLength = lines.arcLength(line);

        int dvPos = lines.arcStart(line);
        for (int step = 0; step < arcLength; step++) {
            coordBuffer[dvDim] = dvPos;
            int moleculeInt = env.getMoleculeIntAt(coordBuffer);

            if (moleculeInt == 0) {
                if (nopRunStart == -1) {
                    nopRunStart = dvPos;
                }
                nopRunLength++;
            } else {
                offerNopRun(line, env, nopRunStart, nopRunLength, dvDim, dvStep, shapeDvDim);
                nopRunLength = 0;
                nopRunStart = -1;
            }

            dvPos = env.properties.stepAlongAxis(dvPos, dvDim, 1);
        }
        offerNopRun(line, env, nopRunStart, nopRunLength, dvDim, dvStep, shapeDvDim);
    }

    /**
     * Takes a NOP run as the line's best if it is longer than the best so far, long enough to be a
     * target at all, and away from execution.
     * <p>
     * A run the organism started in is passed over: its instruction pointer would start inside
     * the copy. Otherwise the run is asked about at the cell execution would enter it by; the
     * empty cells of the run itself change nothing about the answer. A run too short to matter is
     * not asked about.
     *
     * @param line The number of the line the run lies on.
     * @param env The simulation environment.
     * @param runStart The run's smallest DV coordinate, in the direction of rising coordinates.
     * @param runLength The number of cells of the run; 0 if there is none.
     * @param dvDim The DV dimension index.
     * @param dvStep The step along the DV dimension, {@code +1} or {@code -1}.
     * @param shapeDvDim The environment size along the DV dimension.
     */
    private void offerNopRun(int line, Environment env, int runStart, int runLength,
                             int dvDim, int dvStep, int shapeDvDim) {
        if (runLength <= bestNopLength[line] || runLength < minNopSize) {
            return;
        }
        System.arraycopy(coordBuffer, 0, runEntryPos, 0, runEntryPos.length);
        for (int i = 0; i < runLength; i++) {
            runEntryPos[dvDim] = (runStart + i) % shapeDvDim;
            if (frame.isInitialPosition(env.properties.toFlatIndex(runEntryPos))) {
                return;
            }
        }
        int entry = dvStep > 0 ? runStart : (runStart + runLength - 1) % shapeDvDim;
        runEntryPos[dvDim] = entry;
        if (flow.reachedByFallThrough(env, frame, runEntryPos)) {
            return;
        }
        bestNopStart[line] = runStart;
        bestNopLength[line] = runLength;
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
