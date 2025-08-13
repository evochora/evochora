package org.evochora.compiler.api;

/**
 * Eine Exception, die geworfen wird, wenn während des Kompilierungsvorgangs
 * ein oder mehrere Fehler auftreten.
 * <p>
 * Sie ist Teil der öffentlichen API und verbirgt die internen Exception-Typen des Compilers.
 */
public class CompilationException extends Exception {

    /**
     * TODO: [Phase 4] Temporäre Nutzlast, um Testdaten aus dem Compiler zu extrahieren.
     * Wird entfernt, sobald der Compiler vollständig ist.
     */
    private final Object payload;

    public CompilationException(String message) {
        this(message, null, null);
    }

    public CompilationException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public CompilationException(String message, Object payload) {
        this(message, null, payload);
    }

    public CompilationException(String message, Throwable cause, Object payload) {
        super(message, cause);
        this.payload = payload;
    }

    public Object getPayload() {
        return payload;
    }
}
