package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.model.ast.AstNode;

/**
 * Contributor for Phase 5 (Token Map Generation).
 *
 * <p>Each contributor handles one type of AST node and adds its token information
 * to the token map via the {@link ITokenMapContext}. This replaces the hardcoded
 * {@code instanceof} dispatch chain in {@code TokenMapGenerator}.</p>
 *
 * <p>Implementations will be created in step C3 when the TokenMapGenerator is refactored.</p>
 */
public interface ITokenMapContributor {

	/**
	 * Contributes token map entries for the given AST node.
	 *
	 * @param node    The AST node to process.
	 * @param context The token map context for adding entries and resolving symbols.
	 */
	void contribute(AstNode node, ITokenMapContext context);
}
