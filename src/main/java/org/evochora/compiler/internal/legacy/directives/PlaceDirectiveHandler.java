package org.evochora.compiler.internal.legacy.directives;

import org.evochora.compiler.internal.legacy.AnnotatedLine;
import org.evochora.compiler.internal.legacy.AssemblerException;
import org.evochora.compiler.internal.legacy.PassManagerContext;
import org.evochora.runtime.Config;
import org.evochora.compiler.internal.i18n.Messages;
import org.evochora.runtime.model.Molecule;

public class PlaceDirectiveHandler implements IDirectiveHandler {
    @Override
    public void handle(AnnotatedLine line, PassManagerContext context) {
        String stripped = line.content().split("#", 2)[0].strip();
        String[] parts = stripped.split("\\s+");
        if (parts.length != 3) {
            throw new AssemblerException(context.programName(), line.originalFileName(), line.originalLineNumber(),
                    "Invalid .PLACE syntax. Expected: .PLACE <TYPE:VAL> <v0|v1|...>", line.content());
        }
        String[] typeAndValue = parts[1].split(":");
        if (typeAndValue.length != 2) {
            throw new AssemblerException(context.programName(), line.originalFileName(), line.originalLineNumber(),
                    "Invalid .PLACE payload. Expected TYPE:VALUE", line.content());
        }
        int type = getTypeFromString(typeAndValue[0], line, context.programName());
        int value;
        try {
            value = Integer.parseInt(typeAndValue[1].strip());
        } catch (NumberFormatException nfe) {
            throw new AssemblerException(context.programName(), line.originalFileName(), line.originalLineNumber(),
                    "Invalid .PLACE value: " + nfe.getMessage(), line.content());
        }
        String[] comps = parts[2].split("\\|");
        if (comps.length != Config.WORLD_DIMENSIONS) {
            throw new AssemblerException(context.programName(), line.originalFileName(), line.originalLineNumber(),
                    "Invalid .PLACE vector dimensionality.", line.content());
        }
        int[] relativePos = new int[Config.WORLD_DIMENSIONS];
        try {
            for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                relativePos[i] = Integer.parseInt(comps[i].strip());
            }
        } catch (NumberFormatException nfe) {
            throw new AssemblerException(context.programName(), line.originalFileName(), line.originalLineNumber(),
                    "Invalid .PLACE vector component: " + nfe.getMessage(), line.content());
        }
        context.initialWorldObjects().put(relativePos, new Molecule(type, value));
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