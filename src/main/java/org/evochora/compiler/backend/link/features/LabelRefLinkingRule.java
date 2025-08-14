package org.evochora.compiler.backend.link.features;

import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves IrLabelRef operands to n-D delta vectors using the layout mapping.
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
					int[] srcCoord = layout.linearAddressToCoord().get(context.currentAddress());
					int[] dstCoord = layout.linearAddressToCoord().get(targetAddr);
					int dims = Math.max(srcCoord.length, dstCoord.length);
					int[] delta = new int[dims];
					for (int d = 0; d < dims; d++) {
						int s = d < srcCoord.length ? srcCoord[d] : 0;
						int t = d < dstCoord.length ? dstCoord[d] : 0;
						delta[d] = t - s;
					}
					if (rewritten == null) rewritten = new ArrayList<>(ops);
					rewritten.set(i, new IrVec(delta));
				}
			}
		}
		return rewritten != null ? new IrInstruction(instruction.opcode(), rewritten, instruction.source()) : instruction;
	}
}


