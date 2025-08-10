package org.evochora.assembler.directives;

import org.evochora.Messages;
import org.evochora.assembler.AnnotatedLine;
import org.evochora.assembler.AssemblerException;
import org.evochora.assembler.DefinitionExtractor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MacroDirectiveHandler implements IBlockDirectiveHandler {

    private String macroName;
    private final List<String> parameters = new ArrayList<>();
    private final List<String> body = new ArrayList<>();
    private AnnotatedLine startLine;
    private final String programName;

    public MacroDirectiveHandler(String programName) {
        this.programName = programName;
    }

    @Override
    public void startBlock(AnnotatedLine line) {
        this.startLine = line;
        String[] parts = line.content().strip().split("\\s+");
        if (parts.length < 2) {
            throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.directiveNeedsName", ".MACRO"), line.content());
        }
        this.macroName = parts[1].toUpperCase();
        this.parameters.addAll(Arrays.asList(parts).subList(2, parts.length));
    }

    @Override
    public void processLine(AnnotatedLine line) {
        body.add(line.content());
    }

    @Override
    public void endBlock(DefinitionExtractor extractor) {
        DefinitionExtractor.MacroDefinition def = new DefinitionExtractor.MacroDefinition(macroName, parameters, body, startLine.originalFileName());
        extractor.getMacroMap().put(macroName, def);
        if (macroName.startsWith("$")) {
            extractor.getMacroMap().put(macroName.substring(1), def);
        }
    }
}