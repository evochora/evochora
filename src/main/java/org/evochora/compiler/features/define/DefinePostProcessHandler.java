package org.evochora.compiler.features.define;

import org.evochora.compiler.frontend.postprocess.IPostProcessHandler;
import org.evochora.compiler.frontend.postprocess.IPostProcessContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;

/**
 * Collects constant definitions from {@link DefineNode} AST nodes.
 *
 * <p>Each {@code .DEFINE} directive defines a named constant. This handler extracts
 * typed literal values and delegates storage to the {@link IPostProcessContext},
 * which handles module-scoped constant tracking.</p>
 */
public class DefinePostProcessHandler implements IPostProcessHandler {

	@Override
	public void collect(AstNode node, IPostProcessContext context) {
		DefineNode defineNode = (DefineNode) node;
		if (defineNode.value() instanceof TypedLiteralNode typedValue) {
			context.collectConstant(defineNode.name(), typedValue);
		}
	}
}
