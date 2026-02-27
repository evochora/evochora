package org.evochora.compiler.frontend.preprocessor.features.source;

import org.evochora.compiler.frontend.io.SourceLoader;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorDirectiveHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Handles the {@code .SOURCE} directive in the preprocessor phase.
 * This directive reads another source file and injects its tokens into the current token stream.
 * Supports local filesystem paths, classpath resources, and HTTP/HTTPS URLs.
 *
 * <p>{@code .SOURCE} files must not contain {@code .IMPORT} or {@code .REQUIRE} directives.
 * Module dependencies belong in the file that uses the code, not in sourced macro/constant files.</p>
 *
 * <p>For HTTP-sourced files, relative {@code .SOURCE} paths within them are resolved relative
 * to the base URL (e.g., {@code https://example.com/lib/macros.evo} sourcing {@code ./helpers.evo}
 * resolves to {@code https://example.com/lib/helpers.evo}).</p>
 */
public class SourceDirectiveHandler implements IPreProcessorDirectiveHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int startIndex = preProcessor.getCurrentIndex();

        preProcessor.advance(); // consume .SOURCE
        Token pathToken = preProcessor.consume(TokenType.STRING, "Expected a file path in quotes after .SOURCE.");
        if (pathToken == null) return;

        int endIndex = preProcessor.getCurrentIndex();
        String pathValue = (String) pathToken.value();

        try {
            SourceLoader.LoadResult result = loadContent(pathValue, pathToken, preProcessor);
            String content = result.content();
            String logicalName = result.logicalName();

            if (preProcessor.hasAlreadyIncluded(logicalName)) {
                preProcessor.removeTokens(startIndex, endIndex - startIndex);
                return;
            }
            preProcessor.markAsIncluded(logicalName);
            preProcessor.addSourceContent(logicalName, content);

            Lexer lexer = new Lexer(content, preProcessor.getDiagnostics(), logicalName);
            List<Token> newTokens = lexer.scanTokens();

            // Remove the EOF token from the sourced file
            if (!newTokens.isEmpty() && newTokens.getLast().type() == TokenType.END_OF_FILE) {
                newTokens.removeLast();
            }

            // Validate: .SOURCE files must not contain .IMPORT or .REQUIRE directives
            for (Token t : newTokens) {
                if (t.type() == TokenType.DIRECTIVE) {
                    String text = t.text().toUpperCase();
                    if (".IMPORT".equals(text) || ".REQUIRE".equals(text)) {
                        preProcessor.getDiagnostics().reportError(
                                ".SOURCE files must not contain " + text + " directives.",
                                t.fileName(), t.line());
                        return;
                    }
                }
            }

            // Inject context management directives
            newTokens.add(0, new Token(TokenType.DIRECTIVE, ".PUSH_CTX", null, pathToken.line(), 0, pathToken.fileName()));
            newTokens.add(new Token(TokenType.DIRECTIVE, ".POP_CTX", null, pathToken.line(), 0, pathToken.fileName()));

            preProcessor.removeTokens(startIndex, endIndex - startIndex);
            preProcessor.injectTokens(newTokens, 0);

        } catch (IOException e) {
            preProcessor.getDiagnostics().reportError("Could not read sourced file: " + pathValue, pathToken.fileName(), pathToken.line());
        }
    }

    /**
     * Loads content from an HTTP URL, local filesystem, or classpath.
     * For HTTP-sourced files, the logical name is the full URL.
     * For local files, relative paths are resolved from the including file's directory.
     */
    private SourceLoader.LoadResult loadContent(String pathValue, Token pathToken, PreProcessor preProcessor) throws IOException {
        // Check if the including file itself was loaded from HTTP
        String includingFileName = pathToken.fileName();
        boolean includingIsHttp = SourceLoader.isHttpUrl(includingFileName);

        // HTTP URL (explicit or relative within an HTTP-sourced file)
        if (SourceLoader.isHttpUrl(pathValue)) {
            return SourceLoader.loadHttp(pathValue);
        }

        // Relative path inside an HTTP-sourced file resolves against the base URL
        if (includingIsHttp) {
            String resolvedUrl = SourceLoader.resolveHttpRelative(includingFileName, pathValue);
            return SourceLoader.loadHttp(resolvedUrl);
        }

        // Local filesystem resolution
        Path includingFileDir = deriveParentDir(includingFileName, preProcessor.getBasePath());
        Path resolvedPath = includingFileDir.resolve(pathValue).normalize();

        if (Files.exists(resolvedPath)) {
            return SourceLoader.loadFile(resolvedPath);
        }

        // Fallback to classpath resource resolution
        String including = includingFileName != null ? includingFileName.replace('\\', '/') : "";
        String resourceBase = including.contains("/") ? including.substring(0, including.lastIndexOf('/')) : "";

        if (resourceBase.isEmpty()) {
            resourceBase = "org/evochora/organism/prototypes";
        }

        String classpathCandidate = (resourceBase.isEmpty() ? pathValue : resourceBase + "/" + pathValue).replace('\\', '/');

        try {
            return SourceLoader.loadClasspath(classpathCandidate);
        } catch (IOException ignored) {
            // Try alternative classpath resolution using basePath
            String basePathStr = preProcessor.getBasePath().toString().replace('\\', '/');
            String alternativeCandidate = basePathStr + "/" + pathValue;
            return SourceLoader.loadClasspath(alternativeCandidate);
        }
    }

    /**
     * Derives the parent directory of the file containing the directive.
     * Falls back to the provided basePath if the fileName has no parent.
     */
    private Path deriveParentDir(String fileName, Path fallbackBasePath) {
        if (fileName != null && !fileName.isEmpty()) {
            Path filePath = Path.of(fileName);
            Path parent = filePath.getParent();
            if (parent != null) {
                return parent;
            }
        }
        return fallbackBasePath;
    }
}
