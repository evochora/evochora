package org.evochora.compiler.core.phases;

/**
 * Definiert die verschiedenen Phasen des Kompilierungsprozesses.
 * Handler können sich für eine dieser Phasen registrieren.
 */
public enum CompilerPhase {
    /**
     * Phase 0: Verarbeitet Direktiven, die die Struktur der Quelldateien
     * verändern, wie z.B. .FILE.
     */
    PREPROCESSING,

    /**
     * Phase 1: Verarbeitet die meisten Direktiven und baut den AST auf.
     */
    PARSING
}
