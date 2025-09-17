package org.evochora.datapipeline.api.contracts;

import java.util.Arrays;

/**
 * A wrapper object to represent values on the Data Stack, which can be either a 32-bit integer
 * literal or an n-dimensional position vector.
 */
public class StackValue {

    private StackValueType type;
    private int literalValue;
    private int[] positionValue;

    /**
     * Default constructor for deserialization.
     */
    public StackValue() {
    }

    // Getters and setters

    public StackValueType getType() {
        return type;
    }

    public void setType(StackValueType type) {
        this.type = type;
    }

    public int getLiteralValue() {
        return literalValue;
    }

    public void setLiteralValue(int literalValue) {
        this.literalValue = literalValue;
    }

    public int[] getPositionValue() {
        return positionValue;
    }

    public void setPositionValue(int[] positionValue) {
        this.positionValue = positionValue;
    }

    @Override
    public String toString() {
        return "StackValue{" +
                "type=" + type +
                ", literalValue=" + literalValue +
                ", positionValue=" + Arrays.toString(positionValue) +
                '}';
    }
}
