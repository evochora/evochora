package org.evochora.compiler.backend.layout;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.runtime.model.EnvironmentProperties;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

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

    public EnvironmentProperties getEnvProps() {
        return envProps;
    }

    public int[] currentPos() throws CompilationException {
        initialize();
        return currentPos;
    }

    public void setCurrentPos(int[] p) throws CompilationException {
        initialize();
        this.currentPos = p;
    }

    public int[] currentDv() throws CompilationException {
        initialize();
        return currentDv;
    }

    public void setCurrentDv(int[] dv) throws CompilationException {
        initialize();
        this.currentDv = dv;
    }

    public int[] basePos() throws CompilationException {
        initialize();
        return basePos;
    }

    public void setBasePos(int[] p) throws CompilationException {
        initialize();
        this.basePos = p;
    }

    public int[] anchorPos() throws CompilationException {
        initialize();
        return anchorPos;
    }

    public void setAnchorPos(int[] p) throws CompilationException {
        initialize();
        this.anchorPos = p;
    }

    public Deque<int[]> basePosStack() {
        return basePosStack;
    }

    public Deque<int[]> dvStack() {
        return dvStack;
    }

    private String coordToStringKey(int[] coord) {
        return Arrays.stream(coord).mapToObj(String::valueOf).collect(Collectors.joining("|"));
    }

    public void placeOpcode(SourceInfo src) throws CompilationException {
        initialize();
        placeAtCurrent(src);
    }

    public void placeOperand(SourceInfo src) throws CompilationException {
        initialize();
        placeAtCurrent(src);
    }

    /**
     * Places a label at the current position.
     * Labels now occupy space in the grid (like Tierra/Avida templates).
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

    public Map<Integer, int[]> linearToCoord() {
        return linearToCoord;
    }

    public Map<String, Integer> coordToLinear() {
        return coordToLinear;
    }

    public Map<Integer, SourceInfo> sourceMap() {
        return sourceMap;
    }

    public Map<int[], PlacedMolecule> initialWorldObjects() {
        return initialWorldObjects;
    }

    public int linearAddress() {
        return linearAddress;
    }
}
