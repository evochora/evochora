package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The line of a directive of this feature in the preprocessor's token stream: its operands, its
 * end, and the rule that the directive stands alone on its physical line.
 * <p>
 * The rule is checked on the tokens' positions. Every token of the directive's file and line
 * either belongs to the directive or is the {@code \n} that ends the line; a {@code ;} is a
 * statement end on the same line and therefore another statement. Tokens of another file or
 * another line are not counted, so the marker an inclusion puts before a file's first line, a
 * separator a repetition puts between two bodies, and a macro argument substituted into the
 * directive never break the rule.
 *
 * @param directive The index of the directive token.
 * @param end       The index of the token that ends the operands: the next statement end, the
 *                  end-of-file token, or the size of the stream.
 */
record DirectiveLine(int directive, int end) {

    /**
     * Finds the line of the directive at an index.
     *
     * @param preProcessor The token stream.
     * @param directive    The index of the directive token.
     * @return The line.
     */
    static DirectiveLine at(PreProcessor preProcessor, int directive) {
        int i = directive + 1;
        while (i < preProcessor.streamSize()) {
            TokenType type = preProcessor.getToken(i).type();
            if (type == TokenType.NEWLINE || type == TokenType.END_OF_FILE) {
                break;
            }
            i++;
        }
        return new DirectiveLine(directive, i);
    }

    /**
     * Returns the tokens after the directive up to the statement end.
     *
     * @param preProcessor The token stream.
     * @return The operands, possibly none.
     */
    List<Token> operands(PreProcessor preProcessor) {
        List<Token> operands = new ArrayList<>(end - directive - 1);
        for (int i = directive + 1; i < end; i++) {
            operands.add(preProcessor.getToken(i));
        }
        return operands;
    }

    /**
     * Reports whether the directive stands alone on its physical line: no token of its file and
     * line before it, and its operands ended by a line break rather than a {@code ;} on the same
     * line.
     *
     * @param preProcessor The token stream.
     * @return {@code true} if the rule holds.
     */
    boolean standsAlone(PreProcessor preProcessor) {
        Token word = preProcessor.getToken(directive);
        if (directive > 0 && sameLine(preProcessor.getToken(directive - 1), word)) {
            return false;
        }
        if (end < preProcessor.streamSize()) {
            Token terminator = preProcessor.getToken(end);
            return !(terminator.type() == TokenType.NEWLINE && ";".equals(terminator.text())
                    && sameLine(terminator, word));
        }
        return true;
    }

    /**
     * Returns the index after the line, its line break included.
     *
     * @param preProcessor The token stream.
     * @return The index of the first token of the next line.
     */
    int next(PreProcessor preProcessor) {
        if (end < preProcessor.streamSize() && preProcessor.getToken(end).type() == TokenType.NEWLINE) {
            return end + 1;
        }
        return end;
    }

    private static boolean sameLine(Token a, Token b) {
        return a.line() == b.line() && Objects.equals(a.fileName(), b.fileName());
    }
}
