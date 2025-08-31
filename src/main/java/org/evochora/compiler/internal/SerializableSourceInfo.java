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
public record SerializableSourceInfo(String fileName, int lineNumber, String lineContent) {
    
    /**
     * Creates a SerializableSourceInfo from a regular SourceInfo.
     */
    public static SerializableSourceInfo from(org.evochora.compiler.api.SourceInfo sourceInfo) {
        return new SerializableSourceInfo(
            sourceInfo.fileName(),
            sourceInfo.lineNumber(),
            sourceInfo.lineContent()
        );
    }
    
    /**
     * Converts this SerializableSourceInfo back to a regular SourceInfo.
     */
    public org.evochora.compiler.api.SourceInfo toSourceInfo() {
        return new org.evochora.compiler.api.SourceInfo(fileName, lineNumber, lineContent);
    }
    
    /**
     * Serializes to a string format for use as a map key.
     * Format: "fileName:lineNumber:lineContent"
     */
    @JsonValue
    @Override
    public String toString() {
        return String.format("%s:%d:%s", 
            fileName != null ? fileName : "<unknown>", 
            lineNumber, 
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
        
        String[] parts = serialized.split(":", 3);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid serialized format: " + serialized);
        }
        
        String fileName = parts[0].equals("<unknown>") ? null : parts[0];
        int lineNumber = Integer.parseInt(parts[1]);
        String lineContent = parts.length > 2 ? parts[2] : "";
        
        return new SerializableSourceInfo(fileName, lineNumber, lineContent);
    }
}
