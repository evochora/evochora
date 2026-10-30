package org.evochora.runtime.worldgen;

import java.util.List;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Instruction.OperandSource;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

/**
 * The role every cell of one organism's genome carries when the virtual machine reads it as code.
 * <p>
 * A cell is an opcode, a cell of an operand slot of some kind, or part of no instruction at all.
 * Which of them it is cannot be seen on the cell: an operand slot holds whatever molecule the
 * program put there, a CODE molecule of value zero included, so neither the cell nor its
 * neighbours say whether it is an opcode or an argument. Only a forward parse from a point at
 * which the instruction pointer can stand knows the reading frame, and that is what this class
 * performs: it starts where the machine can enter the code — at a label the organism owns and at
 * the organism's initial position — and reads forward exactly as the machine reads while the
 * organism keeps its direction vector.
 * <p>
 * <strong>One direction.</strong> Everything the frame answers holds for the one direction vector
 * it is built with. An organism that turns reads its cells along other scan lines, and none of
 * the answers describes that. The mutation operators build it for a newborn with the direction
 * vector it is born with and accept this limit; nothing outside them may rely on it, which is why
 * the class is not visible outside this package.
 * <p>
 * <strong>What a walk does.</strong> From each of those starts it follows the organism's direction
 * vector, one cell at a time:
 * <ul>
 *   <li>a molecule that is not CODE, and a CODE molecule whose value is the NOP opcode, is at an
 *       instruction start a no-operation of one cell and belongs to no instruction;</li>
 *   <li>a registered opcode occupies its own cell and the cells its signature declares — a
 *       register, a location register, an immediate and a label operand one cell each, a vector
 *       operand one cell per world dimension, a stack operand none — whatever molecules those
 *       cells hold;</li>
 *   <li>an opcode value that is registered nowhere occupies one cell, the length the machine
 *       reads for it;</li>
 *   <li>cells owned by another organism are walked over like any other, because the machine does
 *       not stop at them either.</li>
 * </ul> A walk ends where the organism's extent on its scan line ends, as {@link GenomeScanLines}
 * resolves it: the arc over the coordinates the organism owns on the scan line and its initial
 * position where that lies on the scan line, which crosses the world edge only where the world is
 * toroidal. An instruction whose operands would reach past the end of the arc is cut there: the
 * cells beyond are not part of this genome. The frame is built on top of those scan lines, which
 * the caller builds first and can use for its own purposes as well.
 * <p>
 * <strong>Two walks over one cell.</strong> Two starts can lead through the same cell in different
 * reading frames — a label placed inside another instruction's operand list is read as an operand
 * by the walk that passes it and as an instruction start by the walk that begins there. Such a
 * cell is {@link Slot#AMBIGUOUS}, and it is so whichever walk ran first: any two assignments that
 * disagree make the cell ambiguous, and further agreeing assignments do not take that back.
 * <p>
 * <strong>A lookup, not a sequence.</strong> The result is read cell by cell, by the flat index of
 * {@link EnvironmentProperties#toFlatIndex(int[])}. It is deliberately not iterable: the walks run
 * along the organism's direction vector, an order that has nothing to do with the flat index in
 * which cells are numbered and persisted, so a caller that iterated the frame to choose a cell
 * would make its choice depend on that direction. A caller selects over the cells it owns and asks
 * the frame what each of them is.
 * <p>
 * <strong>What the frame answers.</strong> The mutation operators read it for different
 * purposes, and the answers are there for any operator that needs them:
 * <ul>
 *   <li>the role of every cell — opcode, register, literal, label reference, vector component —
 *       so that a selection weight or a perturbation can depend on where a molecule stands, not
 *       only on what it is: the substitution weighs a cell by its role, and a CODE molecule in a
 *       literal slot is a value there, not an opcode;</li>
 *   <li>which cells belong to no instruction — labels, no-operation cells and cells no walk
 *       reaches — so that an operator can leave them alone or seek them out: the deletion finds
 *       the labels that open a block among them and leaves them standing at a block's end, and the
 *       duplication writes such a cell into a copy as an empty one, a label excepted;</li>
 *   <li>the component index of a vector cell, so that an operator can act on a vector as a whole
 *       — rotate or mirror it — rather than on one of its cells;</li>
 *   <li>where an instruction begins and ends, so that an operator copies or removes whole
 *       instructions instead of fragments, and can tell a gap between two instructions from a
 *       cell inside an operand list.</li>
 * </ul>
 * What the frame does not know is control flow: a walk does not end at a jump, so the padding
 * behind a row's last jump is reached like the code before it. {@link GenomeFlow} answers those
 * questions on top of a built frame — whether execution runs on into a cell, whether a label is a
 * jump target and nothing else, whether a stretch of code can be left at its end.
 * <p>
 * Besides the roles the frame tells whether a cell is the organism's initial position, where its
 * instruction pointer enters the code without a jump.
 * <p>
 * <strong>Determinism.</strong> The roles are a pure function of the grid, of the cells the
 * organism owns, of the initial position and of the direction vector. Nothing is carried over
 * between builds: every build reads the grid as it stands.
 * <p>
 * <strong>Allocation.</strong> The frame is an object so that its buffers — the role table and
 * the coordinate array — survive a build and are cleared at the beginning of the next one. After
 * the first builds the only allocation left per build is the lookup of the NOP opcode; the
 * operand sources of an opcode are the registry's own list and cost nothing.
 * <p>
 * <strong>Thread safety.</strong> Not thread-safe: a frame holds the buffers of the build that is
 * running, so every user keeps its own.
 */
