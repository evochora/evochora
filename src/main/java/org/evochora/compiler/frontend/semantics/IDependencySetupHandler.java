package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.frontend.module.IDependencyInfo;

/**
 * Handler for setting up module relationships from dependency data.
 * Called during Phase 4 (before AST walk), once per module placement, in two steps after every
 * placement has been registered under its alias chain:
 * <ol>
 *   <li>registerRelationships — register relationships in module scopes (imported placements first)</li>
 *   <li>resolveBindings — resolve cross-module bindings like USING (importing placements first)</li>
 * </ol>
 *
 * @param <T> The specific dependency info type this handler processes.
 */
public interface IDependencySetupHandler<T extends IDependencyInfo> {

    /**
     * Step 1: Register relationships in module scopes.
     * Called for every placement after the placements it imports.
     *
     * @param dependency The dependency declared by the module being set up.
     * @param ctx        Context of the declaring placement. Every module already has a scope in
     *                   the symbol table, so scopes may be looked up and modified here.
     */
    void registerRelationships(T dependency, ModuleSetupContext ctx);

    /**
     * Step 2: Resolve cross-module bindings (e.g., USING).
     * <p>
     * Called for every placement before the placements it imports, after all relationships are
     * registered.
     * A module may hand on a dependency it was given itself, and can only do so once the module
     * above it has bound that dependency — which happens in this same step.
     *
     * @param dependency The dependency declared by the module being set up.
     * @param ctx        Context of the declaring placement. All import relationships are already
     *                   registered, so lookups that follow them resolve.
     */
    default void resolveBindings(T dependency, ModuleSetupContext ctx) {}
}
