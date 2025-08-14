package org.evochora.compiler.frontend.irgen;

import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * Converts a specific AST node type into zero or more IR items.
 * <p>
 * Implementations should be stateless. All output must be emitted via the provided {@link IrGenContext}.
 *
 * @param <T> The concrete AST node type handled by this converter.
 */
public interface IAstNodeToIrConverter<T extends AstNode> {

	/**
	 * Converts the given AST node into IR and emits results via the provided context.
	 *
	 * @param node The AST node to convert.
	 * @param ctx  The IR generation context used to emit IR items and access diagnostics.
	 */
	void convert(T node, IrGenContext ctx);
}


