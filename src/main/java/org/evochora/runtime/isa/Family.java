package org.evochora.runtime.isa;

/**
 * The instruction families an opcode can belong to.
 *
 * <p>A family groups semantically related instructions. Which family an instruction belongs to is
 * recorded by {@link Instruction} when the instruction registers and is answered by
 * {@link Instruction#getFamilyById(int)}; the family value also forms the lowest five bits of the
 * opcode id, which is how the ids are allocated without a central counter.
 *
 * <p>The values are part of the opcode allocation and never change: a family renumbered here would
 * give every one of its instructions a different opcode id, and the ids are meant to stay stable
 * so that a stable program format can be built on them.
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

    /** Data operations: SET, PUSH, POP, stack ops. */
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

    private Family() {
        // Utility class - prevent instantiation
    }
}
