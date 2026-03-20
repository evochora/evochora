package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.frontend.module.IDependencyInfo;

/**
 * Handler for setting up module relationships from dependency data.
 * Called during Phase 4 (before AST walk) in three passes:
 * <ol>
 *   <li>registerScope — compute alias chains (reverse topological order)</li>
 *   <li>registerRelationships — register relationships in module scopes (after all modules registered)</li>
 *   <li>resolveBindings — resolve cross-module bindings like USING (after all relationships registered)</li>
 * </ol>
 *
 * @param <T> The specific dependency info type this handler processes.
 */
public interface IDependencySetupHandler<T extends IDependencyInfo> {

    /**
     * Pass 1: Compute alias chains for this dependency.
     * Called in reverse topological order (root first).
     */
    void registerScope(T dependency, ModuleSetupContext ctx);

    /**
     * Pass 2: Register relationships in module scopes.
     * Called in topological order after all modules are registered in the symbol table.
     */
    default void registerRelationships(T dependency, ModuleSetupContext ctx) {}

    /**
     * Pass 3: Resolve cross-module bindings (e.g., USING).
     * Called in topological order after all relationships are registered.
     */
    default void resolveBindings(T dependency, ModuleSetupContext ctx) {}
}
