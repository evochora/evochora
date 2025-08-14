package org.evochora.compiler.frontend.irgen;

import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * Default/fallback converter used when no specific converter is registered.
 * For unknown top-level nodes, it emits nothing and reports a warning.
 */
public final class DefaultAstNodeToIrConverter implements IAstNodeToIrConverter<AstNode> {

	@Override
	public void convert(AstNode node, IrGenContext ctx) {
		ctx.diagnostics().reportWarning(
				"IR: No converter registered for node type " + node.getClass().getSimpleName(),
				"unknown",
				-1
		);
	}
}


