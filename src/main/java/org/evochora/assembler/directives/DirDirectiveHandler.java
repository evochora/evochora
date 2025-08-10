package org.evochora.assembler.directives;

import org.evochora.assembler.AnnotatedLine;
import org.evochora.assembler.PassManagerContext;
import java.util.Arrays;

public class DirDirectiveHandler implements IDirectiveHandler {
    @Override
    public void handle(AnnotatedLine line, PassManagerContext context) {
        String[] parts = line.content().strip().split("\\s+");
        int[] newDv = Arrays.stream(parts[1].split("\\|")).mapToInt(Integer::parseInt).toArray();
        System.arraycopy(newDv, 0, context.currentDv(), 0, newDv.length);
    }
}