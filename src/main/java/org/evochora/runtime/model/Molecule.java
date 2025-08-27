// src/main/java/org/evochora/world/Molecule.java
package org.evochora.runtime.model;

import org.evochora.runtime.Config;

/**
 * Represents a molecule in the environment, with a type and a value.
 * @param type The type of the molecule.
 * @param value The value of the molecule.
 */
public record Molecule(int type, int value) {

    /**
     * Converts the molecule to its integer representation.
     * This logic prevents DATA:0 or STRUCTURE:0 from being incorrectly
     * stored as the integer 0 (reserved for CODE:0).
     * @return The integer representation of the molecule.
     */
    public int toInt() {
        if (this.value() == 0 && this.type() == Config.TYPE_CODE) {
            return 0;
        }
        // Otherwise, the type is always combined with the value.
        return this.type() | (this.value() & Config.VALUE_MASK);
    }

    /**
     * Gets the scalar value of the molecule.
     * @return The scalar value.
     */
    public int toScalarValue() {
        return this.value();
    }

    /**
     * Checks if the molecule is empty (CODE:0).
     * @return true if the molecule is empty, false otherwise.
     */
    public boolean isEmpty() {
        return this.type() == Config.TYPE_CODE && this.value() == 0;
    }

    /**
     * Creates a molecule from its integer representation.
     * @param fullValue The integer representation of the molecule.
     * @return The created molecule.
     */
    public static Molecule fromInt(int fullValue) {
        if (fullValue == 0) {
            return new Molecule(Config.TYPE_CODE, 0);
        }
        int type = fullValue & Config.TYPE_MASK;
        int rawValue = fullValue & Config.VALUE_MASK;
        if ((rawValue & (1 << (Config.VALUE_BITS - 1))) != 0) {
            rawValue |= ~((1 << Config.VALUE_BITS) - 1);
        }
        return new Molecule(type, rawValue);
    }

    /**
     * Gets the owner of this molecule from the environment.
     * @param environment The environment.
     * @param coord The coordinate of the molecule.
     * @return The owner ID.
     */
    public int getOwnerFrom(Environment environment, int... coord) {
        return environment.getOwnerId(coord);
    }

    /**
     * Sets the owner of this molecule in the environment.
     * @param environment The environment.
     * @param ownerId The owner ID.
     * @param coord The coordinate of the molecule.
     */
    public void setOwnerIn(Environment environment, int ownerId, int... coord) {
        environment.setOwnerId(ownerId, coord);
    }

    /**
     * Gets the owner of the molecule at the specified coordinates.
     * @param environment The environment.
     * @param coord The coordinate of the molecule.
     * @return The owner ID.
     */
    public static int getOwner(Environment environment, int... coord) {
        return environment.getOwnerId(coord);
    }

    /**
     * Sets the owner of the molecule at the specified coordinates.
     * @param environment The environment.
     * @param ownerId The owner ID.
     * @param coord The coordinate of the molecule.
     */
    public static void setOwner(Environment environment, int ownerId, int... coord) {
        environment.setOwnerId(ownerId, coord);
    }

    @Override
    public String toString() {
        String typePrefix = switch (this.type()) {
            case Config.TYPE_CODE -> "CODE";
            case Config.TYPE_DATA -> "DATA";
            case Config.TYPE_ENERGY -> "ENERGY";
            case Config.TYPE_STRUCTURE -> "STRUCTURE";
            default -> "UNKNOWN";
        };
        return typePrefix + ":" + this.toScalarValue();
    }
}