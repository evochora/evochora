package org.evochora.compiler.frontend.parser.features.importdir;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;

import java.util.List;

/**
 * AST node for the {@code .IMPORT} directive.
 *
 * <p>Syntax: {@code [EXPORT] .IMPORT "path" AS ALIAS [USING source AS target]*}
 *
 * @param path     The string literal token containing the file path or URL.
 * @param alias    The identifier token for the local alias name.
 * @param usings   The USING clauses providing dependencies to the imported module.
 * @param exported Whether this import is re-exported to parent modules via the EXPORT prefix.
 */
public record ImportNode(
        Token path,
        Token alias,
        List<UsingClause> usings,
        boolean exported
) implements AstNode, ISourceLocatable {

    /**
     * Backward-compatible constructor without exported flag (defaults to false).
     */
    public ImportNode(Token path, Token alias, List<UsingClause> usings) {
        this(path, alias, usings, false);
    }

    @Override
    public SourceInfo sourceInfo() {
        return alias.toSourceInfo();
    }

    /**
     * A USING clause on an import, providing a module to satisfy a dependency.
     *
     * @param sourceAlias The alias being provided (must be a known import in the current module).
     * @param targetAlias The alias being satisfied (must match a .REQUIRE in the imported module).
     */
    public record UsingClause(Token sourceAlias, Token targetAlias) {}
}
