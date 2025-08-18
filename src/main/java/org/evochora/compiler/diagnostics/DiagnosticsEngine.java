package org.evochora.compiler.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Eine Engine zum Sammeln und Verwalten von Diagnose-Nachrichten (Fehler, Warnungen),
 * die während des Kompilierungsvorgangs auftreten.
 * <p>
 * Dies entkoppelt die Fehlerberichterstattung von der eigentlichen Compiler-Logik (Parser, etc.).
 */
public class DiagnosticsEngine {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    /**
     * Meldet einen Fehler.
     *
     * @param message    Die Fehlermeldung.
     * @param fileName   Die Datei, in der der Fehler aufgetreten ist.
     * @param lineNumber Die Zeilennummer des Fehlers.
     */
    public void reportError(String message, String fileName, int lineNumber) {
        diagnostics.add(new Diagnostic(Diagnostic.Type.ERROR, message, fileName, lineNumber));
    }

    /**
     * Meldet eine Warnung.
     *
     * @param message    Die Warnmeldung.
     * @param fileName   Die Datei, in der die Warnung aufgetreten ist.
     * @param lineNumber Die Zeilennummer der Warnung.
     */
    public void reportWarning(String message, String fileName, int lineNumber) {
        diagnostics.add(new Diagnostic(Diagnostic.Type.WARNING, message, fileName, lineNumber));
    }

    /**
     * Prüft, ob Fehler gemeldet wurden.
     *
     * @return {@code true}, wenn mindestens ein Fehler existiert, sonst {@code false}.
     */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.type() == Diagnostic.Type.ERROR);
    }

    /**
     * Gibt eine unveränderliche Liste aller gesammelten Diagnosen zurück.
     */
    public List<Diagnostic> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    /**
     * Gibt alle gesammelten Diagnosen als einen einzigen, formatierten String zurück.
     */
    public String summary() {
        return diagnostics.stream()
                .map(Diagnostic::toString)
                .collect(Collectors.joining("\n"));
    }
}