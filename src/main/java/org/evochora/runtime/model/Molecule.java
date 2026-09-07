// src/main/java/org/evochora/world/Molecule.java
package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a molecule in the environment, with a type and a value.
 * <p>
 * <strong>{@code CODE:0} is the empty cell</strong> and carries two invariants that this record does
 * not enforce itself: an empty cell has <em>no marker</em> and <em>no owner</em>. Both hold because
 * every path that writes into the grid upholds them, not because construction is checked:
 * <ul>
 *   <li>The world-interaction instructions clear the marker when writing {@code CODE:0}, whatever the
 *       organism's marker register holds.</li>
 *   <li>Reading a molecule consumes it and clears the cell's ownership.</li>
 *   <li>The death handlers may leave an organism's cells empty but owned; the simulation clears that
 *       organism's ownership immediately afterwards, which is why handlers must not clear it
 *       themselves.</li>
 *   <li>{@link #fromInt(int)} repairs a marked {@code CODE:0} it finds in stored data and reports it,
 *       since such a value can only come from outside these paths.</li>
 * </ul>
 * Anything that gains write access to the grid has to uphold them too. A marked or owned empty cell
 * is not merely untidy: ownership decides what a newborn inherits and which cells the mutation
 * operators may write into, and an empty cell that carries a marker survives in memory but not
 * through serialization — a run would then continue differently after a resume than without one.
 *
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
     * Extracts the sign-extended value component from a packed molecule integer.
     * <p>
     * The value occupies the lowest {@link Config#VALUE_BITS} bits in two's complement. The raw bit
     * pattern on its own is unsigned, so a stored {@code -1} reads as {@code 1048575} unless it is
     * sign-extended. This method is the single place where that conversion is implemented.
     * <p>
     * It is static and takes the packed integer rather than a {@code Molecule}, so that callers on
     * the instruction-execution path can read the value without allocating a record.
     *
     * @param moleculeInt The packed molecule integer.
     * @return The value component, sign-extended to a full {@code int}.
     */
    public static int extractSignedValue(int moleculeInt) {
        int rawValue = moleculeInt & Config.VALUE_MASK;
        if ((rawValue & (1 << (Config.VALUE_BITS - 1))) != 0) {
            rawValue |= ~((1 << Config.VALUE_BITS) - 1);
        }
        return rawValue;
    }

    /**
     * Reports whether two molecule types may take part in the same scalar value operation.
     * <p>
     * Two types are value-compatible when computing with or comparing their values is meaningful
     * under strict typing. Identical types always are. Beyond that, {@link Config#TYPE_DATA} and
     * {@link Config#TYPE_STATE} are interchangeable: both hold a plain number and differ only in
     * whether the cell belongs to an organism's genome or to the memory the organism writes while
     * it runs. A value loaded from a state cell therefore computes and compares against the
     * {@code DATA} constants in the organism's own code.
     * <p>
     * The operations that ask this question are the scalar arithmetic and MIN/MAX paths of
     * {@code ArithmeticInstruction}, the two-operand and shift paths of {@code BitwiseInstruction},
     * the value comparisons of {@code ConditionalInstruction}, and the marker operand of the
     * {@code SMR*} and {@code CMR*} instructions in {@code StateInstruction}.
     * <p>
     * Compatibility does not make the two types equal: the result of an operation keeps the type of
     * its first operand, and type comparisons ({@code IFT*}, {@code INT*}) as well as type scans
     * ({@code SNT*}) still match exactly.
     * <p>
     * It is static and takes the type bits as they are stored in a packed molecule integer, so that
     * callers on the instruction-execution path can ask without allocating a record. Equality is
     * the fast path.
     *
     * @param typeA The first molecule type, a {@code Config.TYPE_*} constant.
     * @param typeB The second molecule type, a {@code Config.TYPE_*} constant.
     * @return true if values of the two types may be combined or compared, false otherwise.
     */
    public static boolean areValueCompatible(int typeA, int typeB) {
        if (typeA == typeB) {
            return true;
        }
        return (typeA == Config.TYPE_DATA && typeB == Config.TYPE_STATE)
                || (typeA == Config.TYPE_STATE && typeB == Config.TYPE_DATA);
    }

    /**
     * Computes the form in which a molecule an organism writes into the environment is stored.
     * <p>
     * A write carries the organism's Molecule Marker Register into the grid, and the marker
     * register also decides whether the written molecule is part of a genome or the organism's
     * own memory. This method is the single definition of that rule:
     * <ul>
     *   <li>{@code CODE:0} is the empty cell and is always stored with marker 0, whatever the
     *       marker register holds. A written {@code CODE:0} that already carries marker bits
     *       violates that invariant before it reaches the grid and is reported as such.</li>
     *   <li>{@link Config#TYPE_DATA} written while the marker register is 0 is stored as
     *       {@link Config#TYPE_STATE} with the same value: marker 0 is the ephemeral class, and
     *       what an organism writes in it is its own working memory rather than genetic material.
     *       {@code STATE} cells are excluded from the genome hash.</li>
     *   <li>Every other molecule keeps its type; the marker register is stamped into it.</li>
     * </ul>
     * The value is carried over unchanged, so a stored molecule differs from the written one at
     * most in its type and marker bits.
     * <p>
     * Everything that has to know what a write ends up as goes through this method: the write
     * paths of the world-interaction instructions, which store the result, and the thermodynamic
     * policies, which price it, so that write costs and read costs both key on what a cell
     * actually holds.
     * <p>
     * It is static and works on packed molecule integers, so that callers on the
     * instruction-execution path need no record allocation.
     *
     * @param moleculeInt The packed molecule integer the organism writes; any marker bits it
     *                    carries are replaced by the marker register.
     * @param markerRegister The writing organism's marker register value (0-15).
     * @return The packed molecule integer as it is stored in the environment.
     */
    public static int storedFormOfWrite(int moleculeInt, int markerRegister) {
        int type = moleculeInt & Config.TYPE_MASK;
        int value = moleculeInt & Config.VALUE_MASK;
        if (type == Config.TYPE_CODE && value == 0) {
            int marker = (moleculeInt & Config.MARKER_MASK) >>> Config.MARKER_SHIFT;
            if (marker != 0) {
                LOG.error("CODE:0 molecule with marker={} - fixing to marker=0", marker,
                          new IllegalStateException("Invariant violation: CODE:0 must have marker=0"));
            }
            return 0;
        }
        int storedType = (type == Config.TYPE_DATA && markerRegister == 0) ? Config.TYPE_STATE : type;
        return ((markerRegister & Config.MARKER_VALUE_MASK) << Config.MARKER_SHIFT) | storedType | value;
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
        // Use unsigned shift (>>>) to avoid sign-extension when bit 31 is set (marker >= 8)
        int marker = (fullValue & Config.MARKER_MASK) >>> Config.MARKER_SHIFT;
        int type = fullValue & Config.TYPE_MASK;
        int rawValue = extractSignedValue(fullValue);
        
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
     * The names it accepts are those registered in {@link MoleculeTypeRegistry}. This is the
     * tolerant form of the lookup, for callers that read type names from configuration and want
     * to report an unknown name themselves instead of failing.
     *
     * @param typeName The name of the molecule type (case-insensitive).
     * @return An Optional containing the integer constant if found, or empty if the name is null
     *         or names no registered type.
     */
    public static java.util.Optional<Integer> getTypeConstantByName(String typeName) {
        return MoleculeTypeRegistry.findType(typeName);
    }

    @Override
    public String toString() {
        String typePrefix = MoleculeTypeRegistry.typeToName(this.type());
        return typePrefix + ":" + this.toScalarValue() + " M:" + this.marker();
    }
}