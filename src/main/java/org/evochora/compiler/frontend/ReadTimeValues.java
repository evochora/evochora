package org.evochora.compiler.frontend;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.expression.EvaluationResult;
import org.evochora.compiler.expression.Evaluator;
import org.evochora.compiler.expression.IEvaluationContext;
import org.evochora.compiler.expression.INameResolution;
import org.evochora.compiler.expression.NameAnswer;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.expression.parser.Computation;
import org.evochora.compiler.expression.parser.ExpressionParser;
import org.evochora.compiler.expression.parser.ParseResult;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.expression.Expression;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.token.Token;

import java.util.List;
import java.util.OptionalLong;

/**
 * Computes the numbers a directive writes at read time, in the Lexer phase and in the
 * preprocessor, so that both phases read a value alike: the tokens are read with the
 * {@link ExpressionParser} and computed with the {@link Evaluator}, where a name is a flag of
 * the phase's {@link Flags}. A flag set with a value is that number; a flag set without one, a
 * name that is not set and the cursor {@code @} have no value at read time. Nothing of the layout
 * is known, and the result has to be a number.
 * <p>
 * Each read-time phase has its own instance over its own flags; the phase decides whether an
 * error is reported.
 */
public final class ReadTimeValues {

    private final Flags flags;
    private final ExpressionParser parser;
    private final Computation computation;
    private final Evaluator evaluator;

    /**
     * Creates the reading over the flags of one read-time phase.
     *
     * @param flags      The flags of the phase.
     * @param operations The operations of the compilation.
     * @param isa        The instruction set, whose molecule types a computation recognises
     *                   before the typing colon.
     */
    public ReadTimeValues(Flags flags, OperationRegistry operations, IInstructionSet isa) {
        this.flags = flags;
        this.parser = new ExpressionParser(operations, isa);
        this.computation = new Computation(isa);
        this.evaluator = new Evaluator(operations);
    }

    /**
     * Returns the flags the names are read from.
     *
     * @return The flags of the phase.
     */
    public Flags flags() {
        return flags;
    }

    /**
     * What reading a value gave: the number, or the error with its position.
     */
    public sealed interface Reading permits Reading.Number, Reading.Failed {

        /**
         * The tokens compute to a number.
         *
         * @param value The number.
         * @param form  What the tokens are: a computation, a name or a number.
         */
        record Number(long value, Form form) implements Reading {
        }

        /**
         * What the tokens of a value are. A computation is what {@link Computation} recognises;
         * a name or a number written alone is not one.
         */
        enum Form {
            /** A computation. */
            COMPUTATION,
            /** A flag name written alone. */
            NAME,
            /** A number written alone, with its sign. */
            NUMBER
        }

        /**
         * The tokens form no computation, or one without a number.
         *
         * @param message One sentence, names quoted.
         * @param at      The position of the token or the node at fault; {@code null} only for
         *                an empty token list.
         */
        record Failed(String message, SourceInfo at) implements Reading {
        }
    }

    /**
     * Reads the tokens of one value as a number: a number, a flag name or a computation.
     *
     * @param tokens The tokens of the value, as the directive delimits them.
     * @return The number, or the error the phase may report.
     */
    public Reading read(List<Token> tokens) {
        return switch (parser.parse(tokens)) {
            case ParseResult.Failed failed -> new Reading.Failed(failed.message(), failed.at());
            case ParseResult.Parsed parsed -> switch (evaluator.evaluate(parsed.expression(), this::resolve,
                    IEvaluationContext.none())) {
                case EvaluationResult.Value value -> value.value() instanceof Value.Number number
                        ? new Reading.Number(number.value(), formOf(tokens, parsed.expression()))
                        : new Reading.Failed("Expected a number.", tokens.getFirst().source());
                case EvaluationResult.Error error -> new Reading.Failed(error.reason(), error.at());
                case EvaluationResult.Partial partial -> throw new IllegalStateException(
                        "A computation at read time left a part for a later phase, though no name answers 'later'.");
                case EvaluationResult.Reported reported -> throw new IllegalStateException(
                        "A computation at read time reported an error at a definition, though no name answers so.");
            };
        };
    }

    /**
     * Tells what the tokens of a value are: a computation when the forms of the language say so,
     * else a name or a number, whichever the tree the parser made of them is.
     */
    private Reading.Form formOf(List<Token> tokens, Expression expression) {
        if (computation.lengthAt(tokens, 0) == tokens.size()) {
            return Reading.Form.COMPUTATION;
        }
        return expression instanceof NameExpression ? Reading.Form.NAME : Reading.Form.NUMBER;
    }

    /**
     * Answers a name with the flag of that name.
     */
    private NameAnswer resolve(NameExpression name) {
        String text = name.name();
        if (INameResolution.CURSOR.equals(text) || !flags.isSet(text)) {
            return new NameAnswer.NotUsable("'" + text + "' is not a flag.");
        }
        OptionalLong value = flags.valueOf(text);
        return value.isPresent()
                ? new NameAnswer.Value(new Value.Number(value.getAsLong()))
                : new NameAnswer.NotUsable("'" + text + "' has no value.");
    }
}
