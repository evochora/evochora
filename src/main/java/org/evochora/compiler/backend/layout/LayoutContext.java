package org.evochora.compiler.backend.layout;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.runtime.model.EnvironmentProperties;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mutable state of the layout pass: the write cursor that walks the n-dimensional grid and the
 * address mappings that accumulate while items are placed.
 *
 * <p>All coordinates held here are relative to the program origin, never absolute world
 * coordinates. Linear addresses count placed cells in placement order starting at zero, and a
 * label occupies a cell just like an opcode or an operand.</p>
 *
 * <p>The cursor is created lazily on first use, because its dimensionality is taken from the
 * world shape. A program that places nothing can therefore be laid out without environment
 * properties, while the first placement without them fails.</p>
 */
public final class LayoutContext {

    private final EnvironmentProperties envProps;
    private boolean isInitialized = false;
    private int[] currentPos;
    private int[] currentDv;
    private int[] basePos;
    private int[] anchorPos;
    private final Deque<int[]> basePosStack = new ArrayDeque<>();
    private final Deque<int[]> dvStack = new ArrayDeque<>();

    private final Map<Integer, int[]> linearToCoord = new HashMap<>();
    private final Map<String, Integer> coordToLinear = new HashMap<>();
    private final Map<Integer, SourceInfo> sourceMap = new HashMap<>();
    private final Map<int[], PlacedMolecule> initialWorldObjects = new HashMap<>();
    private int linearAddress = 0;

    /**
     * Creates a layout context for the given environment.
     *
     * @param envProps The environment properties supplying the world shape, whose length fixes
     *                 the dimensionality of every layout vector. May be null; such a context is
     *                 usable only for programs that place nothing.
     */
    public LayoutContext(EnvironmentProperties envProps) {
        this.envProps = envProps;
    }

    private void initialize() throws CompilationException {
        if (isInitialized) return;

        if (envProps == null || envProps.getWorldShape() == null || envProps.getWorldShape().length == 0) {
            throw new CompilationException("The program contains instructions, which require a world context for layout, but no environment properties were provided.");
        }

        int dims = envProps.getWorldShape().length;
        this.currentPos = new int[dims];
        this.currentDv = new int[dims];
        this.currentDv[0] = 1;
        this.basePos = new int[dims];
        this.anchorPos = new int[dims];
        this.isInitialized = true;
    }

    /**
     * Returns the environment this layout is being built for. Directive handlers read the world
     * shape through it, for example to expand a wildcard into one coordinate per cell along an axis.
     *
     * @return the environment properties, or {@code null} if none were supplied
     */
    public EnvironmentProperties getEnvProps() {
        return envProps;
    }

    /**
     * Returns the write cursor: the relative coordinate the next placed cell will occupy.
     *
     * @return the context's own cursor array, not a copy
     * @throws CompilationException if the cursor still has to be created and no world shape is
     *         available.
     */
    public int[] currentPos() throws CompilationException {
        initialize();
        return currentPos;
    }

    /**
     * Moves the write cursor. The array is stored by reference, so a caller that keeps mutating
     * its own array must pass a copy.
     *
     * @param p The new cursor coordinate, relative to the program origin.
     * @throws CompilationException if the cursor still has to be created and no world shape is
     *         available.
     */
    public void setCurrentPos(int[] p) throws CompilationException {
        initialize();
        this.currentPos = p;
    }

    /**
     * Returns the direction vector added to the cursor after every placed cell. It starts as a
     * single step along the first axis and is changed by direction directives.
     *
     * @return the context's own direction array, not a copy
     * @throws CompilationException if the cursor still has to be created and no world shape is
     *         available.
     */
    public int[] currentDv() throws CompilationException {
        initialize();
        return currentDv;
    }

    /**
     * Sets the direction in which the cursor advances after every placed cell. The array is
     * stored by reference.
     *
     * @param dv The new direction vector.
     * @throws CompilationException if the cursor still has to be created and no world shape is
     *         available.
     */
    public void setCurrentDv(int[] dv) throws CompilationException {
        initialize();
        this.currentDv = dv;
    }

    /**
     * Returns the position that origin directives are resolved against: the cursor position at
     * which the innermost enclosing context was entered, and the zero vector outside any such
     * context.
     *
     * @return the context's own base position array, not a copy
     * @throws CompilationException if the cursor still has to be created and no world shape is
     *         available.
     */
    public int[] basePos() throws CompilationException {
        initialize();
        return basePos;
    }

    /**
     * Sets the position that origin directives are resolved against. The array is stored by
     * reference.
     *
     * @param p The new base position, relative to the program origin.
     * @throws CompilationException if the cursor still has to be created and no world shape is
     *         available.
     */
    public void setBasePos(int[] p) throws CompilationException {
        initialize();
        this.basePos = p;
    }

    /**
     * Returns the position most recently established by an origin directive, recorded alongside
     * the cursor move that directive caused.
     *
     * @return the context's own anchor array, not a copy
     * @throws CompilationException if the cursor still has to be created and no world shape is
     *         available.
     */
    public int[] anchorPos() throws CompilationException {
        initialize();
        return anchorPos;
    }

    /**
     * Records the position established by an origin directive. The array is stored by reference.
     *
     * @param p The anchor position, relative to the program origin.
     * @throws CompilationException if the cursor still has to be created and no world shape is
     *         available.
     */
    public void setAnchorPos(int[] p) throws CompilationException {
        initialize();
        this.anchorPos = p;
    }

