package org.evochora.compiler.features.importdir;

import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.ErrorRecoveryException;
import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.Export;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.frontend.preprocessor.PlacementContext;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the {@code .IMPORT} directive in the preprocessor phase.
 * Inlines the imported module's pre-lexed tokens at the directive location, wrapped with
 * PUSH_CTX/POP_CTX for relative .ORG support. The directive tokens remain in the
 * stream for the parser to create an {@code ImportNode}. The handler reads the path and the alias
 * and passes over the rest of the statement, which the parser reads. The directive begins a
 * statement: anything but an {@code EXPORT} before it without a statement end is an error.
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
        DirectiveLine line = preProcessor.lineOf(preProcessor.getCurrentIndex(), Export::isWord);
        if (!line.startsStatement()) {
            Token directive = preProcessor.peek();
            String message = Export.statementStartMessage(line);
            preProcessor.getDiagnostics().reportError(message, directive.source().fileName(), directive.source().lineNumber());
            throw new ErrorRecoveryException(message);
        }
        Token importToken = preProcessor.peek();
        preProcessor.advance(); // consume .IMPORT

        Token pathToken = preProcessor.consume(TokenType.STRING, "Expected a file path in quotes after .IMPORT.");

        // The alias is read here because the module's tokens are placed under it; the parser
        // reads the directive again, with its clauses, and reports every other malformation.
        String alias = extractAlias(preProcessor);
        if (alias == null) {
            preProcessor.getDiagnostics().reportError(
                    "Expected AS after .IMPORT path.", pathToken.source().fileName(), pathToken.source().lineNumber());
            return;
        }
        while (!preProcessor.isAtEnd() && !preProcessor.check(TokenType.NEWLINE)) {
            preProcessor.advance();
        }

        // Resolve the path to an absolute path
        String pathValue = (String) pathToken.value();
        String resolvedPath;
        try {
            resolvedPath = preProcessor.getResolver().resolve(pathValue, pathToken.source().fileName());
        } catch (org.evochora.compiler.util.SourceRootResolver.UnresolvablePathException e) {
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

        // The module's tokens go after the import's statement end, because the parser reads the
        // directive's clauses up to that end. A ';' there puts another statement on the line;
        // it gets its statement end back after the injected tokens, so that it still begins a
        // statement where the preprocessor reads it.
        Token statementEnd = null;
        if (!preProcessor.isAtEnd() && preProcessor.check(TokenType.NEWLINE)) {
            statementEnd = preProcessor.advance();
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
        if (statementEnd != null && ";".equals(statementEnd.text())) {
            newTokens.add(new Token(TokenType.NEWLINE, ";", null, marker));
        }

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
