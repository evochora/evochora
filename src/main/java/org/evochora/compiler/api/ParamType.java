package org.evochora.compiler.api;

/**
 * Enumeration for procedure parameter types.
 * Distinguishes between reference (REF/LREF) and value (VAL/LVAL) parameters
 * for both scalar and location registers.
 */
public enum ParamType {
    /**
     * Call-by-reference parameter. The parameter is bound to a register,
     * and modifications to the parameter within the procedure affect the original register.
     */
    REF,

    /**
     * Call-by-value parameter. The parameter value is copied, and the parameter
     * can be bound to either a register or a literal value.
     */
    VAL,

    /**
     * Location call-by-reference parameter. Bound to a location register (FLR).
     * Modifications to the FLR within the procedure are written back to the source register on RET.
     */
    LREF,

    /**
     * Location call-by-value parameter. The location value is copied into FLR.
     */
    LVAL;
}
