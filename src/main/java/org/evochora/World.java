// src/main/java/org/evochora/World.java
package org.evochora;

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
        for (int dim : shape) {
            size *= dim;
        }
        this.grid = new int[size];
        Arrays.fill(this.grid, Config.OP_NOP);

        this.strides = new int[shape.length];
        int stride = 1;
        for (int i = shape.length - 1; i >= 0; i--) {
            this.strides[i] = stride;
            stride *= shape[i];
        }
    }

    /**
     * NEU: Öffentliche Methode, die von Organismen zur Berechnung von
     * n-dimensionalen Koordinaten verwendet wird.
     * @param coord Die zu normalisierende Koordinate.
     * @return Die normalisierte Koordinate.
     *
     * TODO: make World.getNormalizedCoordinate() n-dimensional
     */
    public int[] getNormalizedCoordinate(int... coord) {
        if (coord.length != this.shape.length) {
            // Wirft einen Fehler, wenn die Dimensionen nicht übereinstimmen.
            throw new IllegalArgumentException("Coordinate dimensions " + coord.length +
                    " do not match world dimensions " + this.shape.length);
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
                    return -1; // Außerhalb der Grenzen
                }
            }
        }

        int flatIndex = 0;
        for (int i = 0; i < shape.length; i++) {
            flatIndex += normalizedCoord[i] * this.strides[i];
        }
        return flatIndex;
    }

    // TODO: return instance of abstract class Symbol that can be Code, Data, Structure or Energy
    public int getSymbol(int... coord) {
        int index = getFlatIndex(coord);
        if (index == -1) {
            return 0;
        }
        return this.grid[index];
    }

    public void setSymbol(int value, int... coord) {
        int index = getFlatIndex(coord);
        if (index != -1) {
            this.grid[index] = value;
        }
    }

    public int[] getShape() {
        return Arrays.copyOf(this.shape, this.shape.length);
    }
}