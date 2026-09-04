package org.evochora.compiler.features.importdir;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.module.PlacementContext;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the {@code .IMPORT} directive in the preprocessor phase.
 * Inlines the imported module's pre-lexed tokens at the directive location, wrapped with
 * PUSH_CTX/POP_CTX for relative .ORG support. The directive tokens remain in the
 * stream for the parser to create an {@code ImportNode}.
 *
 * <p>The module's tokens are pre-lexed in Phase 1 (Lexical Analysis) and made available via
 * {@link PreProcessorContext#fileTokens()}. This handler does not call the Lexer,
 * maintaining strict phase separation.</p>
 */
public class ImportSourceHandler implements IPreProcessorHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        Token importToken = preProcessor.peek();
        preProcessor.advance(); // consume .IMPORT

        Token pathToken = preProcessor.consume(TokenType.STRING, "Expected a file path in quotes after .IMPORT.");

        // Resolve the path to an absolute path
        String pathValue = (String) pathToken.value();
        String resolvedPath;
        try {
            resolvedPath = preProcessor.getResolver().resolve(pathValue, pathToken.fileName());
        } catch (org.evochora.compiler.util.SourceRootResolver.UnknownPrefixException e) {
            preProcessor.getDiagnostics().reportError(e.getMessage(), pathToken.fileName(), pathToken.line());
            return;
        }

        // The dependency scan loads every module file whose path is written in the source, so a
        // file without tokens here was named by a path the scan could not see: a macro parameter.
        List<Token> tokens = preProcessorContext.fileTokens().get(resolvedPath);
        if (tokens == null) {
            preProcessor.getDiagnostics().reportError(
                    ".IMPORT path must be a literal, not a macro parameter",
                    pathToken.fileName(), pathToken.line());
            return;
        }

        // The alias is read here because the module's tokens are placed under it; the parser
        // reads the directive again and reports every other malformation.
        String alias = extractAlias(preProcessor);
        if (alias == null) {
            preProcessor.getDiagnostics().reportError(
                    "Expected AS after .IMPORT path.", pathToken.fileName(), pathToken.line());
            return;
        }

        // Skip remaining tokens (USING clauses) — leave them for the parser
        while (!preProcessor.isAtEnd() && !preProcessor.check(TokenType.NEWLINE)) {
            preProcessor.advance();
        }
        // Advance past the NEWLINE so module tokens are injected after the directive line
        if (!preProcessor.isAtEnd() && preProcessor.check(TokenType.NEWLINE)) {
            preProcessor.advance();
        }

        // Guard against circular imports
        if (preProcessorContext.isIncluding(resolvedPath)) {
            preProcessor.getDiagnostics().reportError(
                    "Circular .IMPORT detected: " + pathValue, pathToken.fileName(), pathToken.line());
            return;
        }

        // Compute alias chain: parent chain + alias
        String parentChain = preProcessorContext.currentAliasChain();
        String aliasUpper = alias.toUpperCase();
        String aliasChain = (parentChain == null || parentChain.isEmpty())
                ? aliasUpper
                : parentChain + "." + aliasUpper;

        // Create a copy of the pre-lexed tokens (each import gets its own instance)
        List<Token> newTokens = new ArrayList<>(tokens);

        // Wrap with PUSH_CTX/POP_CTX — PUSH_CTX carries PlacementContext with alias chain
        PlacementContext placementCtx = new PlacementContext(resolvedPath, aliasChain);
        newTokens.add(0, new Token(TokenType.DIRECTIVE, ".PUSH_CTX", placementCtx, importToken.line(), 0, importToken.fileName()));
        newTokens.add(new Token(TokenType.DIRECTIVE, ".POP_CTX", null, importToken.line(), 0, importToken.fileName()));

        // The inclusion enters the module's alias chain and stays open until the injected
        // .POP_CTX token is processed by the preprocessor — not in this handler.
        preProcessorContext.enterInclusion(placementCtx);

        // Inject after the .IMPORT directive (tokens remain for the parser)
        preProcessor.injectTokens(newTokens, 0);
    }

    /**
     * Extracts the import alias from the "AS ALIAS" tokens without consuming past them.
     * The tokens are consumed but left conceptually for the parser (which re-parses the directive).
     */
    private String extractAlias(PreProcessor preProcessor) {
        if (!preProcessor.isAtEnd() && preProcessor.check(TokenType.IDENTIFIER)
                && "AS".equalsIgnoreCase(preProcessor.peek().text())) {
            preProcessor.advance(); // consume AS
            if (!preProcessor.isAtEnd() && preProcessor.check(TokenType.IDENTIFIER)) {
                Token aliasToken = preProcessor.peek();
                preProcessor.advance(); // consume alias
                return aliasToken.text();
            }
        }
        return null;
    }

}
