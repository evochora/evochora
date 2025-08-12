// src/main/java/org/evochora/world/World.java
package org.evochora.runtime.model;

import java.util.Arrays;

public class World {
    private final int[] shape;
    private final boolean isToroidal;
    private final int[] grid;
    private final int[] ownerGrid;
    private final int[] strides;

    public World(int[] shape, boolean toroidal) {
        this.shape = shape;
        this.isToroidal = toroidal;
        int size = 1;
        for (int dim : shape) { size *= dim; }
        this.grid = new int[size];
        Arrays.fill(this.grid, 0);
        this.ownerGrid = new int[size];
        Arrays.fill(this.ownerGrid, 0);
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

    public Molecule getMolecule(int... coord) {
        int index = getFlatIndex(coord);
        if (index == -1) {
            return org.evochora.runtime.model.Molecule.fromInt(0);
        }
        return org.evochora.runtime.model.Molecule.fromInt(this.grid[index]);
    }

    public void setMolecule(Molecule molecule, int... coord) {
        int index = getFlatIndex(coord);
        if (index != -1) {
            this.grid[index] = molecule.toInt();
        }
    }

    // Overload: set molecule and owner in one call; owner is only updated for non-empty symbols
    public void setMolecule(Molecule molecule, int ownerId, int... coord) {
        int index = getFlatIndex(coord);
        if (index != -1) {
            int packed = molecule.toInt();
            this.grid[index] = packed;
            if (packed != 0) { // only update owner for non-empty cells
                this.ownerGrid[index] = ownerId;
            }
        }
    }

    // Owner grid accessors
    public int getOwnerId(int... coord) {
        int index = getFlatIndex(coord);
        if (index == -1) {
            return 0;
        }
        return this.ownerGrid[index];
    }

    public void setOwnerId(int ownerId, int... coord) {
        int index = getFlatIndex(coord);
        if (index != -1) {
            this.ownerGrid[index] = ownerId;
        }
    }

    public void clearOwner(int... coord) {
        setOwnerId(0, coord);
    }


    public int[] getShape() {
        return Arrays.copyOf(this.shape, this.shape.length);
    }
}