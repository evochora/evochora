// src/main/java/org/evochora/world/World.java
package org.evochora.world;

import org.evochora.Config;
import java.util.Arrays;

public class World {
    private final int[] shape;
    private final boolean isToroidal;
    private final int[] grid;
    private final int[] strides;

    public World(int[] shape, boolean toroidal) {
        this.shape = shape;
        this.isToroidal = toroidal;
        int size = 1;
        for (int dim : shape) { size *= dim; }
        this.grid = new int[size];
        Arrays.fill(this.grid, 0);
        this.strides = new int[shape.length];
        int stride = 1;
        for (int i = shape.length - 1; i >= 0; i--) {
            this.strides[i] = stride;
            stride *= shape[i];
        }
    }

    public int[] getNormalizedCoordinate(int... coord) {
        if (coord.length != this.shape.length) {
            throw new IllegalArgumentException("Coordinate dimensions do not match world dimensions.");
        }
        int[] normalized = new int[coord.length];
        for (int i = 0; i < coord.length; i++) {
            int c = coord[i];
            if (isToroidal) {
                c = Math.floorMod(c, this.shape[i]);
            }
            normalized[i] = c;
        }
        return normalized;
    }

    private int getFlatIndex(int... coord) {
        int[] normalizedCoord = getNormalizedCoordinate(coord);
        if (!isToroidal) {
            for(int i = 0; i < shape.length; i++) {
                if (normalizedCoord[i] < 0 || normalizedCoord[i] >= shape[i]) {
                    return -1;
                }
            }
        }
        int flatIndex = 0;
        for (int i = 0; i < shape.length; i++) {
            flatIndex += normalizedCoord[i] * this.strides[i];
        }
        return flatIndex;
    }

    public Symbol getSymbol(int... coord) {
        int index = getFlatIndex(coord);
        if (index == -1) {
            return Symbol.fromInt(0);
        }
        return Symbol.fromInt(this.grid[index]);
    }

    public void setSymbol(Symbol symbol, int... coord) {
        int index = getFlatIndex(coord);
        if (index != -1) {
            this.grid[index] = symbol.toInt();
        }
    }

    public int[] getShape() {
        return Arrays.copyOf(this.shape, this.shape.length);
    }
}