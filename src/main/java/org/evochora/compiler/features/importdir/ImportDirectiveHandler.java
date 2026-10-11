package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.parser.IParsingContext;
import org.evochora.compiler.model.ast.AstNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the {@code .IMPORT} directive.
 *
 * <p>Syntax: {@code .IMPORT "path" AS ALIAS [USING source AS target]*}
 *
 * <p>This handler produces an {@link ImportNode} AST node. The actual module loading
 * is handled by Phase 0 (DependencyScanner) and preprocessing (ImportSourceHandler).
 *
 * <p>The directive stands alone on its line: a token after the alias that begins no
 * {@code USING} clause is an error, and the rest of the line is passed over.
 */
public class ImportDirectiveHandler implements IParserStatementHandler {

    @Override
    public boolean supportsExport() { return true; }

    @Override
    public AstNode parse(IParsingContext context) {
        // The dependency scan reads path and alias only, and loads the module from them; the
        // clauses after the alias are read here alone.
        boolean exported = context.isExported();
        context.advance(); // consume .IMPORT

        Token pathToken = context.consume(TokenType.STRING, "Expected a file path in quotes after .IMPORT.");

        // Consume AS keyword
        if (!context.check(TokenType.IDENTIFIER) || !"AS".equalsIgnoreCase(context.peek().text())) {
            context.getDiagnostics().reportError(
                    "Expected AS after .IMPORT path.",
                    pathToken.source().fileName(), pathToken.source().lineNumber());
            return null;
        }
        context.advance(); // consume AS

        Token aliasToken = context.consume(TokenType.IDENTIFIER, "Expected an alias name after AS.");

        // Parse optional USING clauses
        List<ImportNode.UsingClause> usings = new ArrayList<>();
        while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
            if (context.check(TokenType.IDENTIFIER) && "USING".equalsIgnoreCase(context.peek().text())) {
                context.advance(); // consume USING

                Token sourceAlias = context.consume(TokenType.IDENTIFIER, "Expected a source alias after USING.");

                if (!context.check(TokenType.IDENTIFIER) || !"AS".equalsIgnoreCase(context.peek().text())) {
                    context.getDiagnostics().reportError(
                            "Expected AS after USING source alias.",
                            sourceAlias.source().fileName(), sourceAlias.source().lineNumber());
                    break;
                }
                context.advance(); // consume AS

                Token targetAlias = context.consume(TokenType.IDENTIFIER, "Expected a target alias after AS.");

                boolean bound = usings.stream().anyMatch(u -> u.targetAlias().equalsIgnoreCase(targetAlias.text()));
                if (bound) {
                    context.getDiagnostics().reportError(
                            "Cannot bind '" + targetAlias.text() + "' twice in the USING clauses of one import.",
                            targetAlias.source().fileName(), targetAlias.source().lineNumber());
                    continue;
                }
                usings.add(new ImportNode.UsingClause(
                        sourceAlias.text(), targetAlias.text(),
                        sourceAlias.source(), targetAlias.source()));
            } else {
                Token extra = context.peek();
                context.getDiagnostics().reportError(
                        ".IMPORT must stand alone on its line; found '" + extra.text()
                                + "' after the alias and its USING clauses.",
                        extra.source().fileName(), extra.source().lineNumber());
                while (!context.isAtEnd() && !context.check(TokenType.NEWLINE)) {
                    context.advance();
                }
                return null;
            }
        }

        return new ImportNode(
                (String) pathToken.value(), aliasToken.text(), usings, exported,
                aliasToken.source());
    }
}
