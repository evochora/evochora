package org.evochora.compiler.backend.link.features;

import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Resolves IrLabelRef operands to n-D delta vectors using the layout mapping.
 * In this version, the vector represents the program-relative coordinate of the label.
 */
public final class LabelRefLinkingRule implements ILinkingRule {

    @Override
    public IrInstruction apply(IrInstruction instruction, LinkingContext context, LayoutResult layout) {
        List<IrOperand> ops = instruction.operands();
        if (ops == null || ops.isEmpty()) return instruction;

        List<IrOperand> rewritten = null;
        for (int i = 0; i < ops.size(); i++) {
            IrOperand op = ops.get(i);
            if (op instanceof IrLabelRef ref) {
                Integer targetAddr = layout.labelToAddress().get(ref.labelName());
                if (targetAddr != null) {
                    // NEUE LOGIK: Der Vektor ist jetzt die absolute (programmrelative)
                    // Koordinate des Ziels, nicht mehr die Differenz zum aktuellen IP.
                    int[] dstCoord = layout.linearAddressToCoord().get(targetAddr);
                    if (dstCoord != null) {
                        // Eine Kopie erstellen, um Seiteneffekte zu vermeiden.
                        int[] absoluteVector = Arrays.copyOf(dstCoord, dstCoord.length);

                        if (rewritten == null) {
                            rewritten = new ArrayList<>(ops);
                        }
                        rewritten.set(i, new IrVec(absoluteVector));
                    }
                }
            }
        }
        return rewritten != null ? new IrInstruction(instruction.opcode(), rewritten, instruction.source()) : instruction;
    }
}