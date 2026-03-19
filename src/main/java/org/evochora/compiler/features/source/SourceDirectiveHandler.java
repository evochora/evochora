package org.evochora.compiler.features.source;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.module.PlacementContext;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles the {@code .SOURCE} directive in the preprocessor phase.
 * Reads pre-lexed tokens from the {@link PreProcessorContext} and injects them
 * into the current token stream, wrapped with context management directives.
 *
 * <p>{@code .SOURCE} is textual inclusion — no module identity, no alias,
 * no scope. The parent module context is preserved.</p>
 */
public class SourceDirectiveHandler implements IPreProcessorHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int startIndex = preProcessor.getCurrentIndex();

        preProcessor.advance(); // consume .SOURCE
        Token pathToken = preProcessor.consume(TokenType.STRING, "Expected a file path in quotes after .SOURCE.");
        if (pathToken == null) return;

        int endIndex = preProcessor.getCurrentIndex();
        String pathValue = (String) pathToken.value();

        // Resolve path
        String resolvedPath;
        try {
            resolvedPath = preProcessor.getResolver().resolve(pathValue, pathToken.fileName());
        } catch (org.evochora.compiler.util.SourceRootResolver.UnknownPrefixException e) {
            preProcessor.getDiagnostics().reportError(e.getMessage(), pathToken.fileName(), pathToken.line());
            preProcessor.removeTokens(startIndex, endIndex - startIndex);
            return;
        }

        // Check for circular .SOURCE
        if (preProcessor.isInSourceChain(resolvedPath)) {
            preProcessor.getDiagnostics().reportError(
                    "Circular .SOURCE detected: " + pathValue, pathToken.fileName(), pathToken.line());
            preProcessor.removeTokens(startIndex, endIndex - startIndex);
            return;
        }

        // Get pre-lexed tokens
        Map<String, List<Token>> sourceTokens = preProcessorContext.sourceTokens();
        List<Token> preLexed = sourceTokens.get(resolvedPath);
        if (preLexed == null) {
            preProcessor.getDiagnostics().reportError(
                    "Source file not found in pre-lexed sources: " + pathValue, pathToken.fileName(), pathToken.line());
            preProcessor.removeTokens(startIndex, endIndex - startIndex);
            return;
        }

        preProcessor.pushSourceChain(resolvedPath);

        // Copy tokens and wrap with context management directives
        List<Token> newTokens = new ArrayList<>(preLexed);
        PlacementContext placementCtx = new PlacementContext(resolvedPath, null);
        newTokens.add(0, new Token(TokenType.DIRECTIVE, ".PUSH_CTX", placementCtx, pathToken.line(), 0, pathToken.fileName()));
        newTokens.add(new Token(TokenType.DIRECTIVE, ".POP_CTX", "SOURCE", pathToken.line(), 0, pathToken.fileName()));

        preProcessor.removeTokens(startIndex, endIndex - startIndex);
        preProcessor.injectTokens(newTokens, 0);
    }
}
