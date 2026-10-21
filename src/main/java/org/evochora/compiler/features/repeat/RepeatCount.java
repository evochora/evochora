package org.evochora.compiler.features.repeat;

import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.List;

/**
 * Finds the tokens of a repeat count in the stream: one value, a number, a flag name or a
 * computation, which a {@code -} may precede. A computation stands in parentheses, or is a call
 * or an index, so the count ends where its brackets close; what follows it on the line is not
 * part of it. Whether the tokens compute is decided when they are computed.
 */
final class RepeatCount {

    private RepeatCount() {
    }

    /**
     * Returns the tokens of the count that begins at a stream index.
     *
     * @param preProcessor The preprocessor holding the stream.
     * @param from         The stream index the count begins at.
     * @return The tokens of the count, in a list of their own; empty if no count begins there.
     */
    static List<Token> at(PreProcessor preProcessor, int from) {
        int lineEnd = from;
        while (lineEnd < preProcessor.streamSize() && !ends(preProcessor.getToken(lineEnd))) {
            lineEnd++;
        }
        List<Token> line = preProcessor.tokensOf(from, lineEnd);
        return line.subList(0, end(line));
    }

    /**
     * The index after the count at the start of a line's tokens, or 0 if none stands there.
     */
    private static int end(List<Token> tokens) {
        int i = 0;
        if (i < tokens.size() && tokens.get(i).type() == TokenType.SYMBOL && "-".equals(tokens.get(i).text())) {
            i++;
        }
        if (i >= tokens.size()) {
            return 0;
        }
        switch (tokens.get(i).type()) {
            case NUMBER -> i++;
            case OPEN_PARENTHESIS -> i = closed(tokens, i);
            case IDENTIFIER, OPCODE -> {
                i++;
                if (i < tokens.size() && tokens.get(i).type() == TokenType.CALL_PARENTHESIS) {
                    i = closed(tokens, i);
                }
            }
            default -> {
                return 0;
            }
        }
        while (i < tokens.size() && tokens.get(i).type() == TokenType.OPEN_BRACKET) {
            i = closed(tokens, i);
        }
        return i;
    }

    /**
     * The index after the bracket that closes the one at the given index, or the end of the
     * tokens if it is not closed.
     */
    private static int closed(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            switch (tokens.get(i).type()) {
                case OPEN_PARENTHESIS, CALL_PARENTHESIS, OPEN_BRACKET -> depth++;
                case CLOSE_PARENTHESIS, CLOSE_BRACKET -> {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
                default -> {
                }
            }
        }
        return tokens.size();
    }

    private static boolean ends(Token token) {
        return token.type() == TokenType.NEWLINE || token.type() == TokenType.END_OF_FILE;
    }
}
