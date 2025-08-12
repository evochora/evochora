package org.evochora.compiler.internal.legacy.directives;

import org.evochora.compiler.internal.legacy.AnnotatedLine;
import org.evochora.compiler.internal.legacy.PassManagerContext;

public class RegDirectiveHandler implements IDirectiveHandler {
    @Override
    public void handle(AnnotatedLine line, PassManagerContext context) {
        String[] parts = line.content().strip().split("\\s+");
        String regName = parts[1].toUpperCase();
        int regId = Integer.parseInt(parts[2]);
        context.registerMap().put(regName, regId);
        context.registerIdToNameMap().put(regId, regName);
    }
}