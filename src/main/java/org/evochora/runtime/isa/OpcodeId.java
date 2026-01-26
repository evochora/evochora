package org.evochora.runtime.isa;

/**
 * Helper class for computing and decomposing structured opcode IDs.
 *
 * <p>Opcode IDs use an 18-bit structure: {@code [FFFFFF][OOOOOO][VVVVVV]}
 * <ul>
 *   <li>Family: 6 bits (0-63), position 12-17</li>
 *   <li>Operation: 6 bits (0-63), position 6-11</li>
 *   <li>Variant: 6 bits (0-63), position 0-5</li>
 * </ul>
 *
 * <p>The structured format encodes semantic similarity: instructions within the same
 * family share related functionality, and variants represent different modes of the
 * same operation. This enables mutation plugins to make meaningful changes through
 * simple arithmetic on opcode IDs.
 *
 * <p>Formula: {@code opcode = (family * 4096) + (operation * 64) + variant}
 *
 * <p>This class is thread-safe as it contains only static methods and immutable constants.
 */
public final class OpcodeId {

    /** Number of bits used for the variant field. */
    public static final int VARIANT_BITS = 6;

    /** Number of bits used for the operation field. */
    public static final int OPERATION_BITS = 6;

    /** Number of bits used for the family field. */
    public static final int FAMILY_BITS = 6;

    /** Maximum valid variant value (2^6 - 1 = 63). */
    public static final int MAX_VARIANT = 63;

    /** Maximum valid operation value (2^6 - 1 = 63). */
    public static final int MAX_OPERATION = 63;

    /** Maximum valid family value (2^6 - 1 = 63). */
    public static final int MAX_FAMILY = 63;

    /** Multiplier for operation field: 2^6 = 64. */
    public static final int OPERATION_MULTIPLIER = 64;

    /** Multiplier for family field: 2^12 = 4096. */
    public static final int FAMILY_MULTIPLIER = 4096;

    private OpcodeId() {
        // Utility class - prevent instantiation
    }

    /**
     * Computes an opcode ID from family, operation, and variant components.
     *
     * @param family the instruction family (0-63)
     * @param operation the operation within the family (0-63)
     * @param variant the variant of the operation (0-63)
     * @return the computed opcode ID
     * @throws IllegalArgumentException if any parameter is out of valid range
     */
    public static int compute(int family, int operation, int variant) {
        if (family < 0 || family > MAX_FAMILY) {
            throw new IllegalArgumentException(
                    "Family must be between 0 and " + MAX_FAMILY + ", got: " + family);
        }
        if (operation < 0 || operation > MAX_OPERATION) {
            throw new IllegalArgumentException(
                    "Operation must be between 0 and " + MAX_OPERATION + ", got: " + operation);
        }
        if (variant < 0 || variant > MAX_VARIANT) {
            throw new IllegalArgumentException(
                    "Variant must be between 0 and " + MAX_VARIANT + ", got: " + variant);
        }

        return (family * FAMILY_MULTIPLIER) + (operation * OPERATION_MULTIPLIER) + variant;
    }

    /**
     * Extracts the family component from an opcode ID.
     *
     * @param opcodeId the opcode ID to decompose
     * @return the family component (0-63)
     */
    public static int extractFamily(int opcodeId) {
        return opcodeId / FAMILY_MULTIPLIER;
    }

    /**
     * Extracts the operation component from an opcode ID.
     *
     * @param opcodeId the opcode ID to decompose
     * @return the operation component (0-63)
     */
    public static int extractOperation(int opcodeId) {
        return (opcodeId / OPERATION_MULTIPLIER) % OPERATION_MULTIPLIER;
    }

    /**
     * Extracts the variant component from an opcode ID.
     *
     * @param opcodeId the opcode ID to decompose
     * @return the variant component (0-63)
     */
    public static int extractVariant(int opcodeId) {
        return opcodeId % OPERATION_MULTIPLIER;
    }
}
