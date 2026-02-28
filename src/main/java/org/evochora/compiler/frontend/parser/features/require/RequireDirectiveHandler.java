package org.evochora.compiler.frontend.parser.features.require;

import org.evochora.compiler.frontend.parser.IParserDirectiveHandler;
import org.evochora.compiler.model.Token;
import org.evochora.compiler.model.TokenType;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * Parses the {@code .REQUIRE} directive.
 *
 * <p>Syntax: {@code .REQUIRE "path" AS ALIAS}
 *
 * <p>Declares an unsatisfied module dependency. The module importing this one must
 * provide the required module through a {@code USING} clause. This enables compile-time
 * dependency injection for reusable library modules.
 */
public class RequireDirectiveHandler implements IParserDirectiveHandler {

    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .REQUIRE

        Token pathToken = context.consume(TokenType.STRING, "Expected a file path in quotes after .REQUIRE.");
        if (pathToken == null) return null;

        // Consume AS keyword
        if (!context.check(TokenType.IDENTIFIER) || !"AS".equalsIgnoreCase(context.peek().text())) {
            context.getDiagnostics().reportError(
                    "Expected AS after .REQUIRE path.",
                    pathToken.fileName(), pathToken.line());
            return null;
        }
        context.advance(); // consume AS

        Token aliasToken = context.consume(TokenType.IDENTIFIER, "Expected an alias name after AS.");
        if (aliasToken == null) return null;

        return new RequireNode(pathToken, aliasToken);
    }
}
