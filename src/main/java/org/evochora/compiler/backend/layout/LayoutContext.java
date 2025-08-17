package org.evochora.compiler.backend.layout;

import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.SourceInfo;

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
    private final Map<String, Integer> coordToLinear = new HashMap<>(); // GEÄNDERT
    private final Map<String, Integer> labelToAddress = new HashMap<>();
    private final Map<Integer, SourceInfo> sourceMap = new HashMap<>();
    private final Map<int[], PlacedMolecule> initialWorldObjects = new HashMap<>();
    private int linearAddress = 0;

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

    public void onFileChanged(String newFile) {
        if (lastFile != null) {
            basePosStack.push(Nd.copy(basePos));
            dvStack.push(Nd.copy(currentDv));
        }
        basePos = Nd.copy(anchorPos);
        lastFile = newFile;
    }

    private String coordToStringKey(int[] coord) {
        return Arrays.stream(coord).mapToObj(String::valueOf).collect(Collectors.joining("|"));
    }

    public void placeOpcode(SourceInfo src) {
        linearToCoord.put(linearAddress, Nd.copy(currentPos));
        coordToLinear.put(coordToStringKey(currentPos), linearAddress); // GEÄNDERT
        sourceMap.put(linearAddress, src);
        linearAddress++;
        currentPos = Nd.add(currentPos, currentDv);
    }

    public void placeOperand(SourceInfo src) {
        linearToCoord.put(linearAddress, Nd.copy(currentPos));
        coordToLinear.put(coordToStringKey(currentPos), linearAddress); // GEÄNDERT
        sourceMap.put(linearAddress, src);
        linearAddress++;
        currentPos = Nd.add(currentPos, currentDv);
    }

    public Map<Integer, int[]> linearToCoord() { return linearToCoord; }
    public Map<String, Integer> coordToLinear() { return coordToLinear; } // GEÄNDERT
    public Map<String, Integer> labelToAddress() { return labelToAddress; }
    public Map<Integer, SourceInfo> sourceMap() { return sourceMap; }
    public Map<int[], PlacedMolecule> initialWorldObjects() { return initialWorldObjects; }
    public int linearAddress() { return linearAddress; }
}
