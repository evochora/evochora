package org.evochora.compiler.features.importdir;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;

import java.util.List;

/**
 * AST node for the {@code .IMPORT} directive.
 *
 * <p>Syntax: {@code [EXPORT] .IMPORT "path" AS ALIAS [USING source AS target]*}
 *
 * @param path       The imported file path (unquoted string value from the token).
 * @param alias      The local alias name for the imported module.
 * @param usings     The USING clauses providing dependencies to the imported module.
 * @param exported   Whether this import is re-exported to parent modules via the EXPORT prefix.
 * @param sourceInfo The source location of the alias token.
 */
public record ImportNode(
        String path,
        String alias,
        List<UsingClause> usings,
        boolean exported,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {

    /**
     * A USING clause on an import, providing a module to satisfy a dependency.
     *
     * @param sourceAlias      The alias being provided (must be a known import in the current module).
     * @param targetAlias      The alias being satisfied (must match a .REQUIRE in the imported module).
     * @param sourceSourceInfo The source location of the source alias token.
     * @param targetSourceInfo The source location of the target alias token.
     */
    public record UsingClause(
            String sourceAlias,
            String targetAlias,
            SourceInfo sourceSourceInfo,
            SourceInfo targetSourceInfo
    ) {}
}
