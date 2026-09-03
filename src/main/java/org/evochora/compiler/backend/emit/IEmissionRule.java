package org.evochora.compiler.backend.emit;

import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.ir.IrItem;

import java.util.List;

/**
 * Rewriter rule that can expand or modify the IR stream before machine code emission.
 */
public interface IEmissionRule {

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



