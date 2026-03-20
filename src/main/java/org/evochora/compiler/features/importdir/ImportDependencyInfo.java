package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.module.IDependencyInfo;

import java.util.List;

/**
 * Dependency data for an .IMPORT directive discovered during Phase 0 scanning.
 *
 * @param path The import path as written in source.
 * @param alias The local alias for the imported module.
 * @param usings The USING clauses on this import.
 * @param resolvedPath The resolved absolute path of the imported module.
 */
public record ImportDependencyInfo(
        String path,
        String alias,
        List<UsingDecl> usings,
        String resolvedPath
) implements IDependencyInfo {

    @Override public String directiveName() { return ".IMPORT"; }
    @Override public boolean allowedInSourceFile() { return false; }

    /**
     * A USING clause on an import declaration.
     *
     * @param sourceAlias The alias being provided.
     * @param targetAlias The alias being satisfied.
     */
    public record UsingDecl(String sourceAlias, String targetAlias) {}
}
