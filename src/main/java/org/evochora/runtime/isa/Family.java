package org.evochora.runtime.isa;

/**
 * The instruction families an opcode can belong to, one entry per instruction class.
 *
 * <p>A family is the instruction class that registers and executes an instruction. Its value
 * forms the lowest five bits of every opcode id the class allocates, which is how the ids are
 * allocated without a central counter. Which family an instruction belongs to is recorded by
 * {@link Instruction} when the instruction registers and is answered by
 * {@link Instruction#getFamilyById(int)}; registration rejects a value that another class already
 * holds, and a class that registers under a second value.
 *
 * <p>This class is thread-safe as it contains only static constants.
 */
public final class Family {

    /** NOP and reserved instructions. */
    public static final int SPECIAL = 0;

    /** Arithmetic operations: ADD, SUB, MUL, DIV, etc. */
    public static final int ARITHMETIC = 1;

    /** Bitwise operations: AND, OR, XOR, NOT, shifts. */
    public static final int BITWISE = 2;

    /** Data operations: SET, PUSH, POP, XCHG. */
    public static final int DATA = 3;

    /** Conditional operations: IF, comparisons. */
    public static final int CONDITIONAL = 4;

    /** Control flow operations: JMP, CALL, RET. */
    public static final int CONTROL = 5;

    /** Environment operations: PEEK, POKE. */
    public static final int ENVIRONMENT = 6;

    /** State operations: SCAN, SEEK, FORK, etc. */
    public static final int STATE = 7;

    /** Location operations: Location stack/register ops. */
    public static final int LOCATION = 8;

    /** Vector operations: Vector manipulation. */
    public static final int VECTOR = 9;

    /** Stack operations: DUP, SWAP, DROP, ROT. */
    public static final int STACK = 10;

    private Family() {
        // Utility class - prevent instantiation
    }
}
