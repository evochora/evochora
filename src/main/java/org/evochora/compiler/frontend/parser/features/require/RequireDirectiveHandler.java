package org.evochora.compiler.frontend.parser.features.require;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.ParsingContext;
import java.nio.file.Path;

/**
 * Handles the parsing of the <code>.require</code> directive.
 * This directive is used to include another source file.
 */
public class RequireDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }

    /**
     * Parses a <code>.require</code> directive, which specifies a file to be included.
     * The syntax is <code>.require "path/to/file.asm" [AS alias]</code>.
     * The path is resolved relative to the file containing the directive.
     * @param context The parsing context.
     * @return A {@link RequireNode} representing the directive.
     */
    @Override public AstNode parse(ParsingContext context) {
        context.advance(); // consume .REQUIRE
        Token pathToken = context.consume(TokenType.STRING, "Expected a file path in quotes after .REQUIRE.");

        if (pathToken != null && pathToken.value() instanceof String relativePath) {
            try {
                // CORRECTION: Resolve path relative to the file containing the .REQUIRE
                Path includingFile = Path.of(pathToken.fileName());
                Path basePath = includingFile.getParent();
                if (basePath != null) {
                    String resolvedPath = basePath.resolve(relativePath).normalize().toString().replace('\\', '/');
                    pathToken = new Token(TokenType.STRING, pathToken.text(), resolvedPath, pathToken.line(), pathToken.column(), pathToken.fileName());
                }
            } catch (Exception ignored) { /* Keep original path on error */ }
        }

        Token alias = null;
        if (context.match(TokenType.IDENTIFIER) && context.previous().text().equalsIgnoreCase("AS")) {
            alias = context.consume(TokenType.IDENTIFIER, "Expected an alias name after 'AS'.");
        }
        return new RequireNode(pathToken, alias);
    }
}