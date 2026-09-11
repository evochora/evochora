package org.evochora.runtime.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Instruction.OperandSource;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * The role every cell of one organism's genome carries when the virtual machine reads it as code.
 * <p>
 * A cell is an opcode, a cell of an operand slot of some kind, or part of no instruction at all.
 * Which of them it is cannot be seen on the cell: an operand slot holds whatever molecule the
 * program put there, a CODE molecule of value zero included, so neither the cell nor its
 * neighbours say whether it is an opcode or an argument. Only a forward parse from a point at
 * which the instruction pointer can stand knows the reading frame, and that is what this class
 * performs: it starts where the machine can enter the code — at a label the organism owns and at
 * the organism's initial position — and reads forward exactly as the machine reads.
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
 * </ul>
 * A walk ends where the organism's extent on its line ends. That extent is the arc of
 * {@link ScanLineArc} over the coordinates the organism owns on the line, so it crosses the world
 * edge only where the world is toroidal. An instruction whose operands would reach past the end of
 * the arc is cut there: the cells beyond are not part of this genome.
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
 * <strong>What the frame offers beyond its first user.</strong> The substitution plugin reads
 * two things: whether a cell is a scalar literal and whether it is a vector component. The frame
 * answers more, and the answers are there for an operator that needs them:
 * <ul>
 *   <li>the role of every other cell — opcode, register, label reference — so that a selection
 *       weight or a perturbation can depend on where a molecule stands, not only on what it is;
 *       a CODE molecule in a literal slot, for instance, is a value there, not an opcode;</li>
 *   <li>which cells no walk reaches, so that an operator can leave them alone or seek them out;</li>
 *   <li>the component index of a vector cell, so that an operator can act on a vector as a whole
 *       — rotate or mirror it — rather than on one of its cells;</li>
 *   <li>where an instruction begins and ends, so that a duplication can copy whole instructions
 *       instead of fragments cut at a gap, a deletion can remove one instruction, and an
 *       insertion can tell a gap between two instructions from a cell inside an operand list.</li>
 * </ul>
 * What the frame does not know is control flow: a walk does not end at a jump, so the padding
 * behind a row's last jump is reached like the code before it.
 * <p>
 * <strong>Determinism.</strong> The roles are a pure function of the grid, of the cells the
 * organism owns, of the initial position and of the direction vector. Nothing is carried over
 * between builds: every build reads the grid as it stands.
 * <p>
 * <strong>Allocation.</strong> The frame is an object so that its buffers — the role table, the
 * scan lines, their pool and the coordinate arrays — survive a build and are cleared at the
 * beginning of the next one. After the first builds the only allocations left per build are the
 * visitor handed to the environment's owned-cell visit, the lookup of the NOP opcode and the list
 * the instruction registry returns per opcode.
 * <p>
 * <strong>Thread safety.</strong> Not thread-safe: a frame holds the buffers of the build that is
 * running, so every user keeps its own.
 */
public final class GenomeFrame {

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

    /** The organism's cells grouped into lines, by the coordinates perpendicular to the walk. */
    private final Int2ObjectOpenHashMap<ScanLine> scanLines = new Int2ObjectOpenHashMap<>();

    /** Scan lines kept for the next build. */
    private final ArrayList<ScanLine> scanLinePool = new ArrayList<>();

    /** Number of scan lines taken from the pool in the build that is running. */
    private int poolIndex;

    /** Flat indices of the label cells a walk starts at. */
    private final IntArrayList labelStarts = new IntArrayList();

    /** Receives the ends of a line's arc; reused so that resolving a line allocates nothing. */
    private final ScanLineArc.Result arc = new ScanLineArc.Result();

    /** Coordinates of the cell a grouping pass is looking at. */
    private int[] coordBuffer;

    /** Coordinates of the cell a walk is looking at. */
    private int[] walkCoord;

    /** Strides of the perpendicular key, zero in the dimension the walk runs along. */
    private int[] perpStrides;

    /** The owned coordinates along the walk, segment by scan line, while the arcs are resolved. */
    private int[] dvCoordCollector;

    /** The dimension the direction vector points along. */
    private int dvDim;

    /** The step the walk takes along that dimension, {@code +1} or {@code -1}. */
    private int step;

    /** The number of dimensions of the world the frame was last built for. */
    private int dims;

    /** The size of the world along the dimension the walk runs along. */
    private int axisSize;

    /**
     * The stretch of one line the organism occupies, and where a walk along that line ends.
     */
    private static final class ScanLine {
        /** Smallest coordinate the organism owns on this line, along the walk's dimension. */
        int minDv;
        /** Largest coordinate the organism owns on this line, along the walk's dimension. */
        int maxDv;
        /** Number of cells the organism owns on this line. */
        int count;
        /** First coordinate of the line's arc; see {@link ScanLineArc}. */
        int arcStart;
        /** Last coordinate of the line's arc, smaller than the first if the arc crosses the edge. */
        int arcEnd;
        /** Start of this line's segment in the shared coordinate buffer while arcs are resolved. */
        int segmentStart;
        /** Number of coordinates already placed in this line's segment. */
        int segmentFill;

