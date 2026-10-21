package org.evochora.compiler.expression;

/**
 * Where an operation stands among its operands, which decides how the expression parser reads it.
 * One name may be registered under several notations; {@code -} is both a prefix and an infix.
 */
public enum Notation {
    /** A call, {@code NAME(a, b)}: a word directly followed by a parenthesis. */
    FUNCTION,
    /** An operator before its one operand, {@code -a} or {@code NOT a}. */
    PREFIX,
    /** An operator between two operands, {@code a + b}; the index access {@code a[b]} too. */
    INFIX,
    /** An operator between any number of parts, {@code a|b|c}, read as one operation on all of them. */
    CHAIN
}
