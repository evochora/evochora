package org.evochora.compiler.features.importdir;

import java.util.Locale;
import org.evochora.compiler.frontend.semantics.IDependencySetupHandler;
import org.evochora.compiler.frontend.semantics.ModuleSetupContext;
import org.evochora.compiler.model.symbols.ModuleScope;

/**
 * Sets up module relationships for .IMPORT dependencies: binds the import alias in the importing
 * placement's scope to the placement the import creates.
 */
public class ImportModuleSetupHandler implements IDependencySetupHandler<ImportDependencyInfo> {

    @Override
    public void registerRelationships(ImportDependencyInfo dep, ModuleSetupContext ctx) {
        String importAlias = dep.alias().toUpperCase(Locale.ROOT);
        String importedAliasChain = dep.aliasChain();
        ModuleScope modScope = ctx.getModuleScope(ctx.currentAliasChain());
        if (modScope != null) {
            // Only the placement the alias leads to is recorded here. Whether a path from outside
            // may continue through the alias is carried by the alias symbol, which the lookup asks.
            modScope.bindAlias(importAlias, importedAliasChain);
        }
    }
}
