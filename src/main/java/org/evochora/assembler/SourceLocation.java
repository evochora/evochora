package org.evochora.assembler;

/**
 * Speichert den genauen Ursprung einer assemblierten Anweisung für das Laufzeit-Debugging.
 * Ein Objekt dieser Klasse wird in der "Source Map" der ProgramMetadata abgelegt.
 */
public record SourceLocation(
        String fileName,
        int lineNumber,
        String lineContent
) {}