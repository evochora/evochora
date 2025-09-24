package org.evochora.datapipeline.api.contracts;

import java.util.Arrays;

/**
 * Represents the state of a single cell in the world.
 */
public class RawCellState {

    private int[] position;
    private int type;
    private int value;
    private int ownerId;

    /**
     * Default constructor for deserialization.
     */
    public RawCellState() {
    }

    // Getters and setters

    public int[] getPosition() {
        return position;
    }

    public void setPosition(int[] position) {
        this.position = position;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public int getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(int ownerId) {
        this.ownerId = ownerId;
    }

    @Override
    public String toString() {
        return "RawCellState{" +
                "position=" + Arrays.toString(position) +
                ", type=" + type +
                ", value=" + value +
                ", ownerId=" + ownerId +
                '}';
    }
}
