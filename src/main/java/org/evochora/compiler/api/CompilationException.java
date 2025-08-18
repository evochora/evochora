package org.evochora.compiler.api;

/**
 * Eine Exception, die geworfen wird, wenn während des Kompilierungsvorgangs
 * ein oder mehrere Fehler auftreten.
 * <p>
 * Sie ist Teil der öffentlichen API und verbirgt die internen Exception-Typen des Compilers.
 */
public class CompilationException extends Exception {

    public CompilationException(String message) {
        super(message, null);
    }

    public CompilationException(String message, Throwable cause) {
        super(message, cause);
    }
}