package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.semantics.IDependencySetupHandler;
import org.evochora.compiler.frontend.semantics.ModuleSetupContext;
import org.evochora.compiler.model.symbols.ModuleScope;

/**
 * Sets up module relationships for .IMPORT dependencies.
 * registerRelationships: Registers the import of the placement the dependency created in the
 * importing placement's scope.
 * resolveBindings: Resolves USING bindings between modules.
 */
public class ImportModuleSetupHandler implements IDependencySetupHandler<ImportDependencyInfo> {

    @Override
    public void registerRelationships(ImportDependencyInfo dep, ModuleSetupContext ctx) {
        String importAlias = dep.alias().toUpperCase();
        String importedAliasChain = dep.aliasChain();
        ModuleScope modScope = ctx.getModuleScope(ctx.currentAliasChain());
        if (modScope != null) {
            // Whether a name may reach through this import from outside. Resolution walks the
            // chain segment by segment and asks at every step, so a module that keeps its import
            // to itself ends the chain there.
            modScope.addImport(importAlias, importedAliasChain, dep.exported());
        }
    }

    @Override
    public void resolveBindings(ImportDependencyInfo dep, ModuleSetupContext ctx) {
        String importedAliasChain = dep.aliasChain();
        ModuleScope importedModScope = ctx.getModuleScope(importedAliasChain);
        if (importedModScope == null) return;

        for (ImportDependencyInfo.UsingDecl using : dep.usings()) {
            String sourceAlias = using.sourceAlias().toUpperCase();
            ModuleScope importerScope = ctx.getModuleScope(ctx.currentAliasChain());
            if (importerScope == null) continue;
            // Either a module this one imported, or one it received itself - the latter is what
            // lets a requirement travel further down than the module that first accepted it.
            String sourceAliasChain = importerScope.imports().get(sourceAlias);
            if (sourceAliasChain == null) {
                sourceAliasChain = importerScope.usingBindings().get(sourceAlias);
            }
            if (sourceAliasChain != null) {
                importedModScope.bindUsing(using.targetAlias().toUpperCase(), sourceAliasChain);
            }
        }
    }
}
