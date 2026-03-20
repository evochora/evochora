package org.evochora.compiler.features.ctx;

import org.evochora.compiler.backend.link.ILinkingDirectiveHandler;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.model.ir.IrDirective;

/**
 * Handles {@code pop_ctx} directives during linking by popping the module alias chain
 * from the linking context stack, restoring the previous module scope.
 */
public class PopCtxLinkingHandler implements ILinkingDirectiveHandler {

	@Override
	public void handle(IrDirective directive, LinkingContext context) {
		context.popAliasChain();
	}
}
