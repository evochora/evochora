package org.evochora.compiler;

import org.evochora.compiler.diagnostics.Diagnostic;

/**
 * Shows a diagnostic the way the compiler reports a source error to a programmer: the file, the
 * line and the message in one string.
 */
public final class TestDiagnostics {

    private TestDiagnostics() {
    }

    /**
     * The diagnostic as {@code <file>:<line>: <message>}.
     *
     * @param diagnostic the diagnostic of a source error
     * @return the file, the line and the message in one string
     */
    public static String located(Diagnostic diagnostic) {
        return diagnostic.fileName() + ":" + diagnostic.lineNumber() + ": " + diagnostic.message();
    }
}
