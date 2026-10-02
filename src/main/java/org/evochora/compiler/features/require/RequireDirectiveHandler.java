package org.evochora.compiler.features.require;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;

/**
 * Parses the {@code .REQUIRE} directive.
 *
 * <p>Syntax: {@code .REQUIRE "path" AS ALIAS}
 *
 * <p>Declares an unsatisfied module dependency. The module importing this one must
 * provide the required module through a {@code USING} clause. This enables compile-time
 * dependency injection for reusable library modules.
 *
 * <p>The directive stands alone on its line: a token after the alias is an error, and the rest
 * of the line is passed over.
 */
public class RequireDirectiveHandler implements IParserStatementHandler {

    @Override
    public AstNode parse(IParsingContext context) {
        context.advance(); // consume .REQUIRE

        Token pathToken = context.consume(TokenType.STRING, "Expected a file path in quotes after .REQUIRE.");

        // Consume AS keyword
        if (!context.check(TokenType.IDENTIFIER) || !"AS".equalsIgnoreCase(context.peek().text())) {
            context.getDiagnostics().reportError(
                    "Expected AS after .REQUIRE path.",
                    pathToken.fileName(), pathToken.line());
            return null;
        }
        context.advance(); // consume AS

        Token aliasToken = context.consume(TokenType.IDENTIFIER, "Expected an alias name after AS.");

        if (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            Token extra = context.peek();
            context.getDiagnostics().reportError(
                    ".REQUIRE must stand alone on its line; found '" + extra.text() + "' after the alias.",
                    extra.fileName(), extra.line());
            while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
                context.advance();
            }
            return null;
        }

        return new RequireNode((String) pathToken.value(), aliasToken.text(), aliasToken.toSourceInfo());
    }
}
