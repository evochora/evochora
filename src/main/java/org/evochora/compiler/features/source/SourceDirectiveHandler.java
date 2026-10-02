package org.evochora.compiler.features.source;

import org.evochora.compiler.diagnostics.ErrorRecoveryException;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.module.PlacementContext;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handles the {@code .SOURCE} directive in the preprocessor phase.
 * Reads pre-lexed tokens from the {@link PreProcessorContext} and injects them
 * into the current token stream, wrapped with context management directives.
 *
 * <p>{@code .SOURCE} is textual inclusion — no module identity, no alias,
 * no scope. The parent module context is preserved.</p>
 *
 * <p>The directive stands alone on its line: a token before it or after the path is an error.</p>
 */
public class SourceDirectiveHandler implements IPreProcessorHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int startIndex = preProcessor.getCurrentIndex();
        Token before = wordBefore(preProcessor, preProcessor.getCurrentIndex());
        if (before != null) {
            Token directive = preProcessor.peek();
            String message = ".SOURCE must be the first word on its line; found '" + before.text() + "' before it.";
            preProcessor.getDiagnostics().reportError(message, directive.fileName(), directive.line());
            throw new ErrorRecoveryException(message);
        }

        preProcessor.advance(); // consume .SOURCE
        Token pathToken = preProcessor.consume(TokenType.STRING, "Expected a file path in quotes after .SOURCE.");
        if (!preProcessor.isAtEnd() && !preProcessor.check(TokenType.NEWLINE)) {
            Token extra = preProcessor.peek();
            String message = ".SOURCE must stand alone on its line; found '" + extra.text() + "' after the path.";
            preProcessor.getDiagnostics().reportError(message, extra.fileName(), extra.line());
            throw new ErrorRecoveryException(message);
        }

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

        // The dependency scan loads every file whose path is written in a branch it takes, with
        // the flags this pass has at the same place, and the path of a dependency directive is a
        // literal that stands where the scan reads it. A file without tokens here is a defect of
        // the compiler.
        List<Token> preLexed = preProcessorContext.fileTokens().get(resolvedPath);
        if (preLexed == null) {
            preProcessor.getDiagnostics().reportError(
                    "Internal error: the dependency scan did not load " + resolvedPath + ".",
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

    /**
     * Finds a token of the directive's file and line that stands before the directive, which must
     * be the first word on its physical line. Tokens of another
     * file or line, such as the marker an inclusion puts before a file's first line, end the walk.
     *
     * @return The nearest such token that is not a statement end, or the nearest statement end if
     *         there is nothing else, or {@code null} if the directive is the first word.
     */
    private static Token wordBefore(PreProcessor preProcessor, int index) {
        Token directive = preProcessor.getToken(index);
        int i = index - 1;
        Token found = null;
        for (; i >= 0 && sameLine(preProcessor.getToken(i), directive); i--) {
            Token token = preProcessor.getToken(i);
            if (token.type() != TokenType.NEWLINE) {
                return token;
            }
            if (found == null) {
                found = token;
            }
        }
        return found;
    }

    private static boolean sameLine(Token a, Token b) {
        return a.line() == b.line() && Objects.equals(a.fileName(), b.fileName());
    }
}
