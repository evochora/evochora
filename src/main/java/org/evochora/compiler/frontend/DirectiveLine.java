package org.evochora.compiler.frontend;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The physical line of a directive in a token stream: its operands, where they end, and whether
 * the directive stands alone on the line.
 * <p>
 * The rule is checked on the tokens' positions. Every token of the directive's file and line
 * either belongs to the directive or is the {@code \n} that ends the line; a {@code ;} is a
 * statement end on the same line and therefore another statement. Tokens of another file or
 * another line are not counted, so a marker an inclusion puts before a file's first line, a
 * separator a repetition puts between two bodies, and a token substituted into the directive
 * from another position never break the rule. Tokens directly before the directive that the
 * caller passes over, such as a keyword that belongs to the directive, do not break it either.
 *
 * @param directive The index of the directive token.
 * @param end       The index of the token that ends the operands: the next statement end, the
 *                  end-of-file token, or the size of the stream.
 * @param next      The index of the first token after the line, its statement end included.
 * @param operands  The tokens after the directive up to {@code end}, possibly none.
 * @param before    The token of the directive's file and line that stands before it and breaks
 *                  the rule: the nearest one that is not a statement end, or the nearest
 *                  statement end if there is nothing else; {@code null} if there is none.
 * @param separator The {@code ;} on the directive's line that ends the operands and puts another
 *                  statement after them; {@code null} if the operands end at a line break, at the
 *                  end of the file, or at a statement end of another line.
 */
public record DirectiveLine(int directive, int end, int next, List<Token> operands, Token before, Token separator) {

    /**
     * Creates the line, with an immutable copy of the operands.
     */
    public DirectiveLine {
        operands = List.copyOf(operands);
    }

    /**
     * Finds the line of the directive at an index, with no token before it passed over.
     *
     * @param tokens    The token stream.
     * @param directive The index of the directive token.
     * @return The line.
     */
    public static DirectiveLine of(List<Token> tokens, int directive) {
        return of(tokens, directive, token -> false);
    }

    /**
     * Finds the line of the directive at an index.
     *
     * @param tokens     The token stream.
     * @param directive  The index of the directive token.
     * @param passedOver The tokens directly before the directive that belong to it and are
     *                   passed over before the rule looks for a token before the directive.
     * @return The line.
     */
    public static DirectiveLine of(List<Token> tokens, int directive, Predicate<Token> passedOver) {
        Token word = tokens.get(directive);
        int end = directive + 1;
        while (end < tokens.size()) {
            TokenType type = tokens.get(end).type();
            if (type == TokenType.NEWLINE || type == TokenType.END_OF_FILE) {
                break;
            }
            end++;
        }
        int next = end < tokens.size() && tokens.get(end).type() == TokenType.NEWLINE ? end + 1 : end;

        Token separator = null;
        if (end < tokens.size()) {
            Token terminator = tokens.get(end);
            if (terminator.type() == TokenType.NEWLINE && ";".equals(terminator.text()) && sameLine(terminator, word)) {
                separator = terminator;
            }
        }
        return new DirectiveLine(directive, end, next, tokens.subList(directive + 1, end),
                tokenBefore(tokens, directive, passedOver), separator);
    }

    /**
     * Reports whether the directive stands alone on its physical line: no token of its file and
     * line before it, and its operands ended by a line break rather than a {@code ;} on the same
     * line.
     *
     * @return {@code true} if the rule holds.
     */
    public boolean standsAlone() {
        return before == null && separator == null;
    }

    private static Token tokenBefore(List<Token> tokens, int directive, Predicate<Token> passedOver) {
        Token word = tokens.get(directive);
        int i = directive - 1;
        while (i >= 0 && sameLine(tokens.get(i), word) && passedOver.test(tokens.get(i))) {
            i--;
        }
        Token found = null;
        for (; i >= 0 && sameLine(tokens.get(i), word); i--) {
            Token token = tokens.get(i);
            if (token.type() != TokenType.NEWLINE) {
                return token;
            }
            if (found == null) {
                found = token;
            }
        }
        return found;
    }

    private static boolean sameLine(Token a, Token b) {
        return a.source().lineNumber() == b.source().lineNumber()
                && Objects.equals(a.source().fileName(), b.source().fileName());
    }
}
