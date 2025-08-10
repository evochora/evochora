package org.evochora.assembler.directives;

import org.evochora.assembler.AnnotatedLine;
import org.evochora.assembler.AssemblerException;
import org.evochora.assembler.PassManagerContext;
import org.evochora.Config;
import org.evochora.Messages;
import org.evochora.world.Symbol;
import java.util.Arrays;

public class PlaceDirectiveHandler implements IDirectiveHandler {
    @Override
    public void handle(AnnotatedLine line, PassManagerContext context) {
        String[] parts = line.content().strip().split("\\s+");
        String[] typeAndValue = parts[1].split(":");
        int type = getTypeFromString(typeAndValue[0], line, context.programName());
        int value = Integer.parseInt(typeAndValue[1]);
        int[] relativePos = Arrays.stream(parts[2].split("\\|")).map(String::strip).mapToInt(Integer::parseInt).toArray();
        context.initialWorldObjects().put(relativePos, new Symbol(type, value));
    }

    private int getTypeFromString(String typeName, AnnotatedLine line, String programName) {
        return switch (typeName.toUpperCase()) {
            case "CODE" -> Config.TYPE_CODE;
            case "DATA" -> Config.TYPE_DATA;
            case "ENERGY" -> Config.TYPE_ENERGY;
            case "STRUCTURE" -> Config.TYPE_STRUCTURE;
            default -> throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("passManager.unknownType", typeName), line.content());
        };
    }
}