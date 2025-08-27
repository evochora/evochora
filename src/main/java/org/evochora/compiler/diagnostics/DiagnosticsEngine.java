package org.evochora.compiler.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * An engine for collecting and managing diagnostic messages (errors, warnings)
 * that occur during the compilation process.
 * <p>
 * This decouples error reporting from the actual compiler logic (parser, etc.).
 */
public class DiagnosticsEngine {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    /**
     * Reports an error.
     *
     * @param message    The error message.
     * @param fileName   The file in which the error occurred.
     * @param lineNumber The line number of the error.
     */
    public void reportError(String message, String fileName, int lineNumber) {
        diagnostics.add(new Diagnostic(Diagnostic.Type.ERROR, message, fileName, lineNumber));
    }

    /**
     * Reports a warning.
     *
     * @param message    The warning message.
     * @param fileName   The file in which the warning occurred.
     * @param lineNumber The line number of the warning.
     */
    public void reportWarning(String message, String fileName, int lineNumber) {
        diagnostics.add(new Diagnostic(Diagnostic.Type.WARNING, message, fileName, lineNumber));
    }

    /**
     * Checks if errors have been reported.
     *
     * @return {@code true} if at least one error exists, otherwise {@code false}.
     */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.type() == Diagnostic.Type.ERROR);
    }

    /**
     * Returns an unmodifiable list of all collected diagnostics.
     *
     * @return An unmodifiable list of diagnostics.
     */
    public List<Diagnostic> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    /**
     * Returns all collected diagnostics as a single, formatted string.
     *
     * @return A formatted string summary of all diagnostics.
     */
    public String summary() {
        return diagnostics.stream()
                .map(Diagnostic::toString)
                .collect(Collectors.joining("\n"));
    }
}