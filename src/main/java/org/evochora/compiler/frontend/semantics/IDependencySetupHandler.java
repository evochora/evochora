package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.frontend.module.IDependencyInfo;

/**
 * Handler for setting up module relationships from dependency data.
 * Called during Phase 4 (before AST walk), in one step after every placement has been registered
 * under its alias chain: the relationships of each placement are registered in the module
 * scopes, the placements after the placements they import.
 *
 * @param <T> The specific dependency info type this handler processes.
 */
public interface IDependencySetupHandler<T extends IDependencyInfo> {

    /**
     * Registers relationships in module scopes.
     * Called for every placement after the placements it imports.
     *
     * @param dependency The dependency declared by the module being set up.
     * @param ctx        Context of the declaring placement. Every module already has a scope in
     *                   the symbol table, so scopes may be looked up and modified here.
     */
    void registerRelationships(T dependency, ModuleSetupContext ctx);
}
