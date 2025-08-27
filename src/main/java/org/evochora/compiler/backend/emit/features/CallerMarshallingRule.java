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
			if (it instanceof IrDirective dir && "core".equals(dir.namespace()) && "call_with".equals(dir.name())) {
				// Extract actuals as a list of register names
				IrValue.ListVal listVal = (IrValue.ListVal) dir.args().get("actuals");
				List<IrValue> vals = listVal != null ? listVal.elements() : List.of();
				List<String> actualRegs = new ArrayList<>(vals.size());
				for (IrValue v : vals) {
					if (v instanceof IrValue.Str s) actualRegs.add(s.value());
				}
				// Expect next item to be CALL instruction
				if (i + 1 < items.size() && items.get(i + 1) instanceof IrInstruction call && "CALL".equals(call.opcode())) {
					// Insert PUSH actuals in order
					for (String r : actualRegs) out.add(new IrInstruction("PUSH", List.of(new IrReg(r)), dir.source()));
					// Add the CALL itself
					out.add(call);
					// Insert POP actuals in reverse order
					for (int a = actualRegs.size() - 1; a >= 0; a--) out.add(new IrInstruction("POP", List.of(new IrReg(actualRegs.get(a))), dir.source()));
					// Consume directive and CALL
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



