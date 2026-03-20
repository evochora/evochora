package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.semantics.IDependencySetupHandler;
import org.evochora.compiler.frontend.semantics.ModuleSetupContext;
import org.evochora.compiler.model.symbols.ModuleScope;

/**
 * Sets up module relationships for .IMPORT dependencies.
 * registerScope: Computes alias chains for imported modules.
 * registerRelationships: Registers import relationships in module scopes.
 * resolveBindings: Resolves USING bindings between modules.
 */
public class ImportModuleSetupHandler implements IDependencySetupHandler<ImportDependencyInfo> {

    @Override
    public void registerScope(ImportDependencyInfo dep, ModuleSetupContext ctx) {
        String moduleAliasChain = ctx.currentAliasChain();
        String importAlias = dep.alias().toUpperCase();
        String importedAliasChain = (moduleAliasChain == null || moduleAliasChain.isEmpty())
                ? importAlias
                : moduleAliasChain + "." + importAlias;
        ctx.pathToAliasChain().put(dep.resolvedPath(), importedAliasChain);
    }

    @Override
    public void registerRelationships(ImportDependencyInfo dep, ModuleSetupContext ctx) {
        String importAlias = dep.alias().toUpperCase();
        String importedAliasChain = ctx.pathToAliasChain().get(dep.resolvedPath());
        ModuleScope modScope = ctx.getModuleScope(ctx.currentAliasChain());
        if (modScope != null) {
            modScope.imports().put(importAlias, importedAliasChain);
        }
    }

    @Override
    public void resolveBindings(ImportDependencyInfo dep, ModuleSetupContext ctx) {
        String importedAliasChain = ctx.pathToAliasChain().get(dep.resolvedPath());
        ModuleScope importedModScope = ctx.getModuleScope(importedAliasChain);
        if (importedModScope == null) return;

        for (ImportDependencyInfo.UsingDecl using : dep.usings()) {
            String sourceAlias = using.sourceAlias().toUpperCase();
            ModuleScope importerScope = ctx.getModuleScope(ctx.currentAliasChain());
            if (importerScope == null) continue;
            String sourceAliasChain = importerScope.imports().get(sourceAlias);
            if (sourceAliasChain != null) {
                importedModScope.usingBindings().put(using.targetAlias().toUpperCase(), sourceAliasChain);
            }
        }
    }
}
