package org.evochora.compiler.frontend.preprocessor.features.importdir;

import org.evochora.compiler.model.Token;
import org.evochora.compiler.model.TokenType;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorDirectiveHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles the {@code .IMPORT} directive in the preprocessor phase.
 * Inlines the imported module's pre-lexed tokens at the directive location, wrapped with
 * PUSH_CTX/POP_CTX for relative .ORG support. The directive tokens remain in the
 * stream for the parser to create an {@code ImportNode}.
 *
 * <p>Module tokens are pre-lexed in Phase 1 (Lexical Analysis) and passed through as a
 * map from resolved absolute path to token list. This handler does not call the Lexer,
 * maintaining strict phase separation.</p>
 */
public class ImportSourceHandler implements IPreProcessorDirectiveHandler {

    private final Map<String, List<Token>> moduleTokens;

    /**
     * @param moduleTokens Map of absolute resolved path to pre-lexed module tokens
     *                     (EOF removed, ready for preprocessing).
     *                     Built from the DependencyGraph in Phase 1 of the Compiler.
     */
    public ImportSourceHandler(Map<String, List<Token>> moduleTokens) {
        this.moduleTokens = moduleTokens;
    }

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        Token importToken = preProcessor.peek();
        preProcessor.advance(); // consume .IMPORT

        Token pathToken = preProcessor.consume(TokenType.STRING, "Expected a file path in quotes after .IMPORT.");
        if (pathToken == null) return;

        // Skip AS ALIAS and optional USING clauses — leave them for the parser
        while (!preProcessor.isAtEnd() && !preProcessor.check(TokenType.NEWLINE)) {
            preProcessor.advance();
        }
        // Advance past the NEWLINE so module tokens are injected after the directive line
        if (!preProcessor.isAtEnd() && preProcessor.check(TokenType.NEWLINE)) {
            preProcessor.advance();
        }

        // Resolve the path to an absolute path
        String pathValue = (String) pathToken.value();
        String resolvedPath = resolvePath(pathValue, pathToken.fileName(), preProcessor.getBasePath());

        // Guard against double-inlining of the same module
        if (preProcessor.hasAlreadyIncluded(resolvedPath)) {
            return;
        }
        preProcessor.markAsIncluded(resolvedPath);

        List<Token> tokens = moduleTokens.get(resolvedPath);
        if (tokens == null) {
            preProcessor.getDiagnostics().reportError(
                    "Module tokens not found for: " + pathValue + " (resolved to: " + resolvedPath + ")",
                    pathToken.fileName(), pathToken.line());
            return;
        }

        // Create a copy of the pre-lexed tokens (each import gets its own instance)
        List<Token> newTokens = new ArrayList<>(tokens);

        // Wrap with PUSH_CTX/POP_CTX for relative .ORG support
        newTokens.add(0, new Token(TokenType.DIRECTIVE, ".PUSH_CTX", null, importToken.line(), 0, importToken.fileName()));
        newTokens.add(new Token(TokenType.DIRECTIVE, ".POP_CTX", null, importToken.line(), 0, importToken.fileName()));

        // Inject after the .IMPORT directive (tokens remain for the parser)
        preProcessor.injectTokens(newTokens, 0);
    }

    /**
     * Resolves a relative path to an absolute path, using the including file's directory.
     * Falls back to basePath if the including file has no parent directory.
     */
    private String resolvePath(String pathValue, String includingFileName, Path basePath) {
        Path includingFileDir;
        if (includingFileName != null && !includingFileName.isEmpty()) {
            Path filePath = Path.of(includingFileName);
            Path parent = filePath.getParent();
            includingFileDir = (parent != null) ? parent : basePath;
        } else {
            includingFileDir = basePath;
        }
        return includingFileDir.resolve(pathValue).normalize().toString().replace('\\', '/');
    }
}
