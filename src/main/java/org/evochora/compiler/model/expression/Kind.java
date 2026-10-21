package org.evochora.compiler.model.expression;

/**
 * The kinds a {@link Value} can have, one per record of the sealed family. A phase that checks
 * what a value may stand for asks for the kind; a phase that computes reads the record.
 */
public enum Kind {
    /** A 64-bit integer. */
    NUMBER,
    /** A molecule type with a number, the value of a typed literal such as {@code DATA:5}. */
    TYPED,
    /** A list of numbers, one per dimension. */
    VECTOR,
    /** A molecule type on its own. */
    TYPE,
    /** A place in the program, as a vector from the program's origin. */
    POSITION
}
