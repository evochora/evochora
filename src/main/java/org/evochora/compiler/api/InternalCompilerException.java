package org.evochora.compiler.api;

/**
 * A defect in the compiler, as opposed to a mistake in the program: a phase or a handler
 * failed with an exception it did not report as a diagnostic. The message names the
 * exception, the cause carries it, and no source position is given because the program is
 * not at fault. A caller that treats a program's mistakes and the compiler's defects the same
 * catches {@link CompilationException}; one that wants to tell them apart catches this first.
 */
public class InternalCompilerException extends CompilationException {

    /**
     * Wraps the exception the compiler failed with.
     *
     * @param cause The exception the compiler failed with.
     */
    public InternalCompilerException(Throwable cause) {
        super("Internal compiler error: " + cause, cause);
    }
}
