package org.evochora.assembler;

/**
 * Speichert den genauen Ursprung einer assemblierten Anweisung für das Laufzeit-Debugging.
 */
public record SourceLocation(
        String fileName,
        int lineNumber,
        String lineContent
) {}