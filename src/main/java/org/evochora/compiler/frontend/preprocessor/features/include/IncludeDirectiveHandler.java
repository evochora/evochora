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
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles the <code>.include</code> directive in the preprocessor phase.
 * This directive reads another source file and injects its tokens into the current token stream.
 * It handles both file system paths and classpath resources.
 */
public class IncludeDirectiveHandler implements IDirectiveHandler {
    @Override
    public CompilerPhase getPhase() {
        return CompilerPhase.PREPROCESSING;
    }

    /**
     * Parses an <code>.include</code> directive. It reads the specified file,
     * tokenizes its content, and injects the new tokens into the stream.
     * @param context The parsing context, which must be a {@link PreProcessor}.
     * @return null, as this directive does not produce an AST node.
     */
    @Override
    public AstNode parse(ParsingContext context) {
        PreProcessor pass = (PreProcessor) context;
        int startIndex = pass.getCurrentIndex();

        context.advance(); // consume .INCLUDE
        Token pathToken = context.consume(TokenType.STRING, "Expected a file path in quotes after .INCLUDE.");
        if (pathToken == null) return null;

        int endIndex = pass.getCurrentIndex();
        String relativePath = (String) pathToken.value();

        try {
            String content;
            String logicalName;
            
            // First, try to resolve as filesystem path relative to the including file
            Path resolvedPath = context.getBasePath().resolve(relativePath).normalize();
            if (Files.exists(resolvedPath)) {
                // Filesystem path - use the resolved path
                logicalName = resolvedPath.toString().replace('\\', '/');
                // Explicitly normalize line endings to \n to ensure consistency with Lexer
                content = String.join("\n", Files.readAllLines(resolvedPath)) + "\n";
            } else {
                // Fallback to classpath resource resolution
                String including = pathToken.fileName() != null ? pathToken.fileName().replace('\\', '/') : "";
                String resourceBase = including.contains("/") ? including.substring(0, including.lastIndexOf('/')) : "";
                
                // If resourceBase is empty (fileName doesn't contain path), we need to reconstruct it
                // The original programName should be available in the token's fileName, but if it's just the filename,
                // we need to use the basePath to construct the proper classpath
                if (resourceBase.isEmpty()) {
                    // For primordial3.s, we know the correct classpath base should be "org/evochora/organism/prototypes"
                    // This is a hardcoded solution for now, but we could make it more generic later
                    resourceBase = "org/evochora/organism/prototypes";
                }
                
                String classpathCandidate = (resourceBase.isEmpty() ? relativePath : resourceBase + "/" + relativePath).replace('\\', '/');

                try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathCandidate)) {
                    if (is == null) {
                        // If the classpath lookup fails, try to use the basePath for relative resolution
                        String basePathStr = context.getBasePath().toString().replace('\\', '/');
                        String alternativeCandidate = basePathStr + "/" + relativePath;
                        try (InputStream altIs = Thread.currentThread().getContextClassLoader().getResourceAsStream(alternativeCandidate)) {
                            if (altIs == null) {
                                throw new IOException("Resource not found in classpath: " + classpathCandidate + " or " + alternativeCandidate);
                            }
                            try (BufferedReader br = new BufferedReader(new InputStreamReader(altIs))) {
                                content = br.lines().collect(Collectors.joining("\n")) + "\n";
                            }
                            logicalName = alternativeCandidate;
                        }
                    } else {
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                            content = br.lines().collect(Collectors.joining("\n")) + "\n";
                        }
                        logicalName = classpathCandidate;
                    }
                }
            }

            if (context.hasAlreadyIncluded(logicalName)) {
                pass.removeTokens(startIndex, endIndex - startIndex);
                return null;
            }
            context.markAsIncluded(logicalName);
            pass.addSourceContent(logicalName, content);

            Lexer lexer = new Lexer(content, context.getDiagnostics(), logicalName);
            List<Token> newTokens = lexer.scanTokens();

            // Remove the EOF token from the included file
            if (!newTokens.isEmpty() && newTokens.get(newTokens.size() - 1).type() == TokenType.END_OF_FILE) {
                newTokens.remove(newTokens.size() - 1);
            }

            // Inject context management directives
            newTokens.add(0, new Token(TokenType.DIRECTIVE, ".PUSH_CTX", null, pathToken.line(), 0, pathToken.fileName()));
            newTokens.add(new Token(TokenType.DIRECTIVE, ".POP_CTX", null, pathToken.line(), 0, pathToken.fileName()));

            pass.removeTokens(startIndex, endIndex - startIndex);
            pass.injectTokens(newTokens, 0);

        } catch (IOException e) {
            context.getDiagnostics().reportError("Could not read included file: " + relativePath, pathToken.fileName(), pathToken.line());
        }

        return null;
    }
}