package org.evochora.runtime.isa;

/**
 * Definiert die semantischen Typen von Instruktions-Argumenten für den Compiler.
 * Dies wird vom SemanticAnalyzer verwendet, um die Korrektheit von Instruktionen zu prüfen.
 */
public enum InstructionArgumentType {
    /** Ein Register (z.B. %DR0, %PR1). */
    REGISTER,
    /** Ein numerisches oder typisiertes Literal (z.B. 42, DATA:10). */
    LITERAL,
    /** Ein Vektor-Literal (z.B. 1|0). */
    VECTOR,
    /** Ein Label, das zu einer Adresse aufgelöst wird. */
    LABEL
}