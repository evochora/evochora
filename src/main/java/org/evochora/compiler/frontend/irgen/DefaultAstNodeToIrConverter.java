package org.evochora.compiler.frontend.irgen;

import org.evochora.compiler.model.ast.AstNode;

/**
 * Default/fallback converter used when no specific converter is registered.
 * Reports an error for unknown top-level nodes.
 */
public final class DefaultAstNodeToIrConverter implements IAstNodeToIrConverter<AstNode> {

	/**
	 * {@inheritDoc}
	 * <p>
	 * This implementation reports an error for unregistered node types.
	 *
	 * @param node The node to convert.
	 * @param ctx  The generation context.
	 */
	@Override
	public void convert(AstNode node, IrGenContext ctx) {
		ctx.diagnostics().reportError(
				"No IR converter registered for node type " + node.getClass().getSimpleName(),
				"unknown",
				-1
		);
	}
}


