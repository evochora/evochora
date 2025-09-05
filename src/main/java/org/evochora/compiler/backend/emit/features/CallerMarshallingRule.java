package org.evochora.compiler.backend.emit.features;

import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Inserts caller-side PUSH/POP sequences around CALL based on a preceding
 * core:call_with directive that carries actual registers.
 */
public final class CallerMarshallingRule implements IEmissionRule {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<IrItem> apply(List<IrItem> items, LinkingContext linkingContext) {
		List<IrItem> out = new ArrayList<>(items.size() + 8);
		int i = 0;
		while (i < items.size()) {
			IrItem it = items.get(i);

			// New logic for REF/VAL calls
			if (it instanceof IrInstruction call && "CALL".equals(call.opcode()) && (!call.refOperands().isEmpty() || !call.valOperands().isEmpty())) {
				// Pre-call: Push arguments. REFs then VALs, each block in reverse.
				// REF arguments
				for (int j = call.refOperands().size() - 1; j >= 0; j--) {
					out.add(new IrInstruction("PUSH", List.of(call.refOperands().get(j)), call.source()));
				}
				// VAL arguments
				for (int j = call.valOperands().size() - 1; j >= 0; j--) {
					IrOperand operand = call.valOperands().get(j);
					if (operand instanceof IrImm imm) {
						out.add(new IrInstruction("PUSI", List.of(imm), call.source()));
					} else { // IrReg
						out.add(new IrInstruction("PUSH", List.of(operand), call.source()));
					}
				}

				// The CALL itself
				out.add(call);

				// Post-call: Clean up stack in forward order of declaration
				// REF arguments are restored to their registers.
				for (IrOperand refOperand : call.refOperands()) {
					out.add(new IrInstruction("POP", List.of(refOperand), call.source()));
				}
				// VAL arguments are left on the stack for the callee to clean up.
				i++;
				continue;
			}

			// Old logic for `core:call_with`
			if (it instanceof IrDirective dir && "core".equals(dir.namespace()) && "call_with".equals(dir.name())) {
				IrValue.ListVal listVal = (IrValue.ListVal) dir.args().get("actuals");
				List<IrValue> vals = listVal != null ? listVal.elements() : List.of();
				List<String> actualRegs = new ArrayList<>(vals.size());
				for (IrValue v : vals) {
					if (v instanceof IrValue.Str s) actualRegs.add(s.value());
				}

				if (i + 1 < items.size() && items.get(i + 1) instanceof IrInstruction call && "CALL".equals(call.opcode())) {
					for (String r : actualRegs) {
						out.add(new IrInstruction("PUSH", List.of(new IrReg(r)), dir.source()));
					}
					out.add(items.get(i + 1)); // Add the call
					for (int a = actualRegs.size() - 1; a >= 0; a--) {
						out.add(new IrInstruction("POP", List.of(new IrReg(actualRegs.get(a))), dir.source()));
					}
					i += 2;
					continue;
				}
				// If not followed by CALL, just drop directive
				i++;
				continue;
			}
			out.add(it);
			i++;
		}
		return out;
	}
}



