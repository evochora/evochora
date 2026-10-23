package org.evochora.compiler.expression;

/**
 * The precedence scale of the operations, declared from the tightest level to the widest. An
 * operator of a tighter level binds its operands before one of a wider level, so {@code A + B * C}
 * is {@code A + (B * C)}; operators of one level read left to right.
 * <p>
 * {@link #UNORDERED} stands outside the order: its operators stand without parentheses only next
 * to themselves. Next to a multiplicative, additive or chain operator, or to a different operator
 * of its own level, the parentheses are required. The index, the typing operator and the prefix
 * operators bind tighter than every operator of the scale and stand next to it freely.
 * <p>
 * A new level is a change of the core and of the language, by design: the parser reads each one.
 */
public enum Level {
    /** The index access {@code a[i]}, and the calls of functions, both ending visibly. */
    INDEX,
    /** The typing operator {@code T:n}. */
    TYPING,
    /** The prefix operators {@code -a} and {@code NOT a}. */
    PREFIX,
    /** {@code *}, {@code /} and {@code MOD}. */
    MULTIPLICATIVE,
    /** {@code +} and {@code -}. */
    ADDITIVE,
    /** The chain {@code a|b|c}. */
    CHAIN,
    /** {@code AND}, {@code OR}, {@code XOR}, {@code SHL}, {@code SHR}: unordered against the others. */
    UNORDERED;

    /**
     * Tells whether the level has a place in the order of the others. An operator of an unordered
     * level stands without parentheses only next to itself.
     *
     * @return {@code false} for {@link #UNORDERED}, {@code true} for every other level.
     */
    public boolean isOrdered() {
        return this != UNORDERED;
    }

    /**
     * Returns the level an operand of an operator of this level is read at: the right operand of
     * an infix operator, every part of a chain, the operand of a prefix operator. An infix operator
     * reads it one level tighter, which makes its level read left to right; the right operand of
     * the typing operator is read at the prefix level, so that {@code DATA:-5} needs no
     * parentheses; a part of a chain at the additive level, so that {@code (WIDTH-1|HEIGHT-1)} is a
     * vector of two computations; the operand of a prefix operator at the prefix level, so that
     * prefixes stack; the index inside its brackets, and the operands of an unordered operator at
     * the widest ordered level, where the parser holds them to the rule of {@link #isOrdered()}.
     *
     * @return The level the operand is read at.
     */
    public Level operandLevel() {
        return switch (this) {
            case INDEX -> UNORDERED;
            case TYPING, PREFIX, MULTIPLICATIVE -> PREFIX;
            case ADDITIVE -> MULTIPLICATIVE;
            case CHAIN -> ADDITIVE;
            case UNORDERED -> CHAIN;
        };
    }
}
