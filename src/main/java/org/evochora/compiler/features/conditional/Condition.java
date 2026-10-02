package org.evochora.compiler.features.conditional;

import org.evochora.compiler.api.IntegerLiteral;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The condition of a conditional directive, {@code NAME [op operand]}, and its evaluation
 * against the {@link Flags}. The dependency scan reads it from the text of a line, the
 * preprocessor from the tokens of the directive; both evaluate it here, so the two phases agree
 * on what holds.
 * <p>
 * {@code .IFDEF NAME} holds if the flag is set. {@code .IFDEF NAME op operand} holds if the flag
 * is set, has a value, and the comparison of that value with the operand holds; the operand is
 * an integer or the name of another flag, whose value is used. {@code .IFNDEF NAME} holds if the
 * flag is not set and takes no comparison. A comparison that cannot be made, because a flag has
 * no value or the operand names a flag that is not set, is an error, not a condition that fails.
 *
 * @param negated    Whether the directive asks for the flag not to be set, as {@code .IFNDEF}
 *                   and {@code .ELSEIFNDEF} do.
 * @param name       The flag name, upper-cased.
 * @param comparison The comparison, or empty for a test of the flag alone.
 */
public record Condition(boolean negated, String name, Optional<Comparison> comparison) {

    // The operators, the two-character ones first so that the first match is the longest.
    private static final Pattern HEAD = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)(?:\\s*(<=|>=|<>|==|!=|=|<|>)\\s*(\\S+))?");

    /**
     * Upper-cases the name.
     */
    public Condition {
        name = name.toUpperCase(Locale.ROOT);
    }

    /**
     * A comparison operator.
     */
    public enum Operator {
        /** {@code =}, also written {@code ==}: the values are equal. */
        EQ("=", "=="),
        /** {@code <>}, also written {@code !=}: the values differ. */
        NE("<>", "!="),
        /** {@code <}: the flag's value is less. */
        LT("<"),
        /** {@code <=}: the flag's value is less or equal. */
        LE("<="),
        /** {@code >}: the flag's value is greater. */
        GT(">"),
        /** {@code >=}: the flag's value is greater or equal. */
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
         * Compares a flag's value with the value on the right of the operator.
         *
         * @param left  The flag's value.
         * @param right The value it is compared with.
         * @return {@code true} if the comparison holds.
         */
        boolean holds(int left, int right) {
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
     * The right-hand side of a comparison.
     */
    public sealed interface Operand permits Literal, FlagName {
    }

    /**
     * An integer written in the condition.
     *
     * @param value The integer.
     */
    public record Literal(int value) implements Operand {
    }

    /**
     * The name of a flag whose value is compared.
     *
     * @param name The flag name, upper-cased.
     */
    public record FlagName(String name) implements Operand {
        /**
         * Upper-cases the name.
         */
        public FlagName {
            name = name.toUpperCase(Locale.ROOT);
        }
    }

    /**
     * A comparison of the flag's value with an operand.
     *
     * @param operator The operator.
     * @param operand  The right-hand side.
     */
    public record Comparison(Operator operator, Operand operand) {
    }

    /**
     * A condition that cannot be read, or cannot be evaluated against the flags as they are.
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
     * Reads a condition from the text that follows the directive on its line, as the dependency
     * scan sees it.
     *
     * @param directive The directive word, which decides whether the condition is negated.
     * @param text      The rest of the line.
     * @return The condition, or empty if the text is no condition the directive takes.
     */
    public static Optional<Condition> parse(String directive, String text) {
        Matcher head = HEAD.matcher(text.trim());
        if (!head.matches()) {
            return Optional.empty();
        }
        boolean negated = negates(directive);
        if (head.group(2) == null) {
            return Optional.of(new Condition(negated, head.group(1), Optional.empty()));
        }
        if (negated) {
            return Optional.empty();
        }
        Operator operator = Operator.of(head.group(2)).orElseThrow();
        String operandText = head.group(3);
        Operand operand;
        if (Flags.isName(operandText)) {
            operand = new FlagName(operandText);
        } else {
            OptionalInt value = IntegerLiteral.parse(operandText);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            operand = new Literal(value.getAsInt());
        }
        return Optional.of(new Condition(false, head.group(1), Optional.of(new Comparison(operator, operand))));
    }

    /**
     * Reads a condition from the tokens that follow the directive up to the end of its line, as
     * the preprocessor sees them.
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
        Token name = tokens.get(0);
        if (!isNameToken(name)) {
            throw new Invalid(word + " needs a flag name, found '" + name.text() + "'");
        }
        if (tokens.size() == 1) {
            return new Condition(negates(word), name.text(), Optional.empty());
        }
        if (negates(word)) {
            throw new Invalid(word + " takes no comparison; compare with .IFDEF or .ELSEIFDEF and use .ELSEDEF"
                    + " for the opposite");
        }
        Token symbol = tokens.get(1);
        Optional<Operator> operator = symbol.type() == TokenType.SYMBOL ? Operator.of(symbol.text()) : Optional.empty();
        if (operator.isEmpty()) {
            throw new Invalid("Expected a comparison (=, ==, <>, !=, <, <=, >, >=) after the flag name of " + word
                    + ", found '" + symbol.text() + "'");
        }
        if (tokens.size() == 2) {
            throw new Invalid("Expected an integer or a flag name after '" + symbol.text() + "' in " + word);
        }
        Token operandToken = tokens.get(2);
        Operand operand;
        if (operandToken.type() == TokenType.NUMBER && operandToken.value() instanceof Integer value) {
            operand = new Literal(value);
        } else if (isNameToken(operandToken)) {
            operand = new FlagName(operandToken.text());
        } else {
            throw new Invalid("Expected an integer or a flag name after '" + symbol.text() + "' in " + word
                    + ", found '" + operandToken.text() + "'");
        }
        if (tokens.size() > 3) {
            throw new Invalid("Unexpected '" + tokens.get(3).text() + "' after the condition of " + word);
        }
        return new Condition(false, name.text(), Optional.of(new Comparison(operator.get(), operand)));
    }

    /**
     * Evaluates the condition against the flags as they are.
     *
     * @param flags The flags.
     * @return {@code true} if the condition holds.
     * @throws Invalid if a comparison cannot be made: the flag is set without a value, or the
     *                 operand names a flag that is not set or has no value.
     */
    public boolean holds(Flags flags) throws Invalid {
        if (negated) {
            return !flags.isSet(name);
        }
        if (!flags.isSet(name)) {
            return false;
        }
        if (comparison.isEmpty()) {
            return true;
        }
        OptionalInt left = flags.valueOf(name);
        if (left.isEmpty()) {
            throw new Invalid("Flag " + name + " is set without a value and cannot be compared");
        }
        int right = switch (comparison.get().operand()) {
            case Literal literal -> literal.value();
            case FlagName other -> {
                if (!flags.isSet(other.name())) {
                    throw new Invalid("Flag " + other.name() + ", which " + name + " is compared with, is not set");
                }
                OptionalInt value = flags.valueOf(other.name());
                if (value.isEmpty()) {
                    throw new Invalid("Flag " + other.name() + ", which " + name
                            + " is compared with, is set without a value");
                }
                yield value.getAsInt();
            }
        };
        return comparison.get().operator().holds(left.getAsInt(), right);
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
