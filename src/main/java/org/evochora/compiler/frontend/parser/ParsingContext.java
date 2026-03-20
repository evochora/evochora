package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;

/**
 * Provides parser directive handlers with access to the token stream.
 * This interface decouples handlers from the concrete {@link Parser} implementation.
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

    /**
     * Parses an expression (literal, register, identifier, or vector).
     * @return The parsed {@link AstNode} for the expression.
     */
    AstNode expression();

    /**
     * Parses a single declaration (directive or statement).
     * @return The parsed {@link AstNode}, or null if an error occurs.
     */
    AstNode declaration();

    /**
     * Returns the generic parser state container that features use
     * to store and retrieve their own state objects.
     * @return The parser state.
     */
    ParserState state();

    /**
     * Returns whether the current statement was preceded by the EXPORT keyword.
     * Set by the parser when it consumes an EXPORT prefix.
     * @return true if the current statement is exported.
     */
    boolean isExported();
}