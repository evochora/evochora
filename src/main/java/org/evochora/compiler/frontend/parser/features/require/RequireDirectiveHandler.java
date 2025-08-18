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
        Token path = context.consume(TokenType.STRING, "Expected a file path in quotes after .REQUIRE.");
        // Resolve relative path against the including file's directory if available
        if (path != null && path.value() instanceof String s) {
            String fileName = path.fileName();
            try {
                Path including = Path.of(fileName);
                Path baseDir = including.getParent();
                if (baseDir != null) {
                    String resolved = baseDir.resolve(s).normalize().toString();
                    path = new Token(TokenType.STRING, path.text(), resolved, path.line(), path.column(), path.fileName());
                }
            } catch (Exception ignored) { /* keep original */ }
        }
        Token alias = null;
        if (context.match(TokenType.IDENTIFIER) && context.previous().text().equalsIgnoreCase("AS")) {
            alias = context.consume(TokenType.IDENTIFIER, "Expected an alias name after 'AS'.");
        }
        return new RequireNode(path, alias);
    }
}
