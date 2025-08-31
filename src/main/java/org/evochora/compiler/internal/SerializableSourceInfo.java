package org.evochora.compiler.internal;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A serializable version of SourceInfo for use in LinearizedProgramArtifact.
 * This class handles Jackson serialization/deserialization while maintaining
 * the same structure as the original SourceInfo.
 * 
 * @param fileName The file where the code is located.
 * @param lineNumber The line number.
 * @param columnNumber The column number.
 */
public record SerializableSourceInfo(String fileName, int lineNumber, int columnNumber) {
    
    /**
     * Creates a SerializableSourceInfo from a regular SourceInfo.
     */
    public static SerializableSourceInfo from(org.evochora.compiler.api.SourceInfo sourceInfo) {
        return new SerializableSourceInfo(
            sourceInfo.fileName(),
            sourceInfo.lineNumber(),
            sourceInfo.columnNumber()
        );
    }
    
    /**
     * Converts this SerializableSourceInfo back to a regular SourceInfo.
     */
    public org.evochora.compiler.api.SourceInfo toSourceInfo() {
        return new org.evochora.compiler.api.SourceInfo(fileName, lineNumber, columnNumber);
    }
    
    /**
     * Serializes to a string format for use as a map key.
     * Format: "fileName:lineNumber:columnNumber"
     */
    @JsonValue
    @Override
    public String toString() {
        return String.format("%s:%d:%d", 
            fileName != null ? fileName : "<unknown>", 
            lineNumber, 
            columnNumber);
    }
    
    /**
     * Creates a SerializableSourceInfo from a serialized string.
     * Format: "fileName:lineNumber:columnNumber"
     */
    public static SerializableSourceInfo fromString(String serialized) {
        if (serialized == null || serialized.isEmpty()) {
            throw new IllegalArgumentException("Serialized string cannot be null or empty");
        }
        
        String[] parts = serialized.split(":", 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid serialized format: " + serialized);
        }
        
        String fileName = parts[0].equals("<unknown>") ? null : parts[0];
        int lineNumber = Integer.parseInt(parts[1]);
        int columnNumber = Integer.parseInt(parts[2]);
        
        return new SerializableSourceInfo(fileName, lineNumber, columnNumber);
    }
}
