package org.evochora.compiler.features.ctx;

import org.evochora.compiler.backend.link.ILinkingDirectiveHandler;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

/**
 * Handles {@code push_ctx} directives during linking by pushing the module alias chain
 * onto the linking context stack. This ensures symbol resolution uses the correct
 * module scope for subsequent instructions.
 */
public class PushCtxLinkingHandler implements ILinkingDirectiveHandler {

	@Override
	public void handle(IrDirective directive, LinkingContext context) {
		IrValue chainValue = directive.args().get("aliasChain");
		if (chainValue instanceof IrValue.Str s) {
			context.pushAliasChain(s.value());
		} else {
			// .SOURCE: no aliasChain arg. Push current chain so pop is symmetric.
			context.pushAliasChain(context.currentAliasChain());
		}
	}
}
