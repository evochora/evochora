package org.evochora.compiler.frontend.preprocessor.features.include;

import org.evochora.compiler.frontend.directive.IDirectiveHandler;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

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
        if (pathToken == null) return null;

        int endIndex = pass.getCurrentIndex();
        String relativePath = (String) pathToken.value();

        try {
            Path resolvedPath = context.getBasePath().resolve(relativePath).normalize();
            String content;
            String logicalName;

            if (Files.exists(resolvedPath)) {
                // Filesystem path for tests/tools
                logicalName = resolvedPath.toString().replace('\\', '/');
                content = Files.readString(resolvedPath);
            } else {
                // Classpath resource relative to including file
                String including = pathToken.fileName() != null ? pathToken.fileName().replace('\\', '/') : "";
                String resourceBase = including.contains("/") ? including.substring(0, including.lastIndexOf('/')) : "";
                String classpathCandidate = (resourceBase.isEmpty() ? relativePath : resourceBase + "/" + relativePath).replace('\\', '/');

                try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathCandidate)) {
                    if (is == null) throw new IOException("Resource not found in classpath: " + classpathCandidate);
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                        content = br.lines().collect(Collectors.joining("\n"));
                    }
                }
                logicalName = classpathCandidate;
            }

            if (context.hasAlreadyIncluded(logicalName)) {
                pass.removeTokens(startIndex, endIndex - startIndex);
                return null;
            }
            context.markAsIncluded(logicalName);
            pass.addSourceContent(logicalName, content);

            Lexer lexer = new Lexer(content, context.getDiagnostics(), logicalName);
            pass.removeTokens(startIndex, endIndex - startIndex);
            pass.injectTokens(lexer.scanTokens(), 0);

        } catch (IOException e) {
            context.getDiagnostics().reportError("Could not read included file: " + relativePath, pathToken.fileName(), pathToken.line());
        }

        return null;
    }
}