package org.evochora.compiler.features.reg;

import org.evochora.compiler.frontend.postprocess.IPostProcessContext;
import org.evochora.compiler.frontend.postprocess.IPostProcessHandler;
import org.evochora.compiler.model.ast.AstNode;

/**
 * Collects module-scoped register aliases from {@link RegNode} AST nodes.
 *
 * <p>Each {@code .REG} directive defines an alias that maps a symbolic name to a physical
 * register. This handler extracts the alias and delegates storage to the
 * {@link IPostProcessContext}, which handles module qualification.</p>
 */
public class RegPostProcessHandler implements IPostProcessHandler {

	@Override
	public void collect(AstNode node, IPostProcessContext context) {
		RegNode regNode = (RegNode) node;
		context.collectRegisterAlias(regNode.alias(), regNode.register());
	}
}
