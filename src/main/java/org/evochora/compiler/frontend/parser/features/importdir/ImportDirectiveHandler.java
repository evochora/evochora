package org.evochora.compiler.frontend.parser.features.importdir;

import org.evochora.compiler.frontend.parser.IParserDirectiveHandler;
import org.evochora.compiler.model.Token;
import org.evochora.compiler.model.TokenType;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the {@code .IMPORT} directive.
 *
 * <p>Syntax: {@code .IMPORT "path" AS ALIAS [USING source AS target]*}
 *
 * <p>This handler produces an {@link ImportNode} AST node. The actual module loading
 * is handled by Phase 0 (DependencyScanner) and preprocessing (ImportSourceHandler).
 */
public class ImportDirectiveHandler implements IParserDirectiveHandler {

    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // consume .IMPORT

        Token pathToken = context.consume(TokenType.STRING, "Expected a file path in quotes after .IMPORT.");
        if (pathToken == null) return null;

        // Consume AS keyword
        if (!context.check(TokenType.IDENTIFIER) || !"AS".equalsIgnoreCase(context.peek().text())) {
            context.getDiagnostics().reportError(
                    "Expected AS after .IMPORT path.",
                    pathToken.fileName(), pathToken.line());
            return null;
        }
        context.advance(); // consume AS

        Token aliasToken = context.consume(TokenType.IDENTIFIER, "Expected an alias name after AS.");
        if (aliasToken == null) return null;

        // Parse optional USING clauses
        List<ImportNode.UsingClause> usings = new ArrayList<>();
        while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            if (context.check(TokenType.IDENTIFIER) && "USING".equalsIgnoreCase(context.peek().text())) {
                context.advance(); // consume USING

                Token sourceAlias = context.consume(TokenType.IDENTIFIER, "Expected a source alias after USING.");
                if (sourceAlias == null) break;

                if (!context.check(TokenType.IDENTIFIER) || !"AS".equalsIgnoreCase(context.peek().text())) {
                    context.getDiagnostics().reportError(
                            "Expected AS after USING source alias.",
                            sourceAlias.fileName(), sourceAlias.line());
                    break;
                }
                context.advance(); // consume AS

                Token targetAlias = context.consume(TokenType.IDENTIFIER, "Expected a target alias after AS.");
                if (targetAlias == null) break;

                usings.add(new ImportNode.UsingClause(sourceAlias, targetAlias));
            } else {
                context.getDiagnostics().reportError(
                        "Unexpected token '" + context.peek().text() + "' in .IMPORT directive.",
                        context.peek().fileName(), context.peek().line());
                break;
            }
        }

        return new ImportNode(pathToken, aliasToken, usings);
    }
}
