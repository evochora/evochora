package org.evochora.compiler.features.label;

import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.model.ir.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves IrLabelRef operands to the values the runtime matches jumps against.
 * <p>
 * A reference carries the label's qualified name, as the frontend resolved it, and the layout
 * keys its label addresses by the same names. A reference whose label the layout does not know
 * is left in place; the emitter reports it.
 */
public class LabelRefLinkingRule implements ILinkingRule {

    @Override
    public IrInstruction apply(IrInstruction instruction, LinkingContext context, LayoutResult layout) {
        List<IrOperand> ops = instruction.operands();
        if (ops == null || ops.isEmpty()) return instruction;

        List<IrOperand> rewritten = null;
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i) instanceof IrLabelRef ref && layout.labelToAddress().containsKey(ref.labelName())) {
                if (rewritten == null) {
                    rewritten = new ArrayList<>(ops);
                }
                rewritten.set(i, new IrTypedImm("LABELREF", IrLabelDef.valueOf(ref.labelName())));
            }
        }
        return rewritten != null ? instruction.withOperands(rewritten) : instruction;
    }
}