    /**
     * Returns the stack of saved base positions. Context directives push the current base position
     * before entering a nested context and pop it again on leaving, so handlers manipulate it
     * directly.
     *
     * @return the live stack, not a copy
     */
    public Deque<int[]> basePosStack() {
        return basePosStack;
    }

    /**
     * Returns the stack of saved direction vectors, pushed and popped by the same context
     * directives that use the base position stack.
     *
     * @return the live stack, not a copy
     */
    public Deque<int[]> dvStack() {
        return dvStack;
    }

    private String coordToStringKey(int[] coord) {
        return Arrays.stream(coord).mapToObj(String::valueOf).collect(Collectors.joining("|"));
    }

    /**
     * Places an instruction opcode at the cursor: the cell receives the next linear address and
     * the cursor advances by the current direction vector.
     *
     * @param src The source information recorded for the placed cell; may be null.
     * @throws CompilationException if the coordinate is already occupied, or if the cursor still
     *         has to be created and no world shape is available.
     */
    public void placeOpcode(SourceInfo src) throws CompilationException {
        initialize();
        placeAtCurrent(src);
    }

    /**
     * Places a single operand cell at the cursor, with the same address assignment and cursor
     * advance as an opcode. A multi-component operand is placed one component per call.
     *
     * @param src The source information recorded for the placed cell; may be null.
     * @throws CompilationException if the coordinate is already occupied, or if the cursor still
     *         has to be created and no world shape is available.
     */
    public void placeOperand(SourceInfo src) throws CompilationException {
        initialize();
        placeAtCurrent(src);
    }

    /**
     * Places a whole instruction: the opcode cell, then one cell per argument of the
     * signature, where a vector argument takes one cell per dimension of the world.
     *
     * @param opcode    The opcode name, for the error a vector argument raises without a world.
     * @param signature The instruction's ISA signature, listing its argument kinds in order.
     * @param src       The source information recorded for every placed cell; may be null.
     * @throws CompilationException if a coordinate is already occupied, or if the instruction
     *         needs a world shape and none was supplied.
     */
    public void placeInstruction(String opcode, IInstructionSet.Signature signature, SourceInfo src) throws CompilationException {
        placeOpcode(src);
        for (IInstructionSet.ArgKind kind : signature.argumentTypes()) {
            if (kind == IInstructionSet.ArgKind.VECTOR) {
                if (envProps == null || envProps.getWorldShape() == null || envProps.getWorldShape().length == 0) {
                    throw new CompilationException("Instruction " + opcode + " requires vector arguments, which need a world context, but no environment properties were provided.", src);
                }
                for (int k = 0; k < envProps.getWorldShape().length; k++) {
                    placeOperand(src);
                }
            } else {
                placeOperand(src);
            }
        }
    }

    /**
     * Places a label at the current position.
     * Labels occupy space in the grid, like Tierra and Avida templates do.
     *
     * @param src The source information for error reporting.
     * @throws CompilationException if the position is already occupied.
     */
    public void placeLabel(SourceInfo src) throws CompilationException {
        initialize();
        placeAtCurrent(src);
    }

    private void placeAtCurrent(SourceInfo src) throws CompilationException {
        String coordKey = coordToStringKey(currentPos);
        if (coordToLinear.containsKey(coordKey)) {
            Integer oldLinearAddress = coordToLinear.get(coordKey);
            SourceInfo oldSource = sourceMap.get(oldLinearAddress);
            String currentLocation = String.format("%s:%d", src != null ? src.fileName() : "unknown", src != null ? src.lineNumber() : 0);
            String originalLocation = String.format("%s:%d", oldSource != null ? oldSource.fileName() : "unknown", oldSource != null ? oldSource.lineNumber() : 0);
            throw new CompilationException(String.format(
                "Address conflict: Coordinate %s is already occupied by an instruction at %s. " +
                "Cannot place new item at %s.",
                Arrays.toString(currentPos), originalLocation, currentLocation
            ));
        }
        
        linearToCoord.put(linearAddress, Nd.copy(currentPos));
        coordToLinear.put(coordKey, linearAddress);
        sourceMap.put(linearAddress, src);
        linearAddress++;
        currentPos = Nd.add(currentPos, currentDv);
    }

    /**
     * Returns the mapping from linear address to the relative coordinate of that cell, filled as
     * items are placed. Each coordinate is a copy taken at placement time, so it is unaffected by
     * later cursor moves.
     *
     * @return the live map, not a copy
     */
    public Map<Integer, int[]> linearToCoord() {
        return linearToCoord;
    }

    /**
     * Returns the inverse of the address mapping. Its keys are coordinates rendered as their
     * components joined by '|', because an {@code int[]} key would be compared by identity.
     *
     * @return the live map, not a copy
     */
    public Map<String, Integer> coordToLinear() {
        return coordToLinear;
    }

    /**
     * Returns the mapping from linear address to the source information of the item placed there.
     *
     * @return the live map, not a copy; a value is {@code null} where the placing item carried no
     *         source information
     */
    public Map<Integer, SourceInfo> sourceMap() {
        return sourceMap;
    }

    /**
     * Returns the molecules to be written into the world before execution, keyed by relative
     * coordinate. Placement directive handlers fill it directly; these cells are not placed
     * through the cursor and therefore have no linear address.
     *
     * @return the live map, not a copy
     */
    public Map<int[], PlacedMolecule> initialWorldObjects() {
        return initialWorldObjects;
    }

    /**
     * Returns the number of cells placed so far.
     *
     * @return that count, which is also the linear address the next placed cell will receive
     */
    public int linearAddress() {
        return linearAddress;
    }
}
