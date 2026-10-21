package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.Flags;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The condition of a conditional directive and its evaluation against the {@link Flags}. The
 * dependency scan and the preprocessor read it from the tokens of the directive's line and
 * evaluate it here, each with its own way of computing a value, so the two phases agree on what
 * holds.
 * <p>
 * {@code .IFDEF NAME} holds if the flag is set. {@code .IFDEF left op right} compares two values,
 * each a flag name, an integer or a computation, the tokens up to the comparison symbol outside
 * brackets; a flag name stands for the flag's value. When the left side is a flag name alone and
 * the flag is not set, the comparison does not hold. {@code .IFDEF (computation)}, a parenthesis,
 * a call or an index alone, holds when the computation can be computed. {@code .IFNDEF NAME}
 * holds if the flag is not set and takes no comparison. A value that cannot be computed, because
 * a flag has no value, a name is no flag set or the result is no number, is an error, not a
 * condition that fails.
 *
 * @param negated    Whether the directive asks for the flag not to be set, as {@code .IFNDEF}
 *                   and {@code .ELSEIFNDEF} do.
 * @param left       The tokens of the left side, or of the whole condition without a
 *                   comparison: a flag name alone, or a computation.
 * @param comparison The comparison, or empty for a test of the left side alone.
 */
public record Condition(boolean negated, List<Token> left, Optional<Comparison> comparison) {

    private static final Set<String> COMPARISON_SYMBOLS = Arrays.stream(Operator.values())
            .flatMap(operator -> operator.symbols().stream()).collect(Collectors.toUnmodifiableSet());

    /**
     * Copies the left side.
     */
    public Condition {
        left = List.copyOf(left);
    }

    /**
     * A comparison operator.
     */
    public enum Operator {
        /** {@code =}, also written {@code ==}: the values are equal. */
        EQ("=", "=="),
        /** {@code <>}, also written {@code !=}: the values differ. */
        NE("<>", "!="),
        /** {@code <}: the left value is less. */
        LT("<"),
        /** {@code <=}: the left value is less or equal. */
        LE("<="),
        /** {@code >}: the left value is greater. */
        GT(">"),
        /** {@code >=}: the left value is greater or equal. */
        GE(">=");

        private final List<String> symbols;

        Operator(String... symbols) {
            this.symbols = List.of(symbols);
        }

        /**
         * Returns the symbols the operator is written with.
         *
         * @return The symbols, e.g. {@code =} and {@code ==}.
         */
        public List<String> symbols() {
            return symbols;
        }

        /**
         * Finds the operator written with a symbol.
         *
         * @param text The symbol.
         * @return The operator, or empty if the text is none.
         */
        public static Optional<Operator> of(String text) {
            for (Operator operator : values()) {
                if (operator.symbols.contains(text)) {
                    return Optional.of(operator);
                }
            }
            return Optional.empty();
        }

        /**
         * Compares the value on the left of the operator with the value on its right.
         *
         * @param left  The value on the left.
         * @param right The value it is compared with.
         * @return {@code true} if the comparison holds.
         */
        boolean holds(long left, long right) {
            return switch (this) {
                case EQ -> left == right;
                case NE -> left != right;
                case LT -> left < right;
                case LE -> left <= right;
                case GT -> left > right;
                case GE -> left >= right;
            };
        }
    }

    /**
     * A comparison of the left side with a right side.
     *
     * @param operator The operator.
     * @param right    The tokens of the right side: a flag name, an integer or a computation.
     */
    public record Comparison(Operator operator, List<Token> right) {
        /**
         * Copies the right side.
         */
        public Comparison {
            right = List.copyOf(right);
        }
    }

