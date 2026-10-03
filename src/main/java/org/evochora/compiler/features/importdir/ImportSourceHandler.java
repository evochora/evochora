package org.evochora.compiler.features.importdir;

import org.evochora.compiler.diagnostics.ErrorRecoveryException;
import org.evochora.compiler.api.SourceFile;
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
 * Handles the {@code .IMPORT} directive in the preprocessor phase.
 * Inlines the imported module's pre-lexed tokens at the directive location, wrapped with
 * PUSH_CTX/POP_CTX for relative .ORG support. The directive tokens remain in the
 * stream for the parser to create an {@code ImportNode}. The directive stands alone on its line:
 * nothing but an {@code EXPORT} before it, and a token after the alias that begins no
 * {@code USING} clause is an error.
 *
 * <p>Every import is a placement and an entry of the preprocessor's sources, under the
 * placement's alias chain, instance 0 and the position of the directive.</p>
 *
 * <p>The module's tokens are pre-lexed in Phase 1 (Lexical Analysis) and made available via
 * {@link PreProcessorContext#fileTokens()}. This handler does not call the Lexer,
 * maintaining strict phase separation.</p>
 */
public class ImportSourceHandler implements IPreProcessorHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        // An EXPORT before the directive belongs to it; the parser reads it with the directive.
        DirectiveLine line = preProcessor.lineOf(preProcessor.getCurrentIndex(), token -> isWord(token, "EXPORT"));
        if (line.before() != null) {
            Token directive = preProcessor.peek();
            String message = ".IMPORT must be the first word on its line; found '" + line.before().text() + "' before it.";
            preProcessor.getDiagnostics().reportError(message, directive.source().fileName(), directive.source().lineNumber());
            throw new ErrorRecoveryException(message);
        }
        Token importToken = preProcessor.peek();
        preProcessor.advance(); // consume .IMPORT

        Token pathToken = preProcessor.consume(TokenType.STRING, "Expected a file path in quotes after .IMPORT.");

        // The alias is read here because the module's tokens are placed under it; the parser
        // reads the directive again and reports every other malformation.
        String alias = extractAlias(preProcessor);
        if (alias == null) {
            preProcessor.getDiagnostics().reportError(
                    "Expected AS after .IMPORT path.", pathToken.source().fileName(), pathToken.source().lineNumber());
            return;
        }
        checkRestOfLine(preProcessor);
        if (line.separator() != null) {
            String message = ".IMPORT must stand alone on its line; found ';' after the alias and its USING clauses.";
            preProcessor.getDiagnostics().reportError(message, line.separator().source().fileName(),
                    line.separator().source().lineNumber());
            throw new ErrorRecoveryException(message);
        }

        // Resolve the path to an absolute path
        String pathValue = (String) pathToken.value();
        String resolvedPath;
        try {
            resolvedPath = preProcessor.getResolver().resolve(pathValue, pathToken.source().fileName());
        } catch (org.evochora.compiler.util.SourceRootResolver.UnknownPrefixException e) {
            preProcessor.getDiagnostics().reportError(e.getMessage(), pathToken.source().fileName(), pathToken.source().lineNumber());
            return;
        }

        // The dependency scan loads the file of every dependency directive this pass reaches,
        // reading the same text under the same feature state, and the path of a dependency
        // directive is a literal that stands where the scan reads it. A file without tokens here
        // is a defect of the compiler.
        List<Token> tokens = preProcessorContext.fileTokens().get(resolvedPath);
        if (tokens == null) {
            preProcessor.getDiagnostics().reportError(
                    "Internal error: the dependency scan did not load " + resolvedPath + ".",
                    pathToken.source().fileName(), pathToken.source().lineNumber());
            return;
        }

        // Advance past the NEWLINE so module tokens are injected after the directive line
        if (!preProcessor.isAtEnd() && preProcessor.check(TokenType.NEWLINE)) {
            preProcessor.advance();
        }

        // Guard against circular imports
        if (preProcessorContext.isIncluding(resolvedPath)) {
            preProcessor.getDiagnostics().reportError(
                    "Circular .IMPORT detected: " + pathValue, pathToken.source().fileName(), pathToken.source().lineNumber());
            return;
        }

        // Compute alias chain: parent chain + alias
        String parentChain = preProcessorContext.currentAliasChain();
        String aliasUpper = alias.toUpperCase();
        String aliasChain = (parentChain == null || parentChain.isEmpty())
                ? aliasUpper
                : parentChain + "." + aliasUpper;

        preProcessor.includes(new SourceFile(aliasChain, pathValue, resolvedPath, 0, importToken.source(),
                preProcessorContext.linesOf(resolvedPath)));

        // Copy the pre-lexed tokens into this placement: every token names the placement it
        // belongs to and keeps instance 0, because the placement's chain identifies the import
        List<Token> newTokens = new ArrayList<>(tokens.size() + 2);
        for (Token token : tokens) {
            SourceInfo at = token.source();
            newTokens.add(token.with(new SourceInfo(at.fileName(), at.lineNumber(), at.columnNumber(), aliasChain,
                    at.expansion())));
        }

        // Wrap with PUSH_CTX/POP_CTX — PUSH_CTX carries PlacementContext with alias chain
        PlacementContext placementCtx = new PlacementContext(resolvedPath, aliasChain);
        SourceInfo directive = importToken.source();
        SourceInfo marker = new SourceInfo(directive.fileName(), directive.lineNumber(), 0, directive.placement(),
                directive.expansion());
        newTokens.add(0, new Token(TokenType.DIRECTIVE, ".PUSH_CTX", placementCtx, marker));
        newTokens.add(new Token(TokenType.DIRECTIVE, ".POP_CTX", null, marker));

        // The inclusion enters the module's alias chain and stays open until the injected
        // .POP_CTX token is processed by the preprocessor — not in this handler.
        preProcessorContext.enterInclusion(placementCtx);

        // Inject after the .IMPORT directive (tokens remain for the parser)
        preProcessor.injectTokens(newTokens, 0);
    }

    /**
     * Checks that nothing but {@code USING source AS target} clauses follows the alias on the
     * line, and moves to the line's end. A clause that begins with {@code USING} but is malformed
     * is left to the parser, which reads the clauses and names what is wrong with them; any
     * other token is reported here, because the directive stands alone on its line.
     *
     * @throws ErrorRecoveryException if a token that begins no clause follows the alias; it has
     *         been reported.
     */
    private void checkRestOfLine(PreProcessor preProcessor) {
        boolean clausesWellFormed = true;
        while (!preProcessor.isAtEnd() && !preProcessor.check(TokenType.NEWLINE)) {
            Token token = preProcessor.peek();
            if (clausesWellFormed && !isWord(token, "USING")) {
                String message = ".IMPORT must stand alone on its line; found '" + token.text()
                        + "' after the alias and its USING clauses.";
                preProcessor.getDiagnostics().reportError(message, token.source().fileName(), token.source().lineNumber());
                throw new ErrorRecoveryException(message);
            }
            if (clausesWellFormed) {
                clausesWellFormed = matchUsingClause(preProcessor);
            } else {
                preProcessor.advance();
            }
        }
    }

    /**
     * Consumes one {@code USING source AS target} clause as far as it is well formed.
     *
     * @return {@code true} if all four tokens were there.
     */
    private boolean matchUsingClause(PreProcessor preProcessor) {
        preProcessor.advance(); // USING
        if (!preProcessor.check(TokenType.IDENTIFIER)) return false;
        preProcessor.advance();
        if (preProcessor.isAtEnd() || !isWord(preProcessor.peek(), "AS")) return false;
        preProcessor.advance();
        if (!preProcessor.check(TokenType.IDENTIFIER)) return false;
        preProcessor.advance();
        return true;
    }

    private static boolean isWord(Token token, String word) {
        return token.type() == TokenType.IDENTIFIER && word.equalsIgnoreCase(token.text());
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
