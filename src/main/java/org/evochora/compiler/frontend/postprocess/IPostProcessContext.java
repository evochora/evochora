package org.evochora.compiler.frontend.postprocess;

/**
 * Context provided to {@link IPostProcessHandler} implementations during Phase 6 (AST Post-Processing).
 *
 * <p>The phase replaces identifiers by asking the nodes that define them, see
 * {@link org.evochora.compiler.model.ast.IIdentifierBinding}, so a handler has nothing to
 * record for that. The context is the place where an operation for handlers would be added
 * if a feature needs the phase to do more than replace identifiers.</p>
 */
public interface IPostProcessContext {
}
