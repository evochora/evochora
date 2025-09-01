package org.evochora.runtime.isa;

/**
 * Utility class for instruction argument type operations.
 * Provides centralized mapping from InstructionArgumentType enum values to their string representations
 * for JavaScript compatibility in the web debugger and other display purposes.
 */
public final class InstructionArgumentTypeUtils {
    
    private InstructionArgumentTypeUtils() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Converts an InstructionArgumentType enum value to its string representation.
     * This mapping is used to ensure JavaScript compatibility in the web debugger
     * while maintaining type safety in Java code.
     * 
     * @param argType The instruction argument type enum value
     * @return String representation of the argument type, or "UNKNOWN" if not recognized
     */
    public static String toDisplayString(InstructionArgumentType argType) {
        if (argType == null) {
            return "UNKNOWN";
        }
        
        return switch (argType) {
            case REGISTER -> "REGISTER";
            case LITERAL -> "LITERAL";
            case VECTOR -> "VECTOR";
            case LABEL -> "LABEL";
        };
    }
    
    /**
     * Converts a string representation back to an InstructionArgumentType enum value.
     * This is useful for parsing data from JavaScript or other string-based sources.
     * 
     * @param displayString The string representation of the argument type
     * @return The corresponding InstructionArgumentType enum value, or null if not recognized
     */
    public static InstructionArgumentType fromDisplayString(String displayString) {
        if (displayString == null) {
            return null;
        }
        
        return switch (displayString.toUpperCase()) {
            case "REGISTER" -> InstructionArgumentType.REGISTER;
            case "LITERAL" -> InstructionArgumentType.LITERAL;
            case "VECTOR" -> InstructionArgumentType.VECTOR;
            case "LABEL" -> InstructionArgumentType.LABEL;
            default -> null;
        };
    }
    
    /**
     * Gets the default/fallback string representation for unknown or invalid argument types.
     * 
     * @return The default string representation
     */
    public static String getDefaultDisplayString() {
        return "UNKNOWN";
    }
}
