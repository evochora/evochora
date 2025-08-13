package org.evochora.compiler.preprocessor;

import org.evochora.compiler.core.*;
import org.evochora.compiler.core.directives.IDirectiveHandler;
import org.evochora.compiler.core.ast.AstNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class IncludeDirectiveHandler implements IDirectiveHandler {
    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PREPROCESSING;
    }

    @Override
    public AstNode parse(ParsingContext context) {
        context.advance(); // .INCLUDE konsumieren
        Token pathToken = context.consume(TokenType.STRING, "Expected a file path in quotes after .INCLUDE.");
        if (pathToken == null) return null; // Error occurred

        String relativePath = (String) pathToken.value();
        Path absolutePath = context.getBasePath().resolve(relativePath).normalize();

        // TODO: Add proper handling for .INCLUDE_STRICT vs .FILE
        if (context.hasAlreadyIncluded(absolutePath.toString())) {
            return null; // Prevent duplicate inclusion
        }
        context.markAsIncluded(absolutePath.toString());

        try {
            String content = Files.readString(absolutePath);
            Lexer lexer = new Lexer(content, context.getDiagnostics());
            // Wir müssen 2 Tokens entfernen: .INCLUDE und den Pfad-String.
            context.injectTokens(lexer.scanTokens(), 2);
        } catch (IOException e) {
            context.getDiagnostics().reportError("Could not read included file: " + absolutePath, "Unknown", pathToken.line());
        }

        return null; // This handler does not produce an AST node.
    }
}
