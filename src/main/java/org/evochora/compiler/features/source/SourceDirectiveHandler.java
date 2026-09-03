package org.evochora.compiler.features.source;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.module.PlacementContext;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.ArrayList;
import java.util.List;

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
        if (preProcessorContext.isIncluding(resolvedPath)) {
            preProcessor.getDiagnostics().reportError(
                    "Circular .SOURCE detected: " + pathValue, pathToken.fileName(), pathToken.line());
            preProcessor.removeTokens(startIndex, endIndex - startIndex);
            return;
        }

        // The dependency scan loads every source file whose path is written in the source, so a
        // file without tokens here was named by a path the scan could not see: a macro parameter.
        List<Token> preLexed = preProcessorContext.fileTokens().get(resolvedPath);
        if (preLexed == null) {
            preProcessor.getDiagnostics().reportError(
                    ".SOURCE path must be a literal, not a macro parameter",
                    pathToken.fileName(), pathToken.line());
            preProcessor.removeTokens(startIndex, endIndex - startIndex);
            return;
        }

        // A .SOURCE inclusion keeps the enclosing module context, so it carries no alias chain.
        // The inclusion stays open until the injected .POP_CTX token is processed.
        PlacementContext placementCtx = new PlacementContext(resolvedPath, null);
        preProcessorContext.enterInclusion(placementCtx);

        // Copy tokens and wrap with context management directives
        List<Token> newTokens = new ArrayList<>(preLexed);
        newTokens.add(0, new Token(TokenType.DIRECTIVE, ".PUSH_CTX", placementCtx, pathToken.line(), 0, pathToken.fileName()));
        newTokens.add(new Token(TokenType.DIRECTIVE, ".POP_CTX", null, pathToken.line(), 0, pathToken.fileName()));

        preProcessor.removeTokens(startIndex, endIndex - startIndex);
        preProcessor.injectTokens(newTokens, 0);
    }
}
