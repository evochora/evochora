package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Family;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.GenomeFrame.Slot;

import it.unimi.dsi.fastutil.booleans.BooleanArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * What the instruction set says about how execution moves through one organism's genome, read on
 * top of a {@link GenomeFrame}.
 * <p>
 * The frame knows what every cell is; it does not know control flow. An operator that writes code
 * into a genome needs three answers the frame alone cannot give:
 * <ul>
 *   <li><b>whether execution runs into a stretch of empty cells on its own</b>
 *       ({@link #reachedByFallThrough}) — code written there that leaves through a jump takes every
 *       passage through those cells with it, whatever the code is;</li>
 *   <li><b>whether a label is a jump target and nothing else</b> ({@link #collectReferences},
 *       {@link #isJumpTarget}) — a label a location instruction addresses names a place for the
 *       data pointer, and the cells behind it are no block of code;</li>
 *   <li><b>whether a stretch of code can be left at its end</b> ({@link #endsOpen}) — a copy of
 *       such a stretch lacks the code the original runs on into.</li>
 * </ul>
 * All three rest on what the instruction set declares — the family of an instruction and
 * {@link Instruction#neverFallsThrough(int)} — and on nothing a particular program does.
 * <p>
 * <strong>What counts as transparent.</strong> The machine reads a cell that opens no instruction —
 * an empty cell, a molecule that is not CODE, an opcode value registered nowhere — as a
 * no-operation of one cell and moves on. Every walk here passes such cells the same way, and an
 * operand cell as well, which leads back to its opcode. A registered opcode the frame gives no
 * role is passed too: every walk of the frame runs from a label to the end of its line, so such a
 * cell stands before the first label of its line, where the machine never reads, and no cell with
 * a role can stand before it.
 * <p>
 * <strong>What it cannot see.</strong> A jump whose target is computed at run time carries no label
 * reference in the genome, so {@link #isJumpTarget} does not know of it.
 * <p>
 * <strong>Allocation.</strong> The reference lists and the coordinate buffer survive a call and are
 * reused by the next; {@link #collectReferences} allocates the visitor it hands to the environment.
 * <p>
 * <strong>Thread safety.</strong> Not thread-safe: an instance holds the buffers of the call that is
 * running, so every user keeps its own.
 */
public final class GenomeFlow {

    /** The values of the label references {@link #collectReferences} found. */
    private final IntArrayList referenceValues = new IntArrayList();

    /** Per reference, whether it stands in the label slot of a control flow instruction. */
    private final BooleanArrayList referenceIsControlFlow = new BooleanArrayList();

    /** Coordinates of the cell a walk is looking at. */
    private int[] coord;

    /**
     * Tells whether execution that runs on from one instruction to the next reaches a cell.
     * <p>
     * The walk goes from the cell against the direction vector, over transparent cells, to whatever
     * execution would have to come from:
     * <ul>
     *   <li>a label: a jump lands there and runs on into the cell — reached;</li>
     *   <li>an instruction after which execution continues with the next cell — reached;</li>
     *   <li>an instruction that {@linkplain Instruction#neverFallsThrough(int) never falls
     *       through}: reached only if the instruction before it is a conditional, which skips it
     *       when its test fails. Only that one instruction is asked, because a conditional skips
     *       the next instruction and no other; a conditional further back acts on what stands
     *       between. A label between the two does not end the search for it: the machine's skip
     *       passes every cell that is no instruction ({@link Organism#skipNopCells});</li>
     *   <li>the beginning of the organism's extent on the line: nothing comes from there — not
     *       reached.</li>
     * </ul>
     * A cell two walks of the frame read differently has no single role; it counts as reached.
     *
     * @param env The environment holding the cells.
     * @param frame The reading frame of the organism, built for the grid as it stands.
     * @param cell The coordinates of the cell asked about; not modified.
     * @param dvDim The dimension the organism's direction vector points along.
     * @param dvStep The step along that dimension, {@code +1} or {@code -1}.
     * @param cellsBefore The number of cells of the organism's extent on the line that lie before
     *                    {@code cell} against the direction vector.
     * @return {@code true} if execution can run into the cell without a jump to it.
     */
    public boolean reachedByFallThrough(Environment env, GenomeFrame frame, int[] cell,
                                        int dvDim, int dvStep, int cellsBefore) {
        EnvironmentProperties props = env.getProperties();
        int axisSize = props.getDimensionSize(dvDim);
        copyToBuffer(cell);

        boolean behindFlowEnd = false;
        int pos = cell[dvDim];
        for (int i = 0; i < cellsBefore; i++) {
            pos = step(pos, -dvStep, axisSize);
            coord[dvDim] = pos;
            int moleculeInt = env.getMoleculeIntAt(coord);
            if (moleculeInt == 0) {
                continue;
            }
            Slot slot = frame.slot(props.toFlatIndex(coord));
            if (slot == Slot.AMBIGUOUS) {
                return true;
            }
            if (slot == Slot.INSTRUCTION) {
                int opcodeId = Molecule.extractSignedValue(moleculeInt);
                if (behindFlowEnd) {
                    return Instruction.getFamilyById(opcodeId) == Family.CONDITIONAL;
                }
                if (!Instruction.neverFallsThrough(opcodeId)) {
                    return true;
                }
                behindFlowEnd = true;
                continue;
            }
            if (slot == Slot.NONE && !behindFlowEnd
                    && (moleculeInt & Config.TYPE_MASK) == Config.TYPE_LABEL) {
                return true;
            }
            // An operand cell leads back to its opcode; every other cell is transparent.
        }
        return false;
    }

    /**
     * Reads every label reference the organism owns and notes whether it is the operand of a
     * control flow instruction. Replaces what a previous call collected.
     * <p>
     * The visit runs in flat-index order; the answers of {@link #isJumpTarget} do not depend on it.
     *
     * @param env The environment holding the cells.
     * @param frame The reading frame of the organism, built for the grid as it stands.
     * @param organismId The organism whose label references are read.
     * @param dvDim The dimension the organism's direction vector points along.
     * @param dvStep The step along that dimension, {@code +1} or {@code -1}.
     */
    public void collectReferences(Environment env, GenomeFrame frame, int organismId,
                                  int dvDim, int dvStep) {
        referenceValues.clear();
        referenceIsControlFlow.clear();
        EnvironmentProperties props = env.getProperties();
        int axisSize = props.getDimensionSize(dvDim);
        env.visitCellsOwnedBy(organismId, cell -> {
            int moleculeInt = cell.moleculeInt();
            if ((moleculeInt & Config.TYPE_MASK) != Config.TYPE_LABELREF) {
                return;
            }
            referenceValues.add(moleculeInt & Config.VALUE_MASK);
            referenceIsControlFlow.add(
                    isControlFlowOperand(env, frame, props, cell.coordinate(), dvDim, dvStep, axisSize));
        });
    }

    /**
     * Tells whether a label value is addressed by jumps and calls and by nothing else, judged by
     * the references {@link #collectReferences} read.
     * <p>
     * A reference addresses the label if it differs from it in at most {@code tolerance} bits. The
     * label is a jump target if at least one reference addresses it and every reference that does
     * stands in the label slot of a control flow instruction. One reference in a location
     * instruction, or one that stands in no label slot at all, makes the label a place for data.
     *
     * @param labelValue The label's value.
     * @param tolerance The number of differing bits up to which a reference addresses a label.
     * @return {@code true} if the label is a jump target and nothing else.
     */
    public boolean isJumpTarget(int labelValue, int tolerance) {
        boolean addressed = false;
        for (int i = 0; i < referenceValues.size(); i++) {
            if (Integer.bitCount(referenceValues.getInt(i) ^ labelValue) > tolerance) {
                continue;
            }
            if (!referenceIsControlFlow.getBoolean(i)) {
                return false;
            }
            addressed = true;
        }
        return addressed;
    }

    /**
     * Tells whether a reference value addresses no label value it did not address before, which is
     * what a label needs from a new value: that it draws no reference away from another label.
     *
     * @param newValue The value a label is to receive.
     * @param oldValue The value the label carries now.
     * @param tolerance The number of differing bits up to which a reference addresses a label.
     * @return {@code true} if every collected reference that addresses {@code newValue} also
     *         addresses {@code oldValue}.
     */
    public boolean drawsNoForeignReference(int newValue, int oldValue, int tolerance) {
        for (int i = 0; i < referenceValues.size(); i++) {
            int reference = referenceValues.getInt(i);
            if (Integer.bitCount(reference ^ newValue) <= tolerance
                    && Integer.bitCount(reference ^ oldValue) > tolerance) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tells whether execution can leave a stretch of code at its end.
     * <p>
     * The stretch is closed if its last instruction
     * {@linkplain Instruction#neverFallsThrough(int) never falls through} and the
     * instruction before it, within the stretch, is no conditional. It is open in every other case:
     * its last instruction lets execution continue, a conditional can skip the closing jump, or it
     * holds no instruction at all. A stretch that holds a cell two walks of the frame read
     * differently is open as well: what execution does there cannot be told from one reading.
     *
     * @param env The environment holding the cells.
     * @param frame The reading frame of the organism, built for the grid as it stands.
     * @param firstCell The coordinates of the stretch's first cell; not modified.
     * @param length The number of cells of the stretch along the direction vector.
     * @param dvDim The dimension the organism's direction vector points along.
     * @param dvStep The step along that dimension, {@code +1} or {@code -1}.
     * @return {@code true} if execution can run past the last cell of the stretch.
     */
    public boolean endsOpen(Environment env, GenomeFrame frame, int[] firstCell, int length,
                            int dvDim, int dvStep) {
        EnvironmentProperties props = env.getProperties();
        int axisSize = props.getDimensionSize(dvDim);
        copyToBuffer(firstCell);

        int lastOpcodeId = -1;
        int opcodeIdBeforeLast = -1;
        int pos = firstCell[dvDim];
        for (int i = 0; i < length; i++) {
            coord[dvDim] = pos;
            Slot slot = frame.slot(props.toFlatIndex(coord));
            if (slot == Slot.AMBIGUOUS) {
                return true;
            }
            if (slot == Slot.INSTRUCTION) {
                opcodeIdBeforeLast = lastOpcodeId;
                lastOpcodeId = Molecule.extractSignedValue(env.getMoleculeIntAt(coord));
            }
            pos = step(pos, dvStep, axisSize);
        }
        if (lastOpcodeId == -1 || !Instruction.neverFallsThrough(lastOpcodeId)) {
            return true;
        }
        return opcodeIdBeforeLast != -1
                && Instruction.getFamilyById(opcodeIdBeforeLast) == Family.CONDITIONAL;
    }

    /**
     * Tells whether a label reference is the operand of a control flow instruction.
     * <p>
     * The reference has to stand in a label slot; its opcode is the nearest instruction cell against
     * the direction vector, reached over the operand cells in between.
     *
     * @param env The environment holding the cells.
     * @param frame The reading frame of the organism.
     * @param props The properties flat indices are computed with.
     * @param referenceCell The coordinates of the reference; not modified.
     * @param dvDim The dimension the direction vector points along.
     * @param dvStep The step along that dimension.
     * @param axisSize The size of the world along that dimension.
     * @return {@code true} if the reference is what a jump or a call matches against.
     */
    private boolean isControlFlowOperand(Environment env, GenomeFrame frame, EnvironmentProperties props,
                                         int[] referenceCell, int dvDim, int dvStep, int axisSize) {
        copyToBuffer(referenceCell);
        if (frame.slot(props.toFlatIndex(coord)) != Slot.LABEL) {
            return false;
        }
        int pos = referenceCell[dvDim];
        // The walk ends at the first cell that is no operand, so it is as short as an operand list.
        for (int i = 0; i < axisSize; i++) {
            pos = step(pos, -dvStep, axisSize);
            coord[dvDim] = pos;
            Slot slot = frame.slot(props.toFlatIndex(coord));
            if (slot == Slot.INSTRUCTION) {
                int opcodeId = Molecule.extractSignedValue(env.getMoleculeIntAt(coord));
                return Instruction.getFamilyById(opcodeId) == Family.CONTROL;
            }
            if (slot == Slot.NONE || slot == Slot.AMBIGUOUS) {
                return false;
            }
        }
        return false;
    }

    /**
     * Steps one cell along an axis, around the world edge.
     *
     * @param pos The current coordinate.
     * @param delta The step, {@code +1} or {@code -1}.
     * @param axisSize The size of the axis.
     * @return The next coordinate.
     */
    private static int step(int pos, int delta, int axisSize) {
        int next = pos + delta;
        if (next >= axisSize) {
            return next - axisSize;
        }
        if (next < 0) {
            return next + axisSize;
        }
        return next;
    }

    /**
     * Copies coordinates into the walk buffer, sizing it for the world they come from.
     *
     * @param source The coordinates to copy.
     */
    private void copyToBuffer(int[] source) {
        if (coord == null || coord.length != source.length) {
            coord = new int[source.length];
        }
        System.arraycopy(source, 0, coord, 0, source.length);
    }
}
