package org.evochora.compiler.backend.rewrite;

import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrProgram;

import java.util.List;

/**
 * Phase 8: rewrites the IR by handing the whole item stream to every registered rule in turn.
 * The rewriter itself knows no rule; what a feature does to the IR before layout, such as the
 * marshalling of procedure calls, it does in a rule it registers.
 */
public final class IrRewriter {

	private final RewriteRegistry registry;

	/**
	 * Creates a rewriter applying the rules of the given registry.
	 *
	 * @param registry The rules to apply, in their registration order.
	 */
	public IrRewriter(RewriteRegistry registry) {
		this.registry = registry;
	}

	/**
	 * Applies every rule to the program, each on the output of the one before.
	 *
	 * @param program The generated IR program.
	 * @param isa     The instruction set the rules may consult.
	 * @return A program with the rewritten items, under the same name.
	 */
	public IrProgram rewrite(IrProgram program, IInstructionSet isa) {
		List<IrItem> items = program.items();
		for (IRewriteRule rule : registry.rules()) {
			items = rule.apply(items, isa);
		}
		return new IrProgram(program.programName(), items);
	}
}
