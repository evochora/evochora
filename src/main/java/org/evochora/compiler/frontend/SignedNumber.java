package org.evochora.compiler.frontend;

import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.List;
import java.util.Optional;

/**
 * An integer read from a token list: a number, which a {@code -} may precede, since the tokenizer
 * emits the sign as a token of its own. It serves the operand reader of the parser and the
 * grouping of a macro's arguments, where a sign stands outside a computation and belongs to the
 * number after it.
 *
 * @param value  The value of the number, negated when a {@code -} precedes it.
 * @param length The number of tokens the number takes: two with the sign, one without.
 */
public record SignedNumber(long value, int length) {

    /**
     * Reads the number that stands at the given position.
     *
     * @param tokens The tokens to read from.
     * @param index  The position of the number or of its sign.
     * @return The number and the tokens it takes, or empty if no number stands there.
     */
    public static Optional<SignedNumber> at(List<Token> tokens, int index) {
        boolean negative = index < tokens.size() && tokens.get(index).type() == TokenType.SYMBOL
                && tokens.get(index).text().equals("-");
        int numberIndex = negative ? index + 1 : index;
        if (numberIndex >= tokens.size() || tokens.get(numberIndex).type() != TokenType.NUMBER
                || !(tokens.get(numberIndex).value() instanceof Long number)) {
            return Optional.empty();
        }
        return Optional.of(new SignedNumber(negative ? -number : number, negative ? 2 : 1));
    }
}
