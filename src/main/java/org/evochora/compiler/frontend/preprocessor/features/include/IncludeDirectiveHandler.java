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
        Path baseDir = null;
        try {
            Path includingFile = Path.of(pathToken.fileName());
            baseDir = includingFile.getParent();
        } catch (Exception ignored) { }
        if (baseDir == null) {
            baseDir = context.getBasePath();
        }
        Path absolutePath = baseDir.resolve(relativePath).normalize();

        if (context.hasAlreadyIncluded(absolutePath.toString())) {
            return null; // Prevent duplicate inclusion
        }
        context.markAsIncluded(absolutePath.toString());

        try {
            String content;
            if (Files.exists(absolutePath)) {
                content = Files.readString(absolutePath);
            } else {
                String resourceName = absolutePath.toString().replace('\\', '/');
                try (java.io.InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName)) {
                    if (is == null) throw new IOException("Resource not found: " + resourceName);
                    try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                        content = br.lines().reduce(new StringBuilder(), (sb, s) -> sb.append(s).append('\n'), StringBuilder::append).toString();
                    }
                }
            }
            pass.addSourceContent(absolutePath.toString(), content);
            Lexer lexer = new Lexer(content, context.getDiagnostics(), absolutePath.toString());
            pass.removeTokens(startIndex, endIndex - startIndex);
            pass.injectTokens(lexer.scanTokens(), 0);
        } catch (IOException e) {
            context.getDiagnostics().reportError("Could not read included file: " + absolutePath, "Unknown", pathToken.line());
        }

        return null;
    }
}
