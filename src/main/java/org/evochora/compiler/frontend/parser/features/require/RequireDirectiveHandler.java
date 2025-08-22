package org.evochora.compiler.frontend.parser.features.require;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.ParsingContext;
import java.nio.file.Path;

public class RequireDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }

    @Override public AstNode parse(ParsingContext context) {
        context.advance(); // .REQUIRE konsumieren
        Token pathToken = context.consume(TokenType.STRING, "Expected a file path in quotes after .REQUIRE.");

        if (pathToken != null && pathToken.value() instanceof String relativePath) {
            try {
                // KORREKTUR: Pfad relativ zur Datei auflösen, die das .REQUIRE enthält
                Path includingFile = Path.of(pathToken.fileName());
                Path basePath = includingFile.getParent();
                if (basePath != null) {
                    String resolvedPath = basePath.resolve(relativePath).normalize().toString().replace('\\', '/');
                    pathToken = new Token(TokenType.STRING, pathToken.text(), resolvedPath, pathToken.line(), pathToken.column(), pathToken.fileName());
                }
            } catch (Exception ignored) { /* Bei Fehler den Originalpfad beibehalten */ }
        }

        Token alias = null;
        if (context.match(TokenType.IDENTIFIER) && context.previous().text().equalsIgnoreCase("AS")) {
            alias = context.consume(TokenType.IDENTIFIER, "Expected an alias name after 'AS'.");
        }
        return new RequireNode(pathToken, alias);
    }
}