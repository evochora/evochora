package org.evochora.compiler.frontend.postprocess;

import org.evochora.compiler.model.ast.AstNode;

/**
 * Handler for Phase 6 (AST Post-Processing).
 *
 * <p>Each handler handles one type of AST node and collects replacements or constants
 * via the {@link IPostProcessContext}. This replaces the hardcoded {@code instanceof}
 * dispatch chain in {@code AstPostProcessor}.</p>
 *
 * <p>Implementations will be created in step C4 when the AstPostProcessor is refactored.</p>
 */
public interface IPostProcessHandler {

	/**
	 * Collects replacements or constants for the given AST node during post-processing.
	 *
	 * @param node    The AST node to process.
	 * @param context The post-process context for recording replacements and constants.
	 */
	void collect(AstNode node, IPostProcessContext context);
}
