package org.evochora.compiler.frontend.preprocessor.features.include;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;

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
        PreProcessor pass = (PreProcessor) context;
        int startIndex = pass.getCurrentIndex();

        context.advance(); // .INCLUDE konsumieren
        Token pathToken = context.consume(TokenType.STRING, "Expected a file path in quotes after .INCLUDE.");
        if (pathToken == null) return null; // Error occurred

        int endIndex = pass.getCurrentIndex();

        String relativePath = (String) pathToken.value();
        Path absolutePath = context.getBasePath().resolve(relativePath).normalize();

        // TODO: Add proper handling for .INCLUDE_STRICT vs .FILE
        if (context.hasAlreadyIncluded(absolutePath.toString())) {
            return null; // Prevent duplicate inclusion
        }
        context.markAsIncluded(absolutePath.toString());

        try {
            String content = Files.readString(absolutePath);
            Lexer lexer = new Lexer(content, context.getDiagnostics(), absolutePath.toString());
            pass.removeTokens(startIndex, endIndex - startIndex);
            pass.injectTokens(lexer.scanTokens(), 0);
        } catch (IOException e) {
            context.getDiagnostics().reportError("Could not read included file: " + absolutePath, "Unknown", pathToken.line());
        }

        return null; // This handler does not produce an AST node.
    }
}
