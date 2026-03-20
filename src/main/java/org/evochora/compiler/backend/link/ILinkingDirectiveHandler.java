package org.evochora.compiler.backend.link;

import org.evochora.compiler.model.ir.IrDirective;

/**
 * Handles a specific directive during the linking phase.
 * Implementations modify {@link LinkingContext} state (e.g., alias chain tracking).
 */
public interface ILinkingDirectiveHandler {

	/**
	 * Processes the given directive, updating linking context state as needed.
	 *
	 * @param directive The IR directive.
	 * @param context   The mutable linking context.
	 */
	void handle(IrDirective directive, LinkingContext context);
}
