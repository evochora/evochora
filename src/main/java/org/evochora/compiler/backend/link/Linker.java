package org.evochora.compiler.backend.link;

import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.ir.IrInstruction;
import org.evochora.compiler.ir.IrItem;
import org.evochora.compiler.ir.IrProgram;

import java.util.ArrayList;
import java.util.List;

/**
 * Linking pass: resolves symbolic references using the layout result. Parameter binding is collected
 * as metadata only at this stage.
 */
public final class Linker {

	private final LinkingRegistry registry;

	public Linker(LinkingRegistry registry) { this.registry = registry; }

	public IrProgram link(IrProgram program, LayoutResult layout, LinkingContext context) {
		List<IrItem> out = new ArrayList<>();
		for (IrItem item : program.items()) {
			if (item instanceof IrInstruction ins) {
				for (ILinkingRule rule : registry.rules()) {
					ins = rule.apply(ins, context, layout);
				}
				out.add(ins);
				// advance address by opcode + operands
				int arity = ins.operands() != null ? ins.operands().size() : 0;
				context.nextAddress(); // opcode
				for (int i = 0; i < arity; i++) context.nextAddress();
			} else {
				out.add(item);
			}
		}
		return new IrProgram(program.programName(), out);
	}
}


