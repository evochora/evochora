package org.evochora.compiler.backend.layout;

import org.evochora.compiler.ir.IrDirective;

/**
 * Handles a specific directive during the layout phase.
 * Implementations should modify the {@link LayoutContext} state accordingly.
 */
public interface ILayoutDirectiveHandler {

	/**
	 * Processes the given directive, possibly updating position, direction,
	 * or emitting initial world objects.

	 * @param directive The IR directive.
	 * @param context   The mutable layout context.
	 */
	void handle(IrDirective directive, LayoutContext context);
}


