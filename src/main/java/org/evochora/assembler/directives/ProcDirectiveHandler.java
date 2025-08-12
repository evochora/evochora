package org.evochora.assembler.directives;

import org.evochora.Config;
import org.evochora.Messages;
import org.evochora.assembler.AnnotatedLine;
import org.evochora.assembler.AssemblerException;
import org.evochora.assembler.DefinitionExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProcDirectiveHandler implements IBlockDirectiveHandler {

    private String procName;
    // KORREKTUR: Speichert die gesamten AnnotatedLine-Objekte, nicht nur Strings.
    private final List<AnnotatedLine> body = new ArrayList<>();
    private boolean isExported = false;
    private final List<String> requires = new ArrayList<>();
    private final List<String> formalParams = new ArrayList<>();
    private final Map<String, Integer> pregAliases = new HashMap<>();
    private AnnotatedLine startLine;
    private final String programName;

    public ProcDirectiveHandler(String programName) {
        this.programName = programName;
    }

    @Override
    public void startBlock(AnnotatedLine line) {
        this.startLine = line;
        String[] parts = line.content().strip().split("\\s+");
        if (parts.length < 2) {
            throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.directiveNeedsName", ".PROC"), line.content());
        }
        this.procName = parts[1].toUpperCase();

        if (parts.length >= 4 && parts[2].equalsIgnoreCase("WITH")) {
            for (int i = 3; i < parts.length; i++) {
                String formal = parts[i].toUpperCase();
                if (formal.startsWith("%")) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.procFormalMustNotBePercent", formal), line.content());
                }
                formalParams.add(formal);
            }
        }
    }

    @Override
    public void processLine(AnnotatedLine line) {
        String strippedLine = line.content().split("#", 2)[0].strip();
        if (strippedLine.isEmpty()) {
            body.add(line); // Leere Zeilen auch als AnnotatedLine speichern
            return;
        }
        String[] parts = strippedLine.split("\\s+");
        String directive = parts[0].toUpperCase();

        switch (directive) {
            case ".EXPORT" -> isExported = true;
            case ".REQUIRE" -> {
                if (parts.length >= 2) requires.add(parts[1].toUpperCase());
            }
            case ".PREG" -> {
                if (parts.length != 3) throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.pregInvalidSyntax"), line.content());
                String alias = parts[1].toUpperCase();
                if (!alias.startsWith("%")) throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.pregNameMustStartPercent", alias), line.content());
                int index;
                try {
                    index = Integer.parseInt(parts[2]);
                } catch (NumberFormatException nfe) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.pregInvalidIndex", parts[2]), line.content());
                }
                if (index < 0 || index >= Config.NUM_PROC_REGISTERS) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.pregInvalidIndex", parts[2]), line.content());
                }
                pregAliases.put(alias, index);
            }
            default -> body.add(line); // Komplette Zeile mit Metadaten hinzufügen
        }
    }

    @Override
    public void endBlock(DefinitionExtractor extractor) {
        boolean hasRet = body.stream()
                .map(line -> line.content().split("#", 2)[0].strip())
                .anyMatch(s -> s.equalsIgnoreCase("RET"));

        if (!hasRet) {
            // Füge eine künstliche RET-Zeile hinzu, die Metadaten der .ENDP-Zeile erhält (oder der Startzeile als Fallback)
            body.add(new AnnotatedLine("RET", startLine.originalLineNumber(), startLine.originalFileName()));
            System.out.println(Messages.get("definitionExtractor.autoRetAppended",
                    procName, startLine.originalFileName(), String.valueOf(startLine.originalLineNumber())));
        }

        List<AnnotatedLine> procLines = new ArrayList<>();
        procLines.add(new AnnotatedLine(procName + ":", startLine.originalLineNumber(), startLine.originalFileName()));
        // KORREKTUR: Füge die originalen AnnotatedLine-Objekte aus dem Body hinzu.
        procLines.addAll(body);
        extractor.addDeferredProcBody(procLines);

        extractor.getProcMetaMap().put(procName, new DefinitionExtractor.ProcMeta(
                isExported,
                new ArrayList<>(requires),
                startLine.originalFileName(),
                startLine.originalLineNumber(),
                new ArrayList<>(formalParams),
                new HashMap<>(pregAliases)
        ));
    }
}