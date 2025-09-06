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
        
        // Find the last two colons to handle file paths that contain colons (e.g., Windows paths like C:/...)
        int lastColonIndex = serialized.lastIndexOf(':');
        if (lastColonIndex == -1) {
            throw new IllegalArgumentException("Invalid serialized format: " + serialized);
        }
        
        int secondLastColonIndex = serialized.lastIndexOf(':', lastColonIndex - 1);
        if (secondLastColonIndex == -1) {
            throw new IllegalArgumentException("Invalid serialized format: " + serialized);
        }
        
        String fileName = serialized.substring(0, secondLastColonIndex);
        if (fileName.equals("<unknown>")) {
            fileName = null;
        }
        
        String lineNumberStr = serialized.substring(secondLastColonIndex + 1, lastColonIndex);
        String columnNumberStr = serialized.substring(lastColonIndex + 1);
        
        int lineNumber = Integer.parseInt(lineNumberStr);
        int columnNumber = Integer.parseInt(columnNumberStr);
        
        return new SerializableSourceInfo(fileName, lineNumber, columnNumber);
    }
}
