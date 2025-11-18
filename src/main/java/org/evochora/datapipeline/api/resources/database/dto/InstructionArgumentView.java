package org.evochora.datapipeline.api.resources.database.dto;

/**
 * View model for an instruction argument used by organism debugging APIs.
 * <p>
 * Represents a single argument to an instruction, with type-specific fields.
 */
public final class InstructionArgumentView {

    /**
     * Argument type: "REGISTER", "IMMEDIATE", "VECTOR", "LABEL", or "STACK".
     */
    public final String type;

    // For REGISTER type
    /**
     * Register ID (e.g., 0 for %DR0).
     * Only present for REGISTER type.
     */
    public final Integer registerId;

    /**
     * Register value resolved from organism state.
     * Only present for REGISTER type.
     */
    public final RegisterValueView registerValue;

    /**
     * Register type name: "DR", "PR", "FPR", or "LR".
     * Only present for REGISTER type.
     * This is needed because DR and LR share the same index range (0-3),
     * so we need to explicitly indicate which register type it is.
     */
    public final String registerType;

    // For IMMEDIATE type
    /**
     * Raw int32 value from instruction_raw_arguments.
     * Only present for IMMEDIATE type.
     */
    public final Integer rawValue;

    /**
     * Human-readable molecule type name (e.g., "DATA", "CODE").
     * Only present for IMMEDIATE type.
     */
    public final String moleculeType;

    /**
     * Decoded signed value.
     * Only present for IMMEDIATE type.
     */
    public final Integer value;

    // For VECTOR/LABEL type
    /**
     * Vector components as int array.
     * Only present for VECTOR or LABEL type.
     */
    public final int[] components;

    private InstructionArgumentView(String type,
                                    Integer registerId,
                                    RegisterValueView registerValue,
                                    String registerType,
                                    Integer rawValue,
                                    String moleculeType,
                                    Integer value,
                                    int[] components) {
        this.type = type;
        this.registerId = registerId;
        this.registerValue = registerValue;
        this.registerType = registerType;
        this.rawValue = rawValue;
        this.moleculeType = moleculeType;
        this.value = value;
        this.components = components;
    }

    /**
     * Creates an argument view for a REGISTER type.
     *
     * @param registerId    Register ID (e.g., 0 for %DR0)
     * @param registerValue Register value resolved from organism state
     * @param registerType  Register type: "DR", "PR", "FPR", or "LR"
     * @return InstructionArgumentView for REGISTER type
     */
    public static InstructionArgumentView register(int registerId, RegisterValueView registerValue, String registerType) {
        return new InstructionArgumentView("REGISTER", registerId, registerValue, registerType, null, null, null, null);
    }

    /**
     * Creates an argument view for an IMMEDIATE type.
     *
     * @param rawValue     Raw int32 value from instruction_raw_arguments
     * @param moleculeType Human-readable molecule type name
     * @param value        Decoded signed value
     * @return InstructionArgumentView for IMMEDIATE type
     */
    public static InstructionArgumentView immediate(int rawValue, String moleculeType, int value) {
        return new InstructionArgumentView("IMMEDIATE", null, null, null, rawValue, moleculeType, value, null);
    }

    /**
     * Creates an argument view for a VECTOR type.
     *
     * @param components Vector components as int array
     * @return InstructionArgumentView for VECTOR type
     */
    public static InstructionArgumentView vector(int[] components) {
        return new InstructionArgumentView("VECTOR", null, null, null, null, null, null, components);
    }

    /**
     * Creates an argument view for a LABEL type.
     *
     * @param components Label components as int array
     * @return InstructionArgumentView for LABEL type
     */
    public static InstructionArgumentView label(int[] components) {
        return new InstructionArgumentView("LABEL", null, null, null, null, null, null, components);
    }

    /**
     * Creates an argument view for a STACK type.
     * <p>
     * STACK arguments have no value stored (taken from stack at runtime).
     *
     * @return InstructionArgumentView for STACK type
     */
    public static InstructionArgumentView stack() {
        return new InstructionArgumentView("STACK", null, null, null, null, null, null, null);
    }
}

