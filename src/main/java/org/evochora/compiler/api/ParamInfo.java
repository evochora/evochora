package org.evochora.compiler.api;

/**
 * Represents a single procedure parameter with its name and type.
 * This is used in the ProgramArtifact to store parameter information
 * for each procedure, including whether parameters are REF, VAL, or legacy WITH syntax.
 *
 * @param name The parameter name (e.g., "PROC1REG1").
 * @param type The parameter type (REF, VAL, or WITH).
 */
public record ParamInfo(String name, ParamType type) {
    /**
     * Creates a ParamInfo from a Protobuf ParamInfo message.
     *
     * @param protoParamInfo The Protobuf ParamInfo message.
     * @return A new ParamInfo record.
     * @throws IllegalArgumentException if protoParamInfo is null or has invalid type.
     */
    public static ParamInfo fromProtobuf(org.evochora.datapipeline.api.contracts.ParamInfo protoParamInfo) {
        if (protoParamInfo == null) {
            throw new IllegalArgumentException("Protobuf ParamInfo cannot be null");
        }
        return new ParamInfo(
            protoParamInfo.getName(),
            ParamType.fromProtobuf(protoParamInfo.getType())
        );
    }
    
    /**
     * Converts this ParamInfo to a Protobuf ParamInfo message.
     *
     * @return A new Protobuf ParamInfo.Builder instance.
     */
    public org.evochora.datapipeline.api.contracts.ParamInfo.Builder toProtobufBuilder() {
        return org.evochora.datapipeline.api.contracts.ParamInfo.newBuilder()
            .setName(name)
            .setType(type.toProtobuf());
    }
}

