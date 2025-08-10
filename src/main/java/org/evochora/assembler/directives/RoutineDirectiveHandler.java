package org.evochora.assembler.directives;

import org.evochora.assembler.AnnotatedLine;
import org.evochora.assembler.AssemblerException;
import org.evochora.assembler.DefinitionExtractor;
import org.evochora.Messages;
import org.evochora.organism.Instruction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RoutineDirectiveHandler implements IBlockDirectiveHandler {

    private String routineName;
    private final List<String> parameters = new ArrayList<>();
    private final List<String> body = new ArrayList<>();
    private AnnotatedLine startLine;
    private final String programName;

    public RoutineDirectiveHandler(String programName) {
        this.programName = programName;
    }

    @Override
    public void startBlock(AnnotatedLine line) {
        this.startLine = line;
        String[] parts = line.content().strip().split("\\s+");
        if (parts.length < 2) {
            throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.directiveNeedsName", ".ROUTINE"), line.content());
        }
        this.routineName = parts[1].toUpperCase();

        // Parameter parsen und validieren
        for (int i = 2; i < parts.length; i++) {
            String param = parts[i];
            if (Instruction.getInstructionIdByName(param.toUpperCase()) != null) {
                throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.routineParameterCollidesWithInstruction", param), line.content());
            }
            this.parameters.add(param);
        }
    }

    @Override
    public void processLine(AnnotatedLine line) {
        body.add(line.content());
    }

    @Override
    public void endBlock(DefinitionExtractor extractor) {
        String prefix = getPrefixFromFileName(startLine.originalFileName());
        String qualifiedName = prefix + "." + routineName;
        DefinitionExtractor.RoutineDefinition def = new DefinitionExtractor.RoutineDefinition(qualifiedName, parameters, body, startLine.originalFileName());
        extractor.getRoutineMap().put(qualifiedName, def);
    }

    private String getPrefixFromFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex).toUpperCase();
        }
        return fileName.toUpperCase();
    }
}