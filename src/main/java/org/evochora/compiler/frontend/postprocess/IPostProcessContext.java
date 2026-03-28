package org.evochora.compiler.frontend.postprocess;

import org.evochora.compiler.model.ast.TypedLiteralNode;

/**
 * Context provided to {@link IPostProcessHandler} implementations during Phase 6 (AST Post-Processing).
 *
 * <p>Provides methods for handlers to record constant definitions. The {@code AstPostProcessor}
 * creates this context and applies all collected replacements in a second pass.</p>
 *
 * <p>Register aliases are resolved via the SymbolTable directly (scope-aware lookup) and do not
 * need collection through this context.</p>
 */
public interface IPostProcessContext {

	/**
	 * Records a constant definition for later replacement of matching identifiers.
	 * The constant is scoped to the current module context internally.
	 *
	 * @param constantName The constant name as it appears in source (e.g., "MAX_SIZE").
	 * @param value        The typed literal value of the constant.
	 */
	void collectConstant(String constantName, TypedLiteralNode value);
}
