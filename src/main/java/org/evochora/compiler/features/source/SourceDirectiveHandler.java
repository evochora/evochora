package org.evochora.compiler.features.source;

import org.evochora.compiler.diagnostics.ErrorRecoveryException;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.module.PlacementContext;
import org.evochora.compiler.frontend.DirectiveLine;
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
 *
 * <p>The directive stands alone on its line: a token before it or after the path is an error.</p>
 */
public class SourceDirectiveHandler implements IPreProcessorHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        int startIndex = preProcessor.getCurrentIndex();
        DirectiveLine line = preProcessor.lineOf(startIndex);
        if (line.before() != null) {
            Token directive = preProcessor.peek();
            String message = ".SOURCE must be the first word on its line; found '" + line.before().text() + "' before it.";
            preProcessor.getDiagnostics().reportError(message, directive.source().fileName(), directive.source().lineNumber());
            throw new ErrorRecoveryException(message);
        }

        preProcessor.advance(); // consume .SOURCE
        Token pathToken = preProcessor.consume(TokenType.STRING, "Expected a file path in quotes after .SOURCE.");
        if (!preProcessor.isAtEnd() && !preProcessor.check(TokenType.NEWLINE)) {
            Token extra = preProcessor.peek();
            String message = ".SOURCE must stand alone on its line; found '" + extra.text() + "' after the path.";
            preProcessor.getDiagnostics().reportError(message, extra.source().fileName(), extra.source().lineNumber());
            throw new ErrorRecoveryException(message);
        }
        if (line.separator() != null) {
            String message = ".SOURCE must stand alone on its line; found ';' after the path.";
            preProcessor.getDiagnostics().reportError(message, line.separator().source().fileName(),
                    line.separator().source().lineNumber());
            throw new ErrorRecoveryException(message);
        }

        int endIndex = preProcessor.getCurrentIndex();
        String pathValue = (String) pathToken.value();

        // Resolve path
        String resolvedPath;
        try {
            resolvedPath = preProcessor.getResolver().resolve(pathValue, pathToken.source().fileName());
        } catch (org.evochora.compiler.util.SourceRootResolver.UnknownPrefixException e) {
            preProcessor.getDiagnostics().reportError(e.getMessage(), pathToken.source().fileName(), pathToken.source().lineNumber());
            preProcessor.removeTokens(startIndex, endIndex - startIndex);
            return;
        }

        // Check for circular .SOURCE
        if (preProcessorContext.isIncluding(resolvedPath)) {
            preProcessor.getDiagnostics().reportError(
                    "Circular .SOURCE detected: " + pathValue, pathToken.source().fileName(), pathToken.source().lineNumber());
            preProcessor.removeTokens(startIndex, endIndex - startIndex);
            return;
        }

        // The dependency scan loads the file of every dependency directive this pass reaches,
        // reading the same text under the same feature state, and the path of a dependency
        // directive is a literal that stands where the scan reads it. A file without tokens here
        // is a defect of the compiler.
        List<Token> preLexed = preProcessorContext.fileTokens().get(resolvedPath);
        if (preLexed == null) {
            preProcessor.getDiagnostics().reportError(
                    "Internal error: the dependency scan did not load " + resolvedPath + ".",
                    pathToken.source().fileName(), pathToken.source().lineNumber());
            preProcessor.removeTokens(startIndex, endIndex - startIndex);
            return;
        }

        // The included text belongs to the placement that includes it
        String placement = preProcessorContext.currentAliasChain();

        // A .SOURCE inclusion keeps the enclosing module context, so it carries no alias chain.
        // The inclusion stays open until the injected .POP_CTX token is processed.
        PlacementContext placementCtx = new PlacementContext(resolvedPath, null);
        preProcessorContext.enterInclusion(placementCtx);

        // Copy tokens and wrap with context management directives
        List<Token> newTokens = new ArrayList<>(preLexed.size() + 2);
        for (Token token : preLexed) {
            SourceInfo at = token.source();
            newTokens.add(token.with(new SourceInfo(at.fileName(), at.lineNumber(), at.columnNumber(), placement,
                    at.expansion())));
        }
        SourceInfo directive = pathToken.source();
        SourceInfo marker = new SourceInfo(directive.fileName(), directive.lineNumber(), 0, directive.placement(),
                directive.expansion());
        newTokens.add(0, new Token(TokenType.DIRECTIVE, ".PUSH_CTX", placementCtx, marker));
        newTokens.add(new Token(TokenType.DIRECTIVE, ".POP_CTX", null, marker));

        preProcessor.removeTokens(startIndex, endIndex - startIndex);
        preProcessor.injectTokens(newTokens, 0);
    }
}
