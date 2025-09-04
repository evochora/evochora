package org.evochora.compiler.api;

/**
 * An exception that is thrown when one or more errors occur during the compilation process.
 * <p>
 * It is part of the public API and hides the internal exception types of the compiler.
 */
public class CompilationException extends Exception {

    /**
     * Constructs a new compilation exception with the specified detail message.
     * @param message The detail message.
     */
    public CompilationException(String message) {
        super(message, null);
    }

    /**
     * Constructs a new compilation exception with the specified detail message and cause.
     * @param message The detail message.
     * @param cause The cause.
     */
    public CompilationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new compilation exception with the specified detail message and source information.
     * @param message The detail message.
     * @param sourceInfo The source information.
     */
    public CompilationException(String message, SourceInfo sourceInfo) {
        super(String.format("%s at %s", message, sourceInfo), null);
    }
}