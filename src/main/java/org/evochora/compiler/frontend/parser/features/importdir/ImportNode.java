package org.evochora.compiler.frontend.parser.features.importdir;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.SourceLocatable;

import java.util.List;

/**
 * AST node for the {@code .IMPORT} directive.
 *
 * <p>Syntax: {@code .IMPORT "path" AS ALIAS [USING source AS target]*}
 *
 * @param path   The string literal token containing the file path or URL.
 * @param alias  The identifier token for the local alias name.
 * @param usings The USING clauses providing dependencies to the imported module.
 */
public record ImportNode(
        Token path,
        Token alias,
        List<UsingClause> usings
) implements AstNode, SourceLocatable {

    @Override
    public String getSourceFileName() {
        return alias.fileName();
    }

    /**
     * A USING clause on an import, providing a module to satisfy a dependency.
     *
     * @param sourceAlias The alias being provided (must be a known import in the current module).
     * @param targetAlias The alias being satisfied (must match a .REQUIRE in the imported module).
     */
    public record UsingClause(Token sourceAlias, Token targetAlias) {}
}
