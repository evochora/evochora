package org.evochora.compiler.internal.legacy;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 0 des Assemblers: Verarbeitet .FILE-Direktiven.
 * Diese Klasse liest rekursiv alle inkludierten Dateien und fügt sie zu einem
 * einzigen, flachen Stream von AnnotatedLine-Objekten zusammen, bevor der
 * eigentliche Assembler-Prozess beginnt.
 */
public class FilePreprocessor {

    private final String programName;

    public FilePreprocessor(String programName) {
        this.programName = programName;
    }

    /**
     * Startet den Vorverarbeitungsprozess.
     * @param initialLines Die ursprünglichen Codezeilen aus der Hauptdatei.
     * @return Eine flache Liste von AnnotatedLines, die alle inkludierten Dateien enthält.
     */
    public List<AnnotatedLine> process(List<AnnotatedLine> initialLines) {
        return inlineFileRecursive(initialLines, new HashSet<>());
    }

    private List<AnnotatedLine> inlineFileRecursive(List<AnnotatedLine> lines, Set<String> visitedFiles) {
        List<AnnotatedLine> result = new ArrayList<>();
        for (AnnotatedLine line : lines) {
            String stripped = line.content().split("#", 2)[0].strip();
            String[] parts = stripped.split("\\s+");

            if (parts.length > 0 && parts[0].equalsIgnoreCase(".FILE")) {
                // NEU: Syntax-Prüfung aus dem CodeExpander übernommen
                if (parts.length != 2) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), "Invalid .FILE syntax. Expected: .FILE \"path/to/file.s\"", line.content());
                }
                String fileToInclude = parts[1].replace("\"", "");

                // Verhindert zirkuläre Inklusionen (A -> B -> A)
                if (visitedFiles.contains(fileToInclude)) {
                    continue;
                }
                visitedFiles.add(fileToInclude);

                try {
                    String prototypesPath = "org/evochora/organism/prototypes/";
                    URL resourceUrl = Thread.currentThread().getContextClassLoader().getResource(prototypesPath + fileToInclude);
                    if (resourceUrl == null) {
                        throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), "Library file not found: " + fileToInclude, line.content());
                    }
                    Path path = Paths.get(resourceUrl.toURI());
                    String fileContent = Files.readString(path);

                    // Erstelle AnnotatedLines für die neue Datei
                    List<AnnotatedLine> includedLines = new ArrayList<>();
                    int lineNum = 1;
                    for (String contentLine : fileContent.split("\\r?\\n")) {
                        includedLines.add(new AnnotatedLine(contentLine, lineNum++, fileToInclude));
                    }

                    // Rekursiver Aufruf, um verschachtelte .FILE-Direktiven in der neuen Datei zu verarbeiten
                    result.addAll(inlineFileRecursive(includedLines, visitedFiles));

                } catch (Exception e) {
                    // Kapselt jeden Fehler in einer AssemblerException für konsistente Fehlermeldungen
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), "Error loading library file: " + fileToInclude, line.content());
                }
            } else {
                // Keine .FILE-Direktive, einfach die Zeile übernehmen
                result.add(line);
            }
        }
        return result;
    }
}