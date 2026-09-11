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
     * Register id in the whole register space, the same id every bank is addressed by: 0 for
     * {@code %DR0}, 256 for {@code %LR0}, 512 for {@code %PDR0}. The index within a bank is the id
     * minus that bank's base, which {@link #registerType} names.
     * Only present for REGISTER type.
     */
    public final Integer registerId;

    /**
     * Register value resolved from organism state.
     * Only present for REGISTER type.
     */
    public final RegisterValueView registerValue;

    /**
     * Name of the bank the register belongs to, one of {@code RegisterBank}'s names, or "UNKNOWN"
     * when the cell holds a value that is no register id at all, which is what an overwritten
     * operand looks like. It names the bank {@link #registerId} falls into, so a reader need not
     * derive it from the id.
     * Only present for REGISTER type.
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
     * @param registerId    Register id in the whole register space, not the index within its bank
     * @param registerValue Register value resolved from organism state
     * @param registerType  Name of the bank the id falls into, or "UNKNOWN" for no register id
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
     * Creates an argument view for a LABEL type with a scalar hash value.
     * Since fuzzy jumps, labels are represented as 20-bit hash values stored in DATA molecules.
     *
     * @param rawValue     Raw int32 value from the molecule
     * @param moleculeType Human-readable molecule type name (typically "DATA")
     * @param hashValue    The decoded label hash value
     * @return InstructionArgumentView for LABEL type
     */
    public static InstructionArgumentView label(int rawValue, String moleculeType, int hashValue) {
        return new InstructionArgumentView("LABEL", null, null, null, rawValue, moleculeType, hashValue, null);
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

