package org.evochora.compiler.features.proc;


import org.evochora.compiler.frontend.postprocess.IPostProcessContext;
import org.evochora.compiler.frontend.postprocess.IPostProcessHandler;
import org.evochora.compiler.model.ast.AstNode;

/**
 * Collects procedure-scoped register aliases from {@link PregNode} AST nodes.
 *
 * <p>Each {@code .PREG} directive inside a procedure defines a local alias that maps
 * a symbolic name to a physical register. This handler extracts the alias and delegates
 * storage to the {@link IPostProcessContext}, which handles module qualification.</p>
 */
public class PregPostProcessHandler implements IPostProcessHandler {

	@Override
	public void collect(AstNode node, IPostProcessContext context) {
		PregNode pregNode = (PregNode) node;
		context.collectRegisterAlias(pregNode.alias().text(), pregNode.targetRegister().text());
	}
}
