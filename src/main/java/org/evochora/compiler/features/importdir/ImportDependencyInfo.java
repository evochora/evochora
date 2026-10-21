package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.lexer.IDependencyInfo;

/**
 * Dependency data for an .IMPORT directive the Lexer phase discovers while it reads the files: the address of the
 * imported file, path and alias. What follows the alias on the line is the parser's.
 *
 * @param path The import path as written in source.
 * @param alias The local alias for the imported module.
 * @param resolvedPath The resolved absolute path of the imported module.
 * @param aliasChain The alias chain of the placement this import creates: the importing
 *                   placement's chain followed by the alias.
 */
public record ImportDependencyInfo(
        String path,
        String alias,
        String resolvedPath,
        String aliasChain
) implements IDependencyInfo {

    @Override public String directiveName() { return ".IMPORT"; }
    @Override public boolean allowedInSourceFile() { return false; }
}
