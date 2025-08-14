package org.evochora.compiler.backend.emit;

import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.ir.IrItem;

import java.util.List;

/**
 * Rewriter rule that can expand or modify the IR stream before machine code emission.
 */
public interface IEmissionRule {

	/**
	 * Applies this rule to the given IR item stream.
	 *
	 * @param items          The input IR items (linked).
	 * @param linkingContext Linking metadata (e.g., call-site bindings), may be empty.
	 * @return The rewritten IR items.
	 */
	List<IrItem> apply(List<IrItem> items, LinkingContext linkingContext);
}



