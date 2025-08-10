package org.evochora.assembler.directives;

import org.evochora.assembler.AnnotatedLine;
import org.evochora.assembler.PassManagerContext;
import java.util.Arrays;

public class OrgDirectiveHandler implements IDirectiveHandler {
    @Override
    public void handle(AnnotatedLine line, PassManagerContext context) {
        String[] parts = line.content().strip().split("\\s+");
        int[] newPos = Arrays.stream(parts[1].split("\\|")).mapToInt(Integer::parseInt).toArray();
        System.arraycopy(newPos, 0, context.currentPos(), 0, newPos.length);
    }
}