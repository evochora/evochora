package org.evochora.compiler.features.importdir;

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
 * Handles the {@code .IMPORT} directive in the preprocessor phase.
 * Inlines the imported module's pre-lexed tokens at the directive location, wrapped with
 * PUSH_CTX/POP_CTX for relative .ORG support. The directive tokens remain in the
 * stream for the parser to create an {@code ImportNode}.
 *
 * <p>Module tokens are pre-lexed in Phase 1 (Lexical Analysis) and made available via
 * {@link PreProcessorContext#moduleTokens()}. This handler does not call the Lexer,
 * maintaining strict phase separation.</p>
 */
public class ImportSourceHandler implements IPreProcessorHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        Map<String, List<Token>> moduleTokens = preProcessorContext.moduleTokens();
        if (moduleTokens.isEmpty()) return;

        Token importToken = preProcessor.peek();
        preProcessor.advance(); // consume .IMPORT

        Token pathToken = preProcessor.consume(TokenType.STRING, "Expected a file path in quotes after .IMPORT.");
        if (pathToken == null) return;

        // Extract alias from "AS ALIAS" before skipping the rest of the directive
        String alias = extractAlias(preProcessor);

        // Skip remaining tokens (USING clauses) — leave them for the parser
        while (!preProcessor.isAtEnd() && !preProcessor.check(TokenType.NEWLINE)) {
            preProcessor.advance();
        }
        // Advance past the NEWLINE so module tokens are injected after the directive line
        if (!preProcessor.isAtEnd() && preProcessor.check(TokenType.NEWLINE)) {
            preProcessor.advance();
        }

        // Resolve the path to an absolute path
        String pathValue = (String) pathToken.value();
        String resolvedPath;
        try {
            resolvedPath = preProcessor.getResolver().resolve(pathValue, pathToken.fileName());
        } catch (org.evochora.compiler.util.SourceRootResolver.UnknownPrefixException e) {
            preProcessor.getDiagnostics().reportError(e.getMessage(), pathToken.fileName(), pathToken.line());
            return;
        }

        // Guard against circular imports
        if (preProcessor.isInImportChain(resolvedPath)) {
            preProcessor.getDiagnostics().reportError(
                    "Circular .IMPORT detected: " + pathValue, pathToken.fileName(), pathToken.line());
            return;
        }
        preProcessor.pushImportChain(resolvedPath);

        // Compute alias chain: parent chain + alias
        String parentChain = preProcessorContext.currentAliasChain();
        if (alias == null) {
            throw new IllegalStateException(
                    "Import alias is null — parser must enforce AS clause");
        }
        String aliasUpper = alias.toUpperCase();
        String aliasChain = (parentChain == null || parentChain.isEmpty())
                ? aliasUpper
                : parentChain + "." + aliasUpper;

        List<Token> tokens = moduleTokens.get(resolvedPath);
        if (tokens == null) {
            preProcessor.getDiagnostics().reportError(
                    "Module tokens not found for: " + pathValue + " (resolved to: " + resolvedPath + ")",
                    pathToken.fileName(), pathToken.line());
            return;
        }

        // Create a copy of the pre-lexed tokens (each import gets its own instance)
        List<Token> newTokens = new ArrayList<>(tokens);

        // Wrap with PUSH_CTX/POP_CTX — PUSH_CTX carries PlacementContext with alias chain
        PlacementContext placementCtx = new PlacementContext(resolvedPath, aliasChain);
        newTokens.add(0, new Token(TokenType.DIRECTIVE, ".PUSH_CTX", placementCtx, importToken.line(), 0, importToken.fileName()));
        newTokens.add(new Token(TokenType.DIRECTIVE, ".POP_CTX", "IMPORT", importToken.line(), 0, importToken.fileName()));

        // Push alias chain before injecting tokens. The corresponding pop happens when the
        // injected .POP_CTX token is processed by the preprocessor — not in this handler.
        preProcessorContext.pushAliasChain(aliasChain);

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
