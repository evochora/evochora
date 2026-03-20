package org.evochora.compiler.features.require;

import org.evochora.compiler.frontend.semantics.IDependencySetupHandler;
import org.evochora.compiler.frontend.semantics.ModuleSetupContext;
import org.evochora.compiler.model.symbols.ModuleScope;

/**
 * Sets up module relationships for .REQUIRE dependencies.
 * Registers require relationships in module scopes.
 */
public class RequireModuleSetupHandler implements IDependencySetupHandler<RequireDependencyInfo> {

    @Override
    public void registerScope(RequireDependencyInfo dep, ModuleSetupContext ctx) {
        // No alias chain computation needed for .REQUIRE
    }

    @Override
    public void registerRelationships(RequireDependencyInfo dep, ModuleSetupContext ctx) {
        ModuleScope modScope = ctx.getModuleScope(ctx.currentAliasChain());
        if (modScope != null) {
            modScope.requires().put(dep.alias().toUpperCase(), dep.path());
        }
    }
}
