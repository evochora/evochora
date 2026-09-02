package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.frontend.module.IDependencyInfo;

/**
 * Handler for setting up module relationships from dependency data.
 * Called during Phase 4 (before AST walk) in three steps:
 * <ol>
 *   <li>registerScope — compute alias chains (reverse topological order)</li>
 *   <li>registerRelationships — register relationships in module scopes (after all modules registered)</li>
 *   <li>resolveBindings — resolve cross-module bindings like USING (reverse topological order)</li>
 * </ol>
 *
 * @param <T> The specific dependency info type this handler processes.
 */
public interface IDependencySetupHandler<T extends IDependencyInfo> {

    /**
     * Step 1: Compute alias chains for this dependency.
     * Called in reverse topological order (root first).
     *
     * @param dependency The dependency declared by the module being set up.
     * @param ctx        Context of the declaring module. Alias chains computed here must be
     *                   written into {@link ModuleSetupContext#pathToAliasChain()}, which is
     *                   the map the later passes read from.
     */
    void registerScope(T dependency, ModuleSetupContext ctx);

    /**
     * Step 2: Register relationships in module scopes.
     * Called in topological order after all modules are registered in the symbol table.
     *
     * @param dependency The dependency declared by the module being set up.
     * @param ctx        Context of the declaring module. Every module already has a scope in
     *                   the symbol table, so scopes may be looked up and modified here.
     */
    default void registerRelationships(T dependency, ModuleSetupContext ctx) {}

    /**
     * Step 3: Resolve cross-module bindings (e.g., USING).
     * <p>
     * Called in reverse topological order (root first), after all relationships are registered.
     * A module may hand on a dependency it was given itself, and can only do so once the module
     * above it has bound that dependency — which happens in this same step.
     *
     * @param dependency The dependency declared by the module being set up.
     * @param ctx        Context of the declaring module. All import relationships are already
     *                   registered, so lookups that follow them resolve.
     */
    default void resolveBindings(T dependency, ModuleSetupContext ctx) {}
}
