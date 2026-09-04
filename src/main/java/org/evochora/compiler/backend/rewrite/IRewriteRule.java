package org.evochora.compiler.backend.rewrite;

import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.ir.IrItem;

import java.util.List;

/**
 * A rule of the IR rewriting phase: it takes the whole IR item stream and returns the stream
 * it should be, expanding or modifying items. The rules run in registration order, each on
 * the output of the one before, after IR generation and before layout.
 */
public interface IRewriteRule {

	/**
	 * Applies this rule to the given IR item stream.
	 *
	 * @param items The input IR items.
	 * @param isa   The instruction set, for what a rule needs to know about the opcodes it
	 *              rewrites, such as which conditional negates another.
	 * @return The rewritten IR items.
	 */
	List<IrItem> apply(List<IrItem> items, IInstructionSet isa);
}
