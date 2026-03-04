package org.evochora.compiler.backend.emit;

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
	 * @return The rewritten IR items.
	 */
	List<IrItem> apply(List<IrItem> items);
}



