package org.evochora.compiler.frontend.parser.features.require;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.SourceLocatable;

/**
 * AST node for the {@code .REQUIRE} directive.
 *
 * <p>Syntax: {@code .REQUIRE "path" AS ALIAS}
 *
 * <p>Declares an unsatisfied dependency on a module. The importer of this module
 * must provide the required module via a {@code USING} clause on the {@code .IMPORT} directive.
 *
 * @param path  The string literal token containing the required file path or URL.
 * @param alias The identifier token for the local alias name.
 */
public record RequireNode(
        Token path,
        Token alias
) implements AstNode, SourceLocatable {

    @Override
    public String getSourceFileName() {
        return alias.fileName();
    }
}