final class GenomeFrame {

    /**
     * The role one cell carries in the reading frame.
     */
    public enum Slot {
        /** The opcode cell of a registered instruction. */
        INSTRUCTION,
        /** A cell in an immediate operand slot, which holds a literal molecule. */
        SCALAR,
        /** A cell in a register or location-register operand slot, which names a register. */
        REGISTER,
        /** A cell in a label operand slot, which holds the reference a jump matches against. */
        LABEL,
        /** A cell of a vector operand slot, which holds one component of the vector. */
        VECTOR,
        /**
         * A cell that is part of no instruction: a label, a no-operation cell at an instruction
         * start, and every cell no walk reaches.
         */
        NONE,
        /** A cell two walks reach in different reading frames, so that it has no single role. */
        AMBIGUOUS
    }

    /** Bits the slot occupies in a packed role. */
    private static final int SLOT_MASK = 0xFF;

    /** Bits above this shift hold the component index, raised by one so that zero means none. */
    private static final int COMPONENT_SHIFT = 8;

    /** Answer of the role table for a cell no walk has reached. */
    private static final int UNASSIGNED = -1;

    /** The slots by ordinal, so that unpacking a role reads no defensive copy. */
    private static final Slot[] SLOTS = Slot.values();

    /** Packed role of a cell two walks disagree about. */
    private static final int AMBIGUOUS_ROLE = Slot.AMBIGUOUS.ordinal();

    /** Packed role per flat index; a cell that is absent has been reached by no walk. */
    private final Int2IntOpenHashMap roles = new Int2IntOpenHashMap();

    /** The scan lines of the genome the frame was last built on. */
    private GenomeScanLines scanLines;

    /** Coordinates of the cell a walk is looking at. */
    private int[] walkCoord;

    /** The dimension the direction vector points along. */
    private int dvDim = -1;

    /** The step the walk takes along that dimension, {@code +1} or {@code -1}. */
    private int step;

    /** The number of dimensions of the world the frame was last built for. */
    private int dims;

    /** Flat index of the position the organism started at; {@code -1} while the frame is empty. */
    private int initialFlatIndex = -1;

    /**
     * Creates an empty frame, which answers {@link Slot#NONE} for every cell until it is built.
     */
    public GenomeFrame() {
        roles.defaultReturnValue(UNASSIGNED);
    }

    /**
     * Reads one organism's genome as the machine reads it and records what every cell is.
     * <p>
     * Everything a previous build recorded is dropped first, so the result describes the grid as
     * it stands now. Scan lines without a direction or without any scan line leave the frame empty.
     * <p> The walks start at every label the scan lines list and at the initial position they were
     * built with, a frame start like every label.
     *
     * @param env The environment holding the cells.
     * @param scanLines The organism's scan lines, built for the grid as it stands; kept by the
     *              frame until its next build and not to be rebuilt before the frame is done with.
     */
    public void build(Environment env, GenomeScanLines scanLines) {
        roles.clear();
        this.scanLines = scanLines;
        initialFlatIndex = -1;
        dvDim = scanLines.dvDim();
        if (dvDim == -1 || scanLines.scanLineCount() == 0) {
            return;
        }
        EnvironmentProperties props = env.getProperties();
        initialFlatIndex = scanLines.initialFlatIndex();
        step = scanLines.step();
        dims = props.getDimensions();
        if (walkCoord == null || walkCoord.length != dims) {
            walkCoord = new int[dims];
        }

        int nopOpcodeId = Instruction.getInstructionIdByName("NOP");
        for (int i = 0; i < scanLines.labelCount(); i++) {
            walk(env, scanLines.labelFlatIndex(i), nopOpcodeId);
        }
        walk(env, initialFlatIndex, nopOpcodeId);
    }

    /**
     * Reports the scan lines the frame was last built on.
     *
     * @return The scan lines, or {@code null} if the frame was never built.
     */
    public GenomeScanLines scanLines() {
        return scanLines;
    }

    /**
     * Reports what the cell at a flat index is in the reading frame.
     *
     * @param flatIndex The cell's flat index, as {@link EnvironmentProperties#toFlatIndex(int[])}
     *                  computes it.
     * @return The cell's role; {@link Slot#NONE} for a cell no walk reached, which includes every
     *         cell outside the organism's body.
     */
    public Slot slot(int flatIndex) {
        int role = roles.get(flatIndex);
        return role == UNASSIGNED ? Slot.NONE : SLOTS[role & SLOT_MASK];
    }

