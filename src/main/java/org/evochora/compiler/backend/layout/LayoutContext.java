package org.evochora.compiler.backend.layout;

import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.CompilationException;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mutable context for the layout phase. Holds current position/direction, include stacks,
 * and all output mappings being built.
 */
public final class LayoutContext {

    private int[] currentPos;
    private int[] currentDv;
    private int[] basePos;
    private int[] anchorPos;
    private final Deque<int[]> basePosStack = new ArrayDeque<>();
    private final Deque<int[]> dvStack = new ArrayDeque<>();
    private String lastFile = null;

    private final Map<Integer, int[]> linearToCoord = new HashMap<>();
    private final Map<String, Integer> coordToLinear = new HashMap<>();
    private final Map<String, Integer> labelToAddress = new HashMap<>();
    private final Map<Integer, SourceInfo> sourceMap = new HashMap<>();
    private final Map<int[], PlacedMolecule> initialWorldObjects = new HashMap<>();
    private int linearAddress = 0;

    /**
     * Constructs a new layout context.
     * @param dims The number of dimensions in the world.
     */
    public LayoutContext(int dims) {
        this.currentPos = new int[dims];
        this.currentDv = new int[dims];
        this.currentDv[0] = 1;
        this.basePos = new int[dims];
        this.anchorPos = new int[dims];
    }

    public int[] currentPos() { return currentPos; }
    public void setCurrentPos(int[] p) { this.currentPos = p; }
    public int[] currentDv() { return currentDv; }
    public void setCurrentDv(int[] dv) { this.currentDv = dv; }
    public int[] basePos() { return basePos; }
    public void setBasePos(int[] p) { this.basePos = p; }
    public int[] anchorPos() { return anchorPos; }
    public void setAnchorPos(int[] p) { this.anchorPos = p; }

    public Deque<int[]> basePosStack() { return basePosStack; }
    public Deque<int[]> dvStack() { return dvStack; }

    private String coordToStringKey(int[] coord) {
        return Arrays.stream(coord).mapToObj(String::valueOf).collect(Collectors.joining("|"));
    }

    /**
     * Places an opcode at the current position and advances the cursor.
     * If coordinate is already occupied, throws a CompilationException with detailed information.
     * @param src The source information for the opcode.
     * @throws CompilationException if the coordinate is already occupied.
     */
    public void placeOpcode(SourceInfo src) throws CompilationException {
        String coordKey = coordToStringKey(currentPos);
        
        if (coordToLinear.containsKey(coordKey)) {
            // Koordinate bereits besetzt - detaillierte Fehlermeldung
            Integer oldLinearAddress = coordToLinear.get(coordKey);
            SourceInfo oldSource = sourceMap.get(oldLinearAddress);
            
            String currentLocation = String.format("%s:%d", 
                src != null ? src.fileName() : "unknown", 
                src != null ? src.lineNumber() : 0);
            
            String originalLocation = String.format("%s:%d", 
                oldSource != null ? oldSource.fileName() : "unknown", 
                oldSource != null ? oldSource.lineNumber() : 0);
            
            throw new CompilationException(String.format(
                "Address conflict: Coordinate %s is already occupied by an instruction at %s. " +
                "Cannot place new opcode instruction at %s.",
                Arrays.toString(currentPos), originalLocation, currentLocation
            ));
        }
        
        // Normale neue Koordinate
        linearToCoord.put(linearAddress, Nd.copy(currentPos));
        coordToLinear.put(coordKey, linearAddress);
        sourceMap.put(linearAddress, src);
        linearAddress++;
        
        currentPos = Nd.add(currentPos, currentDv);
    }

    /**
     * Places an operand at the current position and advances the cursor.
     * If coordinate is already occupied, throws a CompilationException with detailed information.
     * @param src The source information for the operand.
     * @throws CompilationException if the coordinate is already occupied.
     */
    public void placeOperand(SourceInfo src) throws CompilationException {
        String coordKey = coordToStringKey(currentPos);
        
        if (coordToLinear.containsKey(coordKey)) {
            // Koordinate bereits besetzt - detaillierte Fehlermeldung
            Integer oldLinearAddress = coordToLinear.get(coordKey);
            SourceInfo oldSource = sourceMap.get(oldLinearAddress);
            
            String currentLocation = String.format("%s:%d", 
                src != null ? src.fileName() : "unknown", 
                src != null ? src.lineNumber() : 0);
            
            String originalLocation = String.format("%s:%d", 
                oldSource != null ? oldSource.fileName() : "unknown", 
                oldSource != null ? oldSource.lineNumber() : 0);
            
            throw new CompilationException(String.format(
                "Address conflict: Coordinate %s is already occupied by an instruction at %s. " +
                "Cannot place new operand instruction at %s.",
                Arrays.toString(currentPos), originalLocation, currentLocation
            ));
        }
        
        // Normale neue Koordinate
        linearToCoord.put(linearAddress, Nd.copy(currentPos));
        coordToLinear.put(coordKey, linearAddress);
        sourceMap.put(linearAddress, src);
        linearAddress++;
        
        currentPos = Nd.add(currentPos, currentDv);
    }

    public Map<Integer, int[]> linearToCoord() { return linearToCoord; }
    public Map<String, Integer> coordToLinear() { return coordToLinear; }
    public Map<String, Integer> labelToAddress() { return labelToAddress; }
    public Map<Integer, SourceInfo> sourceMap() { return sourceMap; }
    public Map<int[], PlacedMolecule> initialWorldObjects() { return initialWorldObjects; }
    public int linearAddress() { return linearAddress; }
}
