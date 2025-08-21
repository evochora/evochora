package org.evochora.compiler.api;

import java.util.List;

/**
 * Definiert die öffentliche, saubere Schnittstelle für den Evochora-Compiler.
 */
public interface ICompiler {

    /**
     * Kompiliert den gegebenen Quellcode.
     *
     * @param sourceLines Eine Liste von Strings, die die Zeilen des Haupt-Quellcodes darstellen.
     * @param programName Ein Name für das Programm, der für Debugging-Zwecke verwendet wird.
     * @return Ein {@link ProgramArtifact}, das das kompilierte Programm und alle zugehörigen Metadaten enthält.
     * @throws CompilationException wenn während des Kompilierungsvorgangs Fehler auftreten.
     */
    ProgramArtifact compile(List<String> sourceLines, String programName) throws CompilationException;

    /**
     * Compiles with explicit world dimensions for vector/label argument sizing.
     */
    ProgramArtifact compile(List<String> sourceLines, String programName, int worldDimensions) throws CompilationException;

    /**
     * Setzt das Level für die Ausführlichkeit der Log-Ausgaben.
     * @param level Das Verbosity-Level (z.B. 0=leise, 1=normal, 2=verbose, 3=trace).
     */
    void setVerbosity(int level);
}
