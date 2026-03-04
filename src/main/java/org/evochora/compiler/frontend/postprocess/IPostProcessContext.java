package org.evochora.compiler.frontend.postprocess;

/**
 * Context provided to {@link IPostProcessHandler} implementations during Phase 6 (AST Post-Processing).
 *
 * <p>Provides methods for handlers to record register alias replacements and constant
 * definitions. The {@code AstPostProcessor} creates this context and applies all collected
 * replacements in a second pass.</p>
 */
public interface IPostProcessContext {
}
