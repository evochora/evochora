package org.evochora.compiler.preprocessor;

import org.evochora.compiler.core.*;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.core.directives.DirectiveHandlerRegistry;
import org.evochora.compiler.core.directives.IDirectiveHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class PreProcessorPass implements ParsingContext {

    private final List<Token> tokens;
    private final DiagnosticsEngine diagnostics;
    private final DirectiveHandlerRegistry directiveRegistry;
    private final Path basePath;
    private int current = 0;
    private final Set<String> includedFiles = new HashSet<>();

    public PreProcessorPass(List<Token> initialTokens, DiagnosticsEngine diagnostics, Path basePath) {
        this.tokens = new ArrayList<>(initialTokens); // Make a mutable copy
        this.diagnostics = diagnostics;
        this.basePath = basePath;
        this.directiveRegistry = DirectiveHandlerRegistry.initialize();
    }

    public List<Token> expand() {
        // We must use an index-based loop because the size of the token list can change during iteration.
        for (current = 0; current < tokens.size(); /* current is advanced inside */) {
            if (peek().type() == TokenType.DIRECTIVE) {
                Optional<IDirectiveHandler> handlerOpt = directiveRegistry.get(peek().text());
                if (handlerOpt.isPresent()) {
                    IDirectiveHandler handler = handlerOpt.get();
                    if (handler.getPhase() == CompilerPhase.PREPROCESSING) {
                        // Der Handler ist verantwortlich für das Vorrücken des Cursors
                        // über die von ihm verarbeiteten Tokens.
                        handler.parse(this);
                        // Nach der Token-Injektion wollen wir den Stream von vorne neu scannen.
                        // Der `includedFiles`-Satz verhindert eine Endlosschleife.
                        current = 0;
                        continue;
                    }
                }
            }
            current++;
        }
        return tokens;
    }

    // --- ParsingContext Implementation ---

    @Override
    public boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().type() == type;
    }

    @Override
    public Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    @Override
    public Token peek() {
        return tokens.get(current);
    }

    @Override
    public Token previous() {
        if (current == 0) return null;
        return tokens.get(current - 1);
    }

    @Override
    public Token consume(TokenType type, String errorMessage) {
        if (check(type)) {
            return advance();
        }
        Token unexpected = peek();
        getDiagnostics().reportError(errorMessage, "Unknown", unexpected.line());
        throw new RuntimeException("Parser error: " + errorMessage);
    }

    @Override
    public DiagnosticsEngine getDiagnostics() {
        return diagnostics;
    }

    @Override
    public boolean isAtEnd() {
        return current >= tokens.size() || peek().type() == TokenType.END_OF_FILE;
    }

    public void injectTokens(List<Token> newTokens, int tokensToRemove) {
        // The handler has already advanced the 'current' pointer past the tokens to be removed.
        int startIndex = current - tokensToRemove;
        if (startIndex < 0) { /* error handling */ return; }

        // Remove the directive and its arguments
        for (int i = 0; i < tokensToRemove; i++) {
            tokens.remove(startIndex);
        }

        // Remove the EOF token from the injected list
        if (!newTokens.isEmpty() && newTokens.get(newTokens.size() - 1).type() == TokenType.END_OF_FILE) {
            newTokens.remove(newTokens.size() - 1);
        }

        // Add all new tokens at the calculated start index.
        tokens.addAll(startIndex, newTokens);

        // Reset 'current' to the start of the injected tokens.
        current = startIndex;
    }

    public boolean hasAlreadyIncluded(String path) {
        return includedFiles.contains(path);
    }

    public void markAsIncluded(String path) {
        includedFiles.add(path);
    }

    public Path getBasePath() {
        return basePath;
    }
}
