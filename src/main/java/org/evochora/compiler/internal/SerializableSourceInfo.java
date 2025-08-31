package org.evochora.compiler.internal;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A serializable version of SourceInfo for use in LinearizedProgramArtifact.
 * This class handles Jackson serialization/deserialization while maintaining
 * the same structure as the original SourceInfo.
 * 
 * @param fileName The file where the code is located.
 * @param lineNumber The line number.
 * @param lineContent The content of the line.
 */
public record SerializableSourceInfo(String fileName, int lineNumber, int columnNumber, String lineContent) {
    
    /**
     * Creates a SerializableSourceInfo from a regular SourceInfo.
     */
    public static SerializableSourceInfo from(org.evochora.compiler.api.SourceInfo sourceInfo) {
        return new SerializableSourceInfo(
            sourceInfo.fileName(),
            sourceInfo.lineNumber(),
            sourceInfo.columnNumber(),
            sourceInfo.lineContent()
        );
    }
    
    /**
     * Converts this SerializableSourceInfo back to a regular SourceInfo.
     */
    public org.evochora.compiler.api.SourceInfo toSourceInfo() {
        return new org.evochora.compiler.api.SourceInfo(fileName, lineNumber, columnNumber, lineContent);
    }
    
    /**
     * Serializes to a string format for use as a map key.
     * Format: "fileName:lineNumber:lineContent"
     */
    @JsonValue
    @Override
    public String toString() {
        return String.format("%s:%d:%d:%s", 
            fileName != null ? fileName : "<unknown>", 
            lineNumber, 
            columnNumber,
            lineContent != null ? lineContent : "");
    }
    
    /**
     * Creates a SerializableSourceInfo from a serialized string.
     * Format: "fileName:lineNumber:lineContent"
     */
    public static SerializableSourceInfo fromString(String serialized) {
        if (serialized == null || serialized.isEmpty()) {
            throw new IllegalArgumentException("Serialized string cannot be null or empty");
        }
        
        String[] parts = serialized.split(":", 4);
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid serialized format: " + serialized);
        }
        
        String fileName = parts[0].equals("<unknown>") ? null : parts[0];
        int lineNumber = Integer.parseInt(parts[1]);
        int columnNumber = Integer.parseInt(parts[2]);
        String lineContent = parts.length > 3 ? parts[3] : "";
        
        return new SerializableSourceInfo(fileName, lineNumber, columnNumber, lineContent);
    }
}
