package org.evochora.compiler.frontend.postprocess;

import org.evochora.compiler.model.ast.TypedLiteralNode;

/**
 * Context provided to {@link IPostProcessHandler} implementations during Phase 6 (AST Post-Processing).
 *
 * <p>Provides methods for handlers to record register alias replacements and constant
 * definitions. The {@code AstPostProcessor} creates this context and applies all collected
 * replacements in a second pass.</p>
 */
public interface IPostProcessContext {

	/**
	 * Records a register alias for later replacement of matching identifiers.
	 * The alias is module-qualified internally using the current module context.
	 *
	 * @param aliasText    The alias name as it appears in source (e.g., "COUNTER").
	 * @param registerText The physical register name (e.g., "%DR0").
	 */
	void collectRegisterAlias(String aliasText, String registerText);

	/**
	 * Records a constant definition for later replacement of matching identifiers.
	 * The constant is scoped to the current module context internally.
	 *
	 * @param constantName The constant name as it appears in source (e.g., "MAX_SIZE").
	 * @param value        The typed literal value of the constant.
	 */
	void collectConstant(String constantName, TypedLiteralNode value);
}
