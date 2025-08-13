package org.evochora.compiler.diagnostics;

/**
 * Repräsentiert eine einzelne Diagnose-Nachricht (Fehler, Warnung, Info),
 * die während des Kompilierungsvorgangs auftritt.
 */
public record Diagnostic(
        Diagnostic.Type type,
        String message,
        String fileName,
        int lineNumber
) {
    public enum Type {
        ERROR,
        WARNING,
        INFO
    }

    @Override
    public String toString() {
        return String.format("[%s] %s:%d: %s", type, fileName, lineNumber, message);
    }
}
