package org.evochora.compiler.model.ast;

/**
 * Capability interface for AST nodes that mark module context boundaries.
 * Used by the framework to track which module context is active during traversal,
 * without depending on specific feature node types.
 */
public interface IModuleContextBoundary {

    /**
     * Returns whether this boundary is a context entry (push) or exit (pop).
     *
     * @return {@code true} if traversal enters a module context at this node,
     *         {@code false} if it leaves the enclosing one
     */
    boolean isPush();

    /**
     * Returns the module alias chain for push boundaries (e.g., "PRED.MATH"),
     * or null for pop boundaries and for source-type push where the parent context is preserved.
     *
     * @return the alias chain of the module to switch to, or {@code null} when the
     *         enclosing module context remains in effect
     */
    String aliasChain();
}
