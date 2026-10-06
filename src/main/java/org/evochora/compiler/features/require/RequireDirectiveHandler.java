package org.evochora.compiler.features.require;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.diagnostics.ErrorRecoveryException;
import org.evochora.compiler.frontend.DirectiveLine;

/**
 * Parses the {@code .REQUIRE} directive.
 *
 * <p>Syntax: {@code .REQUIRE "path" AS ALIAS}
 *
 * <p>Declares an unsatisfied module dependency. The module importing this one must
 * provide the required module through a {@code USING} clause. This enables compile-time
 * dependency injection for reusable library modules.
 *
 * <p>The directive stands alone on its line: a token of its file and line before it, or a token
 * after the alias, is an error, and the rest of the line is passed over.
 *
 * <p>A module's requirements are names of its module level: the directive inside a scope, such
 * as a procedure body, is reported and produces no node.
 */
public class RequireDirectiveHandler implements IParserStatementHandler {

    @Override
    public AstNode parse(IParsingContext context) {
        Token directive = context.peek();
        // An EXPORT before the directive is the parser's to report; the line rule passes over it.
        DirectiveLine line = context.currentLine(
                token -> token.type() == TokenType.IDENTIFIER && "EXPORT".equalsIgnoreCase(token.text()));
        if (line.before() != null) {
            String message = ".REQUIRE must be the first word on its line; found '" + line.before().text() + "' before it.";
            context.getDiagnostics().reportError(message, directive.source().fileName(), directive.source().lineNumber());
            throw new ErrorRecoveryException(message);
        }
        context.advance(); // consume .REQUIRE

        Token pathToken = context.consume(TokenType.STRING, "Expected a file path in quotes after .REQUIRE.");

        // Consume AS keyword
        if (!context.check(TokenType.IDENTIFIER) || !"AS".equalsIgnoreCase(context.peek().text())) {
            context.getDiagnostics().reportError(
                    "Expected AS after .REQUIRE path.",
                    pathToken.source().fileName(), pathToken.source().lineNumber());
            return null;
        }
        context.advance(); // consume AS

        Token aliasToken = context.consume(TokenType.IDENTIFIER, "Expected an alias name after AS.");

        if (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            Token extra = context.peek();
            context.getDiagnostics().reportError(
                    ".REQUIRE must stand alone on its line; found '" + extra.text() + "' after the alias.",
                    extra.source().fileName(), extra.source().lineNumber());
            while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
                context.advance();
            }
            return null;
        }
        if (line.separator() != null) {
            context.getDiagnostics().reportError(
                    ".REQUIRE must stand alone on its line; found ';' after the alias.",
                    line.separator().source().fileName(), line.separator().source().lineNumber());
            return null;
        }

        if (!context.state().isAtModuleLevel()) {
            context.getDiagnostics().reportError(
                    ".REQUIRE may stand only at the module level.",
                    directive.source().fileName(), directive.source().lineNumber());
            return null;
        }

        return new RequireNode((String) pathToken.value(), aliasToken.text(), aliasToken.source());
    }
}
