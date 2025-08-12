package org.evochora.compiler.api;

import java.util.List;

/**
 * Definiert die öffentliche, saubere Schnittstelle für den Evochora-Compiler.
 * <p>
 * Jede Implementierung dieser Schnittstelle nimmt Quellcode in Form von Strings
 * entgegen und liefert bei Erfolg ein unveränderliches {@link ProgramArtifact}.
 * Bei Fehlern wird eine {@link CompilationException} geworfen.
 * <p>
 * Diese Schnittstelle entkoppelt den Rest der Anwendung vollständig von den
 * internen Implementierungsdetails des Compilers.
 */
public interface Compiler {

    /**
     * Kompiliert den gegebenen Quellcode.
     *
     * @param sourceLines Eine Liste von Strings, die die Zeilen des Haupt-Quellcodes darstellen.
     * @param programName Ein Name für das Programm, der für Debugging-Zwecke verwendet wird.
     * @return Ein {@link ProgramArtifact}, das das kompilierte Programm und alle zugehörigen Metadaten enthält.
     * @throws CompilationException wenn während des Kompilierungsvorgangs Fehler auftreten.
     */
    ProgramArtifact compile(List<String> sourceLines, String programName) throws CompilationException;

}