        /**
         * Begins a line at the first cell seen on it.
         *
         * @param dvCoord The cell's coordinate along the walk's dimension.
         */
        void reset(int dvCoord) {
            this.minDv = dvCoord;
            this.maxDv = dvCoord;
            this.count = 1;
        }

        /**
         * Takes a further cell of the line into account.
         *
         * @param dvCoord The cell's coordinate along the walk's dimension.
         */
        void update(int dvCoord) {
            if (dvCoord < minDv) minDv = dvCoord;
            if (dvCoord > maxDv) maxDv = dvCoord;
            count++;
        }
    }

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
     * it stands now. A direction vector without a non-zero component names no direction and leaves
     * the frame empty, and so does an organism that owns no cell.
     *
     * @param env The environment holding the cells and their owners.
     * @param organismId The organism whose cells are read.
     * @param initialPosition The position the organism started at, a frame start like every label.
     * @param dv The organism's direction vector, a unit vector along one dimension.
     */
    public void build(Environment env, int organismId, int[] initialPosition, int[] dv) {
        roles.clear();
        scanLines.clear();
        labelStarts.clear();
        poolIndex = 0;

        EnvironmentProperties props = env.getProperties();
        dims = props.getDimensions();
        dvDim = -1;
        for (int i = 0; i < dims; i++) {
            if (dv[i] != 0) {
                dvDim = i;
                step = dv[i] > 0 ? 1 : -1;
                break;
            }
        }
        if (dvDim == -1) {
            return;
        }
        axisSize = props.getDimensionSize(dvDim);

        ensureBuffers();
        computePerpStrides(props);

        groupOwnedCells(env, organismId);
        if (scanLines.isEmpty()) {
            return;
        }
        resolveArcs(env, organismId, props.isToroidal());

        int nopOpcodeId = Instruction.getInstructionIdByName("NOP");
        for (int i = 0; i < labelStarts.size(); i++) {
            walk(env, labelStarts.getInt(i), nopOpcodeId);
        }
        walk(env, props.toFlatIndex(initialPosition), nopOpcodeId);
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
     * Groups the organism's cells into lines and collects the labels among them.
     * <p>
     * The visit runs in flat-index order, so the labels are collected in that order; the roles the
     * walks assign do not depend on it, because two walks that disagree about a cell make it
     * ambiguous whichever of them ran first.
     *
     * @param env The environment holding the cells and their owners.
     * @param organismId The organism whose cells are grouped.
     */
    private void groupOwnedCells(Environment env, int organismId) {
        EnvironmentProperties props = env.getProperties();
        env.visitCellsOwnedBy(organismId, cell -> {
            System.arraycopy(cell.coordinate(), 0, coordBuffer, 0, dims);
            int perpKey = perpKey(coordBuffer);
            ScanLine line = scanLines.get(perpKey);
            if (line == null) {
                line = acquireScanLine();
                line.reset(coordBuffer[dvDim]);
                scanLines.put(perpKey, line);
            } else {
                line.update(coordBuffer[dvDim]);
            }
            if ((cell.moleculeInt() & Config.TYPE_MASK) == Config.TYPE_LABEL) {
                labelStarts.add(props.toFlatIndex(coordBuffer));
            }
        });
    }

    /**
     * Determines where a walk along each line ends, the arc the organism spans on it.
     * <p>
     * A line whose owned cells cannot reach around the world edge spans the arc from its smallest
     * to its largest coordinate, which the grouping pass already knows. Only a line that can reach
     * around it needs the coordinates in between: for those the coordinates are collected, sorted
     * and handed to {@link ScanLineArc}, which decides where the body ends and the outside begins.
     *
     * @param env The environment holding the cells and their owners.
     * @param organismId The organism whose lines are resolved.
     * @param toroidal Whether the world wraps around at the ends of the walk's dimension.
     */
    private void resolveArcs(Environment env, int organismId, boolean toroidal) {
        boolean anyWrapping = false;
        for (ScanLine line : scanLines.values()) {
            line.arcStart = line.minDv;
            line.arcEnd = line.maxDv;
            if (ScanLineArc.largestGapRuleApplies(line.minDv, line.maxDv, axisSize, toroidal)) {
                anyWrapping = true;
            }
        }
        if (!anyWrapping) {
            return;
        }

        int total = 0;
        for (ScanLine line : scanLines.values()) {
            line.segmentStart = total;
            line.segmentFill = 0;
            total += line.count;
        }
        if (dvCoordCollector == null || dvCoordCollector.length < total) {
            dvCoordCollector = new int[total];
        }
        env.visitCellsOwnedBy(organismId, cell -> {
            System.arraycopy(cell.coordinate(), 0, coordBuffer, 0, dims);
            ScanLine line = scanLines.get(perpKey(coordBuffer));
            dvCoordCollector[line.segmentStart + line.segmentFill++] = coordBuffer[dvDim];
        });

        for (ScanLine line : scanLines.values()) {
            if (!ScanLineArc.largestGapRuleApplies(line.minDv, line.maxDv, axisSize, toroidal)) {
                continue;
            }
            Arrays.sort(dvCoordCollector, line.segmentStart, line.segmentStart + line.count);
            ScanLineArc.resolve(dvCoordCollector, line.segmentStart, line.count, axisSize, toroidal, arc);
            line.arcStart = arc.start;
            line.arcEnd = arc.end;
        }
    }

    /**
     * Reads the code from one frame start to the end of the organism's extent on that line.
     *
     * @param env The environment holding the cells.
     * @param startFlatIndex The flat index of the cell the walk starts at.
     * @param nopOpcodeId The opcode value of the no-operation, which at an instruction start
     *                    belongs to no instruction.
     */
    private void walk(Environment env, int startFlatIndex, int nopOpcodeId) {
        EnvironmentProperties props = env.getProperties();
        props.flatIndexToCoordinates(startFlatIndex, walkCoord);
        ScanLine line = scanLines.get(perpKey(walkCoord));
        if (line == null) {
            return;
        }

        int arcLength = (line.arcEnd >= line.arcStart)
                ? line.arcEnd - line.arcStart + 1
                : axisSize - line.arcStart + line.arcEnd + 1;
        int offsetInArc = forwardDistance(line.arcStart, walkCoord[dvDim]);
        if (offsetInArc >= arcLength) {
            return;
        }

        // The cells still ahead of the walk: from its start to the end of the arc it moves towards.
        int remaining = step > 0 ? arcLength - offsetInArc : offsetInArc + 1;
        int pos = walkCoord[dvDim];

        while (remaining > 0) {
            walkCoord[dvDim] = pos;
            int moleculeInt = env.getMoleculeIntAt(walkCoord);
            int opcodeId = Molecule.extractSignedValue(moleculeInt);
            boolean opensInstruction = (moleculeInt & Config.TYPE_MASK) == Config.TYPE_CODE
                    && opcodeId != nopOpcodeId
                    && Instruction.getPlannerById(opcodeId) != null;
            assign(props, opensInstruction ? Slot.INSTRUCTION : Slot.NONE, -1);
            pos = advance(pos);
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
                        pos = advance(pos);
                        remaining--;
                    }
                    continue;
                }
                walkCoord[dvDim] = pos;
                assign(props, slotOf(source), -1);
                pos = advance(pos);
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

    /**
     * Steps one cell along the direction vector, around the world edge where the world wraps.
     *
     * @param pos The current coordinate along the walk's dimension.
     * @return The next coordinate along that dimension.
     */
    private int advance(int pos) {
        int next = pos + step;
        if (next >= axisSize) {
            return 0;
        }
        if (next < 0) {
            return axisSize - 1;
        }
        return next;
    }

    /**
     * Counts the cells from one coordinate to another in the direction of rising coordinates.
     *
     * @param from The coordinate counted from.
     * @param to The coordinate counted to.
     * @return The number of steps from {@code from} to {@code to}, around the world edge if
     *         {@code to} lies before {@code from}.
     */
    private int forwardDistance(int from, int to) {
        int distance = to - from;
        return distance < 0 ? distance + axisSize : distance;
    }

    /**
     * Computes the key of the line a coordinate lies on, which is its coordinates in every
     * dimension but the one the walk runs along.
     *
     * @param coord The cell's coordinates.
     * @return The key of the cell's line.
     */
    private int perpKey(int[] coord) {
        int key = 0;
        for (int i = 0; i < coord.length; i++) {
            key += coord[i] * perpStrides[i];
        }
        return key;
    }

    /**
     * Computes the strides of the line key, which leave the walk's own dimension out.
     *
     * @param props The properties holding the world's shape.
     */
    private void computePerpStrides(EnvironmentProperties props) {
        int stride = 1;
        for (int i = dims - 1; i >= 0; i--) {
            if (i == dvDim) {
                perpStrides[i] = 0;
            } else {
                perpStrides[i] = stride;
                stride *= props.getDimensionSize(i);
            }
        }
    }

    /**
     * Sizes the coordinate buffers for the world the frame is being built for.
     */
    private void ensureBuffers() {
        if (coordBuffer == null || coordBuffer.length != dims) {
            coordBuffer = new int[dims];
            walkCoord = new int[dims];
            perpStrides = new int[dims];
        }
    }

    /**
     * Takes a scan line out of the pool, which holds the lines of the builds before.
     *
     * @return A scan line to be reset by the caller.
     */
    private ScanLine acquireScanLine() {
        if (poolIndex < scanLinePool.size()) {
            return scanLinePool.get(poolIndex++);
        }
        ScanLine line = new ScanLine();
        scanLinePool.add(line);
        poolIndex++;
        return line;
    }
}