    /**
     * Reports which component of its vector operand the cell at a flat index holds.
     *
     * @param flatIndex The cell's flat index, as {@link EnvironmentProperties#toFlatIndex(int[])}
     *                  computes it.
     * @return The component index, from zero to one less than the number of world dimensions, for
     *         a cell whose role is {@link Slot#VECTOR}; {@code -1} for every other cell.
     */
    public int componentIndex(int flatIndex) {
        int role = roles.get(flatIndex);
        if (role == UNASSIGNED || SLOTS[role & SLOT_MASK] != Slot.VECTOR) {
            return -1;
        }
        return (role >>> COMPONENT_SHIFT) - 1;
    }

    /**
     * Tells whether a cell is the position the organism started at, where its instruction pointer
     * enters the code without a jump.
     *
     * @param flatIndex The cell's flat index, as {@link EnvironmentProperties#toFlatIndex(int[])}
     *                  computes it.
     * @return {@code true} if the frame was built with this cell as the initial position; never
     *         for a frame that was left empty.
     */
    public boolean isInitialPosition(int flatIndex) {
        return initialFlatIndex >= 0 && flatIndex == initialFlatIndex;
    }

    /**
     * Reads the code from one frame start to the end of the organism's extent on that scan line.
     *
     * @param env The environment holding the cells.
     * @param startFlatIndex The flat index of the cell the walk starts at.
     * @param nopOpcodeId The opcode value of the no-operation, which at an instruction start
     *                    belongs to no instruction.
     */
    private void walk(Environment env, int startFlatIndex, int nopOpcodeId) {
        EnvironmentProperties props = env.getProperties();
        props.flatIndexToCoordinates(startFlatIndex, walkCoord);

        // The cells still ahead of the walk: from its start to the end of the arc it moves towards.
        int remaining = scanLines.cellsFrom(walkCoord);
        if (remaining <= 0) {
            return;
        }
        int pos = walkCoord[dvDim];

        while (remaining > 0) {
            walkCoord[dvDim] = pos;
            int moleculeInt = env.getMoleculeIntAt(walkCoord);
            int opcodeId = Molecule.extractSignedValue(moleculeInt);
            boolean opensInstruction = (moleculeInt & Config.TYPE_MASK) == Config.TYPE_CODE
                    && opcodeId != nopOpcodeId
                    && Instruction.getPlannerById(opcodeId) != null;
            assign(props, opensInstruction ? Slot.INSTRUCTION : Slot.NONE, -1);
            pos = props.stepAlongAxis(pos, dvDim, step);
            remaining--;
            if (!opensInstruction) {
                continue;
            }

            List<OperandSource> sources = Instruction.getOperandSourcesById(opcodeId);
            for (int s = 0; s < sources.size() && remaining > 0; s++) {
                OperandSource source = sources.get(s);
                if (source == OperandSource.STACK) {
                    continue;
                }
                if (source == OperandSource.VECTOR) {
                    for (int component = 0; component < dims && remaining > 0; component++) {
                        walkCoord[dvDim] = pos;
                        assign(props, Slot.VECTOR, component);
                        pos = props.stepAlongAxis(pos, dvDim, step);
                        remaining--;
                    }
                    continue;
                }
                walkCoord[dvDim] = pos;
                assign(props, slotOf(source), -1);
                pos = props.stepAlongAxis(pos, dvDim, step);
                remaining--;
            }
        }
    }

    /**
     * Records the role of the cell the walk stands on, at the coordinates in the walk buffer.
     * <p>
     * A cell another walk has already reached keeps its role while the two agree and becomes
     * {@link Slot#AMBIGUOUS} as soon as they do not, which no later agreement takes back. The
     * outcome is therefore the same whichever walk came first.
     *
     * @param props The properties the cell's flat index is computed with.
     * @param slot The role this walk reads the cell in.
     * @param component The component index for a cell of a vector operand, {@code -1} otherwise.
     */
    private void assign(EnvironmentProperties props, Slot slot, int component) {
        int flatIndex = props.toFlatIndex(walkCoord);
        int role = slot.ordinal() | ((component + 1) << COMPONENT_SHIFT);
        int existing = roles.get(flatIndex);
        if (existing == UNASSIGNED) {
            roles.put(flatIndex, role);
        } else if (existing != role) {
            roles.put(flatIndex, AMBIGUOUS_ROLE);
        }
    }

    /**
     * Maps an operand source to the role the cells of its slot carry.
     *
     * @param source The operand source an instruction's signature declares.
     * @return The role of a cell in that slot; {@link Slot#NONE} for a stack operand, which
     *         occupies no cell.
     */
    private static Slot slotOf(OperandSource source) {
        return switch (source) {
            case IMMEDIATE -> Slot.SCALAR;
            case REGISTER, LOCATION_REGISTER -> Slot.REGISTER;
            case LABEL -> Slot.LABEL;
            case VECTOR -> Slot.VECTOR;
            case STACK -> Slot.NONE;
        };
    }
}
