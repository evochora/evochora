package org.evochora.runtime.isa.instructions;

/**
 * The tests a conditional instruction can make, each in its positive form.
 * <p>
 * Every conditional opcode is registered with one of these and with whether it is negated: the
 * negated opcode holds exactly when the positive one does not. The number of values a test takes
 * is fixed by the test, whatever the operands are read from, and is what a conditional expects to
 * find among its operands.
 */
public enum Condition {

    /** The two values are the same value (IF, IN). */
    EQUAL(2),
    /** The first value is below the second (LT, GET). */
    LESS_THAN(2),
    /** The first value is above the second (GT, LET). */
    GREATER_THAN(2),
    /** The two values have the same molecule type (IFT, INT). */
    TYPE_EQUAL(2),
    /** The cell the vector names is accessible to the organism (IFM, INM). */
    MINE(1),
    /** The organism may move onto the cell the vector names (IFP, INP). */
    PASSABLE(1),
    /** The cell the vector names is owned by another organism (IFF, INF). */
    FOREIGN(1),
    /** The cell the vector names is owned by nobody (IFV, INV). */
    VACANT(1),
    /** The previous instruction failed (IFER, INER). */
    PREVIOUS_FAILED(0),
    /** The location register holds a position (IFSL, INSL). */
    HOLDS_POSITION(1),
    /** The first value is above a draw below the second (PGT, PLE). */
    GREATER_THAN_DRAW(2),
    /** The first value is below a draw below the second (PLT, PGE). */
    LESS_THAN_DRAW(2),
    /** The data pointer lies within the own body on the line the vector names (IFB, INB). */
    WITHIN_BODY(1),
    /** The cell the vector names exists, which beyond the edge of a bounded world it does not (IFX, INX). */
    EXISTS(1);

    private final int operandCount;

    Condition(int operandCount) {
        this.operandCount = operandCount;
    }

    /**
     * Returns how many values the test takes.
     *
     * @return the number of operands the test is evaluated on
     */
    public int operandCount() {
        return operandCount;
    }
}
