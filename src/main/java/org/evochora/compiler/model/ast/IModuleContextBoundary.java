package org.evochora.compiler.model.ast;

/**
 * Capability interface for AST nodes that mark module context boundaries.
 * Used by the framework to track which module context is active during traversal,
 * without depending on specific feature node types.
 */
public interface IModuleContextBoundary {

    /**
     * Returns whether this boundary is a context entry (push) or exit (pop).
     */
    boolean isPush();

    /**
     * Returns the module alias chain for push boundaries (e.g., "PRED.MATH"),
     * or null for pop boundaries and for source-type push where the parent context is preserved.
     */
    String aliasChain();
}
