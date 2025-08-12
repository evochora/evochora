package org.evochora.compiler.internal.legacy.directives;

import org.evochora.app.setup.Config;
import org.evochora.compiler.internal.legacy.AnnotatedLine;
import org.evochora.compiler.internal.legacy.AssemblerException;
import org.evochora.compiler.internal.legacy.PassManagerContext;

public class DirDirectiveHandler implements IDirectiveHandler {
    @Override
    public void handle(AnnotatedLine line, PassManagerContext context) {
        String stripped = line.content().split("#", 2)[0].strip();
        String[] parts = stripped.split("\\s+");
        if (parts.length != 2) {
            throw new AssemblerException(context.programName(), line.originalFileName(), line.originalLineNumber(),
                    "Invalid .DIR syntax. Expected: .DIR <v0|v1|...>", line.content());
        }
        String[] comps = parts[1].split("\\|");
        if (comps.length != Config.WORLD_DIMENSIONS) {
            throw new AssemblerException(context.programName(), line.originalFileName(), line.originalLineNumber(),
                    "Invalid .DIR vector dimensionality.", line.content());
        }
        int[] newDv = new int[Config.WORLD_DIMENSIONS];
        try {
            for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                newDv[i] = Integer.parseInt(comps[i].strip());
            }
        } catch (NumberFormatException nfe) {
            throw new AssemblerException(context.programName(), line.originalFileName(), line.originalLineNumber(),
                    "Invalid .DIR vector component: " + nfe.getMessage(), line.content());
        }
        System.arraycopy(newDv, 0, context.currentDv(), 0, newDv.length);
    }
}