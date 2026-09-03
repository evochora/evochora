package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.util.SourceRootResolver;
import java.util.*;

/**
 * The preprocessor for the assembly language. It runs after the lexer and before the parser.
 * It walks the token stream and hands every token that a handler is registered for to that
 * handler, which rewrites the stream in place.
 */
public class PreProcessor {

    private final List<Token> tokens;
    private final DiagnosticsEngine diagnostics;
    private final SourceRootResolver resolver;
    private int current = 0;
    private final PreProcessorContext ppContext;

    /**
     * Constructs a new PreProcessor.
     *
     * @param initialTokens  The initial list of tokens from the lexer.
     * @param diagnostics    The engine for reporting errors and warnings.
     * @param resolver       The source root resolver for path resolution.
     * @param ppContext      The shared preprocessor context: the handlers to dispatch to, the
     *                       pre-lexed tokens of includable files, the open inclusions.
     */
    public PreProcessor(List<Token> initialTokens, DiagnosticsEngine diagnostics, SourceRootResolver resolver,
                        PreProcessorContext ppContext) {
        this.tokens = new ArrayList<>(initialTokens);
        this.diagnostics = diagnostics;
        this.resolver = resolver;
        this.ppContext = ppContext;
    }

    /**
     * Runs the preprocessor on the token stream. Every token is looked up in the context's
     * handler registry; a token with a handler is handed to it, which rewrites the stream at
     * the current position, and the walk continues from there. A token without one is left as
     * it is.
     * @return The preprocessing result containing the expanded tokens.
     */
    public PreProcessorResult expand() {
        while (current < tokens.size()) {
            Optional<IPreProcessorHandler> handler = ppContext.handlers().get(peek().text());
            if (handler.isPresent()) {
                handler.get().process(this, ppContext);
            } else {
                current++;
            }
        }
        return new PreProcessorResult(tokens);
    }

    // --- Token stream navigation ---

    /**
     * Checks if the current token matches any of the given types. If so, consumes it.
     * @param types The token types to match.
     * @return true if the current token matches one of the types, false otherwise.
     */
    public boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the current token is of the given type without consuming it.
     * @param type The token type to check.
     * @return true if the current token is of the given type, false otherwise.
     */
    public boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().type() == type;
    }

    /**
     * Consumes the current token and returns the previous one.
     * @return The token before the one that was consumed.
     */
    public Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    /**
     * Returns the current token without consuming it.
     * @return The current token.
     */
    public Token peek() {
        return tokens.get(current);
    }

    /**
     * Returns the previously consumed token.
     * @return The previous token, or null if at the start.
     */
    public Token previous() {
        if (current == 0) return null;
        return tokens.get(current - 1);
    }

    /**
     * Consumes the current token if it is of the expected type. Reports an error otherwise.
     * @param type The expected token type.
     * @param errorMessage The error message if the token type does not match.
     * @return The consumed token.
     * @throws RuntimeException if the current token does not match the expected type.
     */
    public Token consume(TokenType type, String errorMessage) {
        if (check(type)) return advance();
        Token unexpected = peek();
        getDiagnostics().reportError(errorMessage, unexpected.fileName(), unexpected.line());
        throw new RuntimeException("Parser error: " + errorMessage);
    }

    /**
     * Gets the diagnostics engine for reporting errors and warnings.
     * @return The diagnostics engine.
     */
    public DiagnosticsEngine getDiagnostics() {
        return diagnostics;
    }

    /**
     * Checks if the end of the token stream has been reached.
     * @return true if at the end of the stream, false otherwise.
     */
    public boolean isAtEnd() {
        return current >= tokens.size() || tokens.get(current).type() == TokenType.END_OF_FILE;
    }

    // --- Token stream manipulation (used by directive handlers) ---

    /**
     * Injects tokens into the stream at the current position, optionally removing existing tokens first.
     * @param newTokens The tokens to inject.
     * @param tokensToRemove The number of tokens to remove at the current position before injecting.
     */
    public void injectTokens(List<Token> newTokens, int tokensToRemove) {
        int startIndex = current;
        for (int i = 0; i < tokensToRemove; i++) {
            if (startIndex < tokens.size()) tokens.remove(startIndex);
        }
        if (!newTokens.isEmpty() && newTokens.get(newTokens.size() - 1).type() == TokenType.END_OF_FILE) {
            newTokens.remove(newTokens.size() - 1);
        }
        tokens.addAll(startIndex, newTokens);
        this.current = startIndex;
    }

    /**
     * Gets the source root resolver for path resolution.
     * @return The source root resolver.
     */
    public SourceRootResolver getResolver() {
        return resolver;
    }

    /**
     * Gets the current index in the token stream.
     * @return The current index.
     */
    public int getCurrentIndex() {
        return current;
    }

    /**
     * Returns the token at the specified index in the stream.
     * @param index The index of the token to retrieve.
     * @return The token at the given index.
     */
    public Token getToken(int index) {
        return tokens.get(index);
    }

    /**
     * Returns the number of tokens in the stream.
     * @return The token count.
     */
    public int streamSize() {
        return tokens.size();
    }

    /**
     * Removes a specified number of tokens from the stream starting at a given index.
     * @param startIndex The starting index.
     * @param count The number of tokens to remove.
     */
    public void removeTokens(int startIndex, int count) {
        if (startIndex < 0 || (startIndex + count) > tokens.size()) {
            throw new IllegalArgumentException("Invalid token removal bounds: startIndex=" + startIndex + ", count=" + count + ", tokens.size()=" + tokens.size());
        }
        tokens.subList(startIndex, startIndex + count).clear();
        this.current = startIndex;
    }

    /**
     * Gets the shared context for the preprocessor.
     * @return The preprocessor context.
     */
    public PreProcessorContext getPreProcessorContext() {
        return this.ppContext;
    }
}
