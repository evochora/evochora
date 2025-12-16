// src/main/java/org/evochora/world/Molecule.java
package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a molecule in the environment, with a type and a value.
 * @param type The type of the molecule.
 * @param value The value of the molecule.
 * @param marker The marker of the molecule.
 */
public record Molecule(int type, int value, int marker) {
    
    private static final Logger LOG = LoggerFactory.getLogger(Molecule.class);

    /**
     * Convenience constructor for creating a molecule with a default marker of 0.
     * @param type The type of the molecule.
     * @param value The value of the molecule.
     */
    public Molecule(int type, int value) {
        this(type, value, 0);
    }

    /**
     * Converts the molecule to its integer representation.
     * This logic prevents DATA:0 or STRUCTURE:0 from being incorrectly
     * stored as the integer 0 (reserved for CODE:0).
     * @return The integer representation of the molecule.
     */
    public int toInt() {
        if (this.value() == 0 && this.type() == Config.TYPE_CODE && this.marker() == 0) {
            return 0;
        }
        // Otherwise, the type is always combined with the value.
        // Marker must be shifted to its correct bit position.
        return ((this.marker() & Config.MARKER_VALUE_MASK) << Config.MARKER_SHIFT)
             | this.type()
             | (this.value() & Config.VALUE_MASK);
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
        return this.type() == Config.TYPE_CODE && this.value() == 0 && this.marker() == 0;
    }

    /**
     * Creates a molecule from its integer representation.
     * @param fullValue The integer representation of the molecule.
     * @return The created molecule.
     */
    public static Molecule fromInt(int fullValue) {
        if (fullValue == 0) {
            return new Molecule(Config.TYPE_CODE, 0, 0);
        }
        // Extract marker and shift it back to 0-15 range
        int marker = (fullValue & Config.MARKER_MASK) >> Config.MARKER_SHIFT;
        int type = fullValue & Config.TYPE_MASK;
        int rawValue = fullValue & Config.VALUE_MASK;
        if ((rawValue & (1 << (Config.VALUE_BITS - 1))) != 0) {
            rawValue |= ~((1 << Config.VALUE_BITS) - 1);
        }
        
        // Invariant check: CODE:0 must have marker=0
        if (type == Config.TYPE_CODE && rawValue == 0 && marker != 0) {
            LOG.error("CODE:0 molecule with marker={} - fixing to marker=0", marker,
                      new IllegalStateException("Invariant violation: CODE:0 must have marker=0"));
            marker = 0;
        }
        
        return new Molecule(type, rawValue, marker);
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

    /**
     * Translates a molecule type name (e.g., "ENERGY") to its integer constant (e.g., Config.TYPE_ENERGY).
     * This method is the single source of truth for parsing type names from configuration.
     *
     * @param typeName The name of the molecule type (case-insensitive).
     * @return An Optional containing the integer constant if found, or empty if unknown.
     */
    public static java.util.Optional<Integer> getTypeConstantByName(String typeName) {
        if (typeName == null) {
            return java.util.Optional.empty();
        }
        return switch (typeName.toUpperCase()) {
            case "CODE" -> java.util.Optional.of(Config.TYPE_CODE);
            case "DATA" -> java.util.Optional.of(Config.TYPE_DATA);
            case "ENERGY" -> java.util.Optional.of(Config.TYPE_ENERGY);
            case "STRUCTURE" -> java.util.Optional.of(Config.TYPE_STRUCTURE);
            default -> java.util.Optional.empty();
        };
    }

    @Override
    public String toString() {
        String typePrefix = MoleculeTypeRegistry.typeToName(this.type());
        return typePrefix + ":" + this.toScalarValue() + " M:" + this.marker();
    }
}