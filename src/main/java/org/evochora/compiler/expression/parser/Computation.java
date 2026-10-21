package org.evochora.compiler.expression.parser;

import org.evochora.compiler.expression.INameResolution;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.List;
import java.util.Locale;

/**
 * The forms of the language under which tokens are a computation, which the
 * {@link ExpressionParser} reads: a parenthesis, or a name or a number followed by a call or an
 * index, each with any calls and indexes directly after it; a typing {@code type : value} whose
 * type is a name or such a form and whose value is such a form, a name or a number with its
 * sign; or the cursor {@code @} with any indexes after it. A number or a name written alone is
 * no computation, nor is a typed literal written out, a molecule type of the instruction set
 * before the colon and a number with its sign after it. Every phase that reads an operand or
 * the value of a directive asks here where a computation begins and how far it reaches, so that
 * the phases agree on what a computation is.
 */
public final class Computation {

    private final IInstructionSet isa;

    /**
     * Creates the forms over an instruction set.
     *
     * @param isa The instruction set whose molecule types a typed literal names.
     */
    public Computation(IInstructionSet isa) {
        this.isa = isa;
    }

    /**
     * Tells how many tokens the computation at the given position takes.
     *
     * @param tokens The tokens.
     * @param index  The position in the token list.
     * @return The number of tokens, or 0 if no computation stands there.
     */
    public int lengthAt(List<Token> tokens, int index) {
        TokenType type = typeAt(tokens, index);
        if (type == TokenType.IDENTIFIER && typeAt(tokens, index + 1) == TokenType.COLON) {
            boolean typeWritten = isa.moleculeType(tokens.get(index).text().toUpperCase(Locale.ROOT)).isPresent();
            if (typeWritten && signedNumberLength(tokens, index + 2) > 0) {
                return 0;
            }
            int value = typedValueLengthAt(tokens, index + 2);
            return value > 0 ? 2 + value : 0;
        }
        if (type == TokenType.IDENTIFIER && INameResolution.CURSOR.equals(tokens.get(index).text())) {
            return 1 + suffixLength(tokens, index + 1);
        }
        int value = valueLengthAt(tokens, index);
        if (value > 0 && typeAt(tokens, index + value) == TokenType.COLON) {
            int typed = typedValueLengthAt(tokens, index + value + 1);
            return typed > 0 ? value + 1 + typed : value;
        }
        return value;
    }

    /**
     * Tells how many tokens the value after a typing colon takes: a computed value, a name with
     * any calls and indexes after it, or a number with its sign.
     */
    private int typedValueLengthAt(List<Token> tokens, int index) {
        int value = valueLengthAt(tokens, index);
        if (value > 0) {
            return value;
        }
        if (typeAt(tokens, index) == TokenType.IDENTIFIER) {
            return 1 + suffixLength(tokens, index + 1);
        }
        return signedNumberLength(tokens, index);
    }

    /**
     * Tells how many tokens a computed value at the given position takes: a parenthesis, or a
     * name or a number followed by a call or an index, each with any calls and indexes directly
     * after it.
     */
    private static int valueLengthAt(List<Token> tokens, int index) {
        TokenType type = typeAt(tokens, index);
        if (type == TokenType.OPEN_PARENTHESIS) {
            int group = groupedLength(tokens, index);
            return group + suffixLength(tokens, index + group);
        }
        if (type == TokenType.IDENTIFIER || type == TokenType.OPCODE || type == TokenType.NUMBER) {
            int suffix = suffixLength(tokens, index + 1);
            return suffix > 0 ? 1 + suffix : 0;
        }
        return 0;
    }

    /**
     * Tells how many tokens the calls and indexes directly at the given position take, one after
     * another, as in {@code MIN(A, 1)[0]} or {@code V[0][1]}.
     */
    private static int suffixLength(List<Token> tokens, int index) {
        int length = 0;
        while (typeAt(tokens, index + length) == TokenType.CALL_PARENTHESIS
                || typeAt(tokens, index + length) == TokenType.OPEN_BRACKET) {
            length += groupedLength(tokens, index + length);
        }
        return length;
    }

    /**
     * Tells how many tokens the group opened at the given position takes, its opener and its
     * matching closing token included, with the nesting of its own kind of bracket counted; a
     * group left open ends before the end of its line, where the expression parser reports it.
     */
    private static int groupedLength(List<Token> tokens, int index) {
        boolean bracket = typeAt(tokens, index) == TokenType.OPEN_BRACKET;
        int depth = 0;
        int i = index;
        while (typeAt(tokens, i) != TokenType.NEWLINE && typeAt(tokens, i) != TokenType.END_OF_FILE) {
            TokenType type = typeAt(tokens, i);
            if (bracket ? type == TokenType.OPEN_BRACKET
                    : type == TokenType.OPEN_PARENTHESIS || type == TokenType.CALL_PARENTHESIS) {
                depth++;
            } else if (type == (bracket ? TokenType.CLOSE_BRACKET : TokenType.CLOSE_PARENTHESIS)) {
                depth--;
            }
            i++;
            if (depth == 0) {
                break;
            }
        }
        return i - index;
    }

    /**
     * Tells how many tokens a number with its sign at the given position takes: two for a
     * {@code -} followed by a number, one for a number, none if no number stands there.
     */
    private static int signedNumberLength(List<Token> tokens, int index) {
        boolean negative = typeAt(tokens, index) == TokenType.SYMBOL && "-".equals(tokens.get(index).text());
        int numberIndex = negative ? index + 1 : index;
        return typeAt(tokens, numberIndex) == TokenType.NUMBER ? (negative ? 2 : 1) : 0;
    }

    private static TokenType typeAt(List<Token> tokens, int index) {
        return index < tokens.size() ? tokens.get(index).type() : TokenType.END_OF_FILE;
    }
}
