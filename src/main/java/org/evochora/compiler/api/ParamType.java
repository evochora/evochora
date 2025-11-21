package org.evochora.compiler.api;

/**
 * Enumeration for procedure parameter types.
 * Distinguishes between reference parameters (REF), value parameters (VAL),
 * and legacy WITH syntax parameters.
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
     * Legacy WITH syntax parameter. Used for backward compatibility with old
     * procedure declaration syntax (deprecated).
     */
    WITH;
    
    /**
     * Converts a Protobuf ParamType to the Java ParamType enum.
     *
     * @param protoType The Protobuf ParamType.
     * @return The corresponding Java ParamType.
     * @throws IllegalArgumentException if the Protobuf type is unknown or UNRECOGNIZED.
     */
    public static ParamType fromProtobuf(org.evochora.datapipeline.api.contracts.ParamType protoType) {
        if (protoType == null) {
            throw new IllegalArgumentException("Protobuf ParamType cannot be null");
        }
        return switch (protoType) {
            case PARAM_TYPE_REF -> REF;
            case PARAM_TYPE_VAL -> VAL;
            case PARAM_TYPE_WITH -> WITH;
            case UNRECOGNIZED -> throw new IllegalArgumentException("Unrecognized ParamType: " + protoType);
        };
    }
    
    /**
     * Converts this Java ParamType to the corresponding Protobuf ParamType.
     *
     * @return The Protobuf ParamType enum value.
     */
    public org.evochora.datapipeline.api.contracts.ParamType toProtobuf() {
        return switch (this) {
            case REF -> org.evochora.datapipeline.api.contracts.ParamType.PARAM_TYPE_REF;
            case VAL -> org.evochora.datapipeline.api.contracts.ParamType.PARAM_TYPE_VAL;
            case WITH -> org.evochora.datapipeline.api.contracts.ParamType.PARAM_TYPE_WITH;
        };
    }
}

