package org.evochora.assembler.directives;

import org.evochora.assembler.AnnotatedLine;
import org.evochora.assembler.PassManagerContext;

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