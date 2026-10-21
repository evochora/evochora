package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.DirectiveLine;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provides parser directive handlers with access to the token stream.
 * This interface decouples handlers from the concrete {@link Parser} implementation.
 */
public interface IParsingContext {
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
     * Checks if the current token is a {@link TokenType#SYMBOL} with the given text, without
     * consuming it.
     * @param text The symbol, as the feature registered it.
     * @return true if the current token is that symbol, false otherwise.
     */
    boolean checkSymbol(String text);

    /**
     * Checks if the current token is a {@link TokenType#SYMBOL} with the given text. If so,
     * consumes it.
     * @param text The symbol, as the feature registered it.
     * @return true if the current token was that symbol and has been consumed, false otherwise.
     */
    boolean matchSymbol(String text);

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
     * @return The token before the current one, or {@code null} at the start of the stream.
     */
    Token previous();

    /**
     * Consumes the current token if it is of the expected type. If not, it reports the error
     * at the token and unwinds to the next statement, so the caller never sees a mismatch.
     * @param type The expected token type.
     * @param errorMessage The error message to report if the token type does not match.
     * @return The consumed token.
     * @throws org.evochora.compiler.diagnostics.ErrorRecoveryException if the token type does
     *         not match; the mismatch has been reported before it is thrown.
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
     * Passes over the rest of the current statement: every token up to its end, which stays, so
     * that a handler that gives up on a statement leaves nothing of it for the next one.
     */
    void skipStatement();

    /**
     * Parses an expression (literal, register, identifier, vector, or computation).
     * @return The parsed {@link OperandNode} for the expression, or {@code null} after a
     *         reported error.
     */
    OperandNode expression();

    /**
     * Reads a list of tokens apart from the token stream as exactly one operand, as
     * {@link #expression()} reads one. Nothing is reported: the caller knows what the tokens were
     * meant to be and says so in its own words.
     *
     * @param tokens The tokens, ending with an end of file.
     * @return The operand, or empty when the tokens are none, hold more than one operand, or end
     *         before one is complete.
     */
    Optional<OperandNode> operandOf(List<Token> tokens);

    /**
     * Reads a list of tokens apart from the token stream as exactly one operand, as
     * {@link #operandOf(List)} does, and reports through this parser's diagnostics why the tokens
     * form none: the error of the operand itself at the token at fault, and a token after a
     * complete operand as unexpected. The list may end with an end of file or without one; the
     * reading ends after its last token either way.
     *
     * @param tokens The tokens of the operand.
     * @return The operand, or empty after a reported error; empty and unreported when the tokens
     *         are none, which the caller, knowing what it expected, reports itself.
     */
    Optional<OperandNode> readOperand(List<Token> tokens);

    /**
     * Returns the placement of the compilation root, so that a handler can tell the main program
     * from a module.
     *
     * @return The alias chain of the root placement, or {@code null} when the parser was given none.
     */
    String rootAliasChain();

    /**
     * Returns the lexed text of every value of the compilation's options, by its name as the
     * options normalise it: upper-cased. The tokens stand apart from the token stream; a handler
     * reads them with {@link #operandOf(List)}.
     *
     * @return The tokens of every value by its name; empty when the compilation has no values. The
     *         tokens of a text the lexer rejected are an empty list.
     */
    Map<String, List<Token>> values();

    /**
     * Parses the statements of a range of the token stream, as a block handler parses a part of
     * its block: from the first index up to the second, exclusive, which is the end of the part
     * by {@link org.evochora.compiler.frontend.BlockReader.Block#partEnd}. A nested block inside
     * the range is parsed through its own handler. The parser stands at the end of the range
     * afterwards.
     *
     * @param from The index of the first token of the range.
     * @param to   The end of the range, exclusive.
     * @return The nodes of the statements, without the statements that produced none.
     */
    List<AstNode> statements(int from, int to);

    /**
     * Finds the physical line of the directive at an index, by the rules of
     * {@link DirectiveLine}, with no token before it passed over; a block handler reads the head
     * of a divider through it.
     *
     * @param index The index of the directive token in the parser's token stream.
     * @return The directive's operands, their end, and the index of the line after it.
     */
    DirectiveLine lineOf(int index);

    /**
     * Returns the token at an index of the parser's token stream, as a block handler takes the
     * position of its closer.
     *
     * @param index The index.
     * @return The token.
     */
    Token tokenAt(int index);

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
