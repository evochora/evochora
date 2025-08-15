package org.evochora.compiler.internal.legacy.directives;

import org.evochora.runtime.Config;
import org.evochora.compiler.internal.legacy.AnnotatedLine;
import org.evochora.compiler.internal.legacy.AssemblerException;
import org.evochora.compiler.internal.legacy.PassManagerContext;

public class OrgDirectiveHandler implements IDirectiveHandler {
    @Override
    public void handle(AnnotatedLine line, PassManagerContext context) {
        String stripped = line.content().split("#", 2)[0].strip();
        String[] parts = stripped.split("\\s+");
        if (parts.length != 2) {
            throw new AssemblerException(context.programName(), line.originalFileName(), line.originalLineNumber(),
                    "Invalid .ORG syntax. Expected: .ORG <p0|p1|...>", line.content());
        }
        String[] comps = parts[1].split("\\|");
        if (comps.length != Config.WORLD_DIMENSIONS) {
            throw new AssemblerException(context.programName(), line.originalFileName(), line.originalLineNumber(),
                    "Invalid .ORG vector dimensionality.", line.content());
        }
        int[] newPos = new int[Config.WORLD_DIMENSIONS];
        try {
            for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                newPos[i] = Integer.parseInt(comps[i].strip());
            }
        } catch (NumberFormatException nfe) {
            throw new AssemblerException(context.programName(), line.originalFileName(), line.originalLineNumber(),
                    "Invalid .ORG vector component: " + nfe.getMessage(), line.content());
        }
        System.arraycopy(newPos, 0, context.currentPos(), 0, newPos.length);
    }
}