    /**
     * A condition that cannot be read.
     */
    public static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        /**
         * Creates the error.
         *
         * @param message What is wrong, as the programmer is told.
         */
        public Invalid(String message) {
            super(message);
        }
    }

    /**
     * Reports whether a directive asks for a flag not to be set: {@code .IFNDEF} and
     * {@code .ELSEIFNDEF}.
     *
     * @param directive The directive word, in any case.
     * @return {@code true} for the negated directives.
     */
    public static boolean negates(String directive) {
        return directive.toUpperCase(Locale.ROOT).endsWith("NDEF");
    }

    /**
     * Reads a condition from the tokens that follow the directive up to the end of its line. The
     * values are only delimited here; whether they compute is found when the condition is
     * evaluated.
     *
     * @param directive The directive token.
     * @param tokens    The tokens after it, up to the line end, excluded.
     * @return The condition.
     * @throws Invalid if the tokens are no condition the directive takes; the message names what
     *                 is wrong.
     */
    public static Condition parse(Token directive, List<Token> tokens) throws Invalid {
        String word = directive.text().toUpperCase(Locale.ROOT);
        if (tokens.isEmpty()) {
            throw new Invalid(word + " needs a flag name");
        }
        Token first = tokens.get(0);
        if (negates(word)) {
            if (!isNameToken(first)) {
                throw new Invalid(word + " needs a flag name, found '" + first.text() + "'");
            }
            if (tokens.size() > 1) {
                throw new Invalid(word + " takes no comparison; compare with .IFDEF or .ELSEIFDEF and use .ELSEDEF"
                        + " for the opposite");
            }
            return new Condition(true, tokens, Optional.empty());
        }
        List<List<Token>> pieces = DirectiveLine.pieces(tokens, Set.of(), COMPARISON_SYMBOLS);
        if (pieces.size() == 1) {
            if (isFlagName(tokens) || isComputation(tokens)) {
                return new Condition(false, tokens, Optional.empty());
            }
            if (isNameToken(first)) {
                throw new Invalid("Expected a comparison (=, ==, <>, !=, <, <=, >, >=) after the flag name of " + word
                        + ", found '" + tokens.get(1).text() + "'");
            }
            throw new Invalid(word + " needs a flag name, found '" + first.text() + "'");
        }
        List<Token> left = pieces.get(0);
        List<Token> right = pieces.get(1);
        Token symbol = tokens.get(left.size());
        if (pieces.size() > 2) {
            throw new Invalid("Unexpected '" + tokens.get(left.size() + 1 + right.size()).text()
                    + "' after the condition of " + word);
        }
        if (left.isEmpty()) {
            throw new Invalid("Expected an integer, a flag name or a computation before '" + symbol.text()
                    + "' in " + word);
        }
        if (right.isEmpty()) {
            throw new Invalid("Expected an integer, a flag name or a computation after '" + symbol.text()
                    + "' in " + word);
        }
        Operator operator = Operator.of(symbol.text()).orElseThrow();
        return new Condition(false, left, Optional.of(new Comparison(operator, right)));
    }

    /**
     * Evaluates the condition against the flags as they are.
     *
     * @param flags    The flags.
     * @param evaluate Computes the number of the tokens of a value, or gives empty when they
     *                 compute none; the phase decides whether that is reported.
     * @return Whether the condition holds, or empty if a value it needs cannot be computed.
     */
    public Optional<Boolean> holds(Flags flags, Function<List<Token>, Optional<Long>> evaluate) {
        if (negated) {
            return Optional.of(!flags.isSet(left.get(0).text()));
        }
        if (comparison.isEmpty()) {
            return isFlagName(left) ? Optional.of(flags.isSet(left.get(0).text())) : evaluate.apply(left).map(value -> true);
        }
        Comparison compared = comparison.get();
        if (isFlagName(left) && !flags.isSet(left.get(0).text())) {
            return evaluate.apply(compared.right()).map(value -> false);
        }
        Optional<Long> leftValue = evaluate.apply(left);
        if (leftValue.isEmpty()) {
            return Optional.empty();
        }
        return evaluate.apply(compared.right()).map(rightValue -> compared.operator().holds(leftValue.get(), rightValue));
    }

    /**
     * Reports whether the tokens of a value are a flag name alone.
     *
     * @param tokens The tokens of a side.
     * @return {@code true} for a single token that names a flag.
     */
    static boolean isFlagName(List<Token> tokens) {
        return tokens.size() == 1 && isNameToken(tokens.get(0));
    }

    /**
     * Reports whether the tokens of a condition without a comparison are a computation: a
     * parenthesis, a call or an index. Whether it is one computation and nothing after it is
     * found when it is computed.
     */
    private static boolean isComputation(List<Token> tokens) {
        TokenType first = tokens.get(0).type();
        if (first == TokenType.OPEN_PARENTHESIS) {
            return true;
        }
        if (tokens.size() < 2 || (first != TokenType.IDENTIFIER && first != TokenType.OPCODE)) {
            return false;
        }
        TokenType second = tokens.get(1).type();
        return second == TokenType.CALL_PARENTHESIS || second == TokenType.OPEN_BRACKET;
    }

    /**
     * Reports whether a token names a flag: an identifier, or a word the lexer took for an opcode,
     * whose text is a flag name.
     */
    static boolean isNameToken(Token token) {
        return (token.type() == TokenType.IDENTIFIER || token.type() == TokenType.OPCODE)
                && Flags.isName(token.text());
    }
}
