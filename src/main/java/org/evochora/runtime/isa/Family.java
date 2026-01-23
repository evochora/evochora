package org.evochora.runtime.isa;

/**
 * Constants defining instruction family IDs for the structured opcode scheme.
 *
 * <p>Each family groups semantically related instructions and has a range of 4096 opcode IDs
 * (64 operations x 64 variants). Family IDs are used with {@link OpcodeId#compute(int, int, int)}
 * to generate structured opcode IDs.
 *
 * <p>Family ranges:
 * <ul>
 *   <li>SPECIAL (0): 0-4095 - NOP and reserved instructions</li>
 *   <li>ARITHMETIC (1): 4096-8191 - ADD, SUB, MUL, DIV, etc.</li>
 *   <li>BITWISE (2): 8192-12287 - AND, OR, XOR, NOT, shifts</li>
 *   <li>DATA (3): 12288-16383 - SET, PUSH, POP, stack operations</li>
 *   <li>CONDITIONAL (4): 16384-20479 - IF, comparisons</li>
 *   <li>CONTROL (5): 20480-24575 - JMP, CALL, RET</li>
 *   <li>ENVIRONMENT (6): 24576-28671 - PEEK, POKE</li>
 *   <li>STATE (7): 28672-32767 - SCAN, SEEK, FORK, etc.</li>
 *   <li>LOCATION (8): 32768-36863 - Location stack/register operations</li>
 *   <li>VECTOR (9): 36864-40959 - Vector manipulation</li>
 * </ul>
 *
 * <p>This class is thread-safe as it contains only static constants and methods.
 */
public final class Family {

    /** NOP and reserved instructions. Range: 0-1023. */
    public static final int SPECIAL = 0;

    /** Arithmetic operations: ADD, SUB, MUL, DIV, etc. Range: 1024-2047. */
    public static final int ARITHMETIC = 1;

    /** Bitwise operations: AND, OR, XOR, NOT, shifts. Range: 2048-3071. */
    public static final int BITWISE = 2;

    /** Data operations: SET, PUSH, POP, stack ops. Range: 3072-4095. */
    public static final int DATA = 3;

    /** Conditional operations: IF, comparisons. Range: 4096-5119. */
    public static final int CONDITIONAL = 4;

    /** Control flow operations: JMP, CALL, RET. Range: 5120-6143. */
    public static final int CONTROL = 5;

    /** Environment operations: PEEK, POKE. Range: 6144-7167. */
    public static final int ENVIRONMENT = 6;

    /** State operations: SCAN, SEEK, FORK, etc. Range: 28672-32767. */
    public static final int STATE = 7;

    /** Location operations: Location stack/register ops. Range: 32768-36863. */
    public static final int LOCATION = 8;

    /** Vector operations: Vector manipulation. Range: 36864-40959. */
    public static final int VECTOR = 9;

    private Family() {
        // Utility class - prevent instantiation
    }

    /**
     * Returns the base opcode ID for the given family.
     *
     * <p>The base ID is the first opcode ID in the family's range, calculated as
     * {@code family * OpcodeId.FAMILY_MULTIPLIER}.
     *
     * @param family the family ID (0-9)
     * @return the base opcode ID for that family
     */
    public static int baseId(int family) {
        return family * OpcodeId.FAMILY_MULTIPLIER;
    }
}
