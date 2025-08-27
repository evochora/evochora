package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;

import java.nio.file.Path;
import java.util.List;

/**
 * An interface that encapsulates the contextual state during parsing or preprocessing.
 * It provides handlers with access to the token stream and other necessary services
 * without coupling them directly to a specific implementation like the parser.
 */
public interface ParsingContext {
    /**
     * Checks if the current token matches any of the given types. If so, consumes it.
     * @param types The token types to match.
     * @return true if the current token matches one of the types, false otherwise.
     */
    boolean match(TokenType... types);

    /**
     * Checks if the current token is of the given type without consuming it.
     * @param type The token type to check.
     * @return true if the current token is of the given type, false otherwise.
     */
    boolean check(TokenType type);

    /**
     * Consumes the current token and returns it.
     * @return The consumed token.
     */
    Token advance();

    /**
     * Returns the current token without consuming it.
     * @return The current token.
     */
    Token peek();

    /**
     * Returns the previously consumed token.
     * @return The previous token.
     */
    Token previous();

    /**
     * Consumes the current token if it is of the expected type.
     * If not, it reports an error.
     * @param type The expected token type.
     * @param errorMessage The error message to report if the token type does not match.
     * @return The consumed token, or null if the type did not match.
     */
    Token consume(TokenType type, String errorMessage);

    /**
     * Gets the diagnostics engine for reporting errors and warnings.
     * @return The diagnostics engine.
     */
    DiagnosticsEngine getDiagnostics();

    /**
     * Checks if the end of the token stream has been reached.
     * @return true if at the end of the stream, false otherwise.
     */
    boolean isAtEnd();

    // Methods specific to the PreProcessor

    /**
     * Injects a list of tokens into the stream, replacing a specified number of existing tokens.
     * This is primarily used for macro expansion.
     * @param tokens The list of tokens to inject.
     * @param tokensToRemove The number of tokens to remove from the original stream.
     */
    void injectTokens(List<Token> tokens, int tokensToRemove);

    /**
     * Gets the base path of the current file being processed.
     * This is used to resolve relative paths in include directives.
     * @return The base path.
     */
    Path getBasePath();

    /**
     * Checks if a file has already been included to prevent circular dependencies.
     * @param path The path of the file to check.
     * @return true if the file has already been included, false otherwise.
     */
    boolean hasAlreadyIncluded(String path);

    /**
     * Marks a file as having been included.
     * @param path The path of the file to mark.
     */
    void markAsIncluded(String path);
}