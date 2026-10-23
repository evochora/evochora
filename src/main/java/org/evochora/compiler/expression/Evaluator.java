package org.evochora.compiler.expression;

import org.evochora.compiler.model.expression.Expression;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.LiteralExpression;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.expression.OperationExpression;
import org.evochora.compiler.model.expression.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Computes a computation as far as the phase that evaluates can: tree in, one of four results
 * out (see {@link EvaluationResult}). A literal is its value; a name is what the caller's
 * {@link INameResolution} answers; an operation is computed with the overload whose signature
 * fits the kinds of its operands, the kind of an operand without a value taken from the name's
 * answer or from the signature of the operation that produced it.
 * <p>
 * The operands are evaluated left to right, and the first that fails decides the result. An
 * operation whose operands have values is computed; one with an operand that has none, or that
 * answers "not yet", stays in the tree for a later phase, its computed operands replaced by their
 * literals.
 */
public final class Evaluator {

    private final OperationRegistry operations;

    /**
     * Creates an evaluator over the operations of a compilation.
     *
     * @param operations The registry the features filled.
     */
    public Evaluator(OperationRegistry operations) {
        this.operations = operations;
    }

    /**
     * Evaluates a computation.
     *
     * @param expression The computation.
     * @param names      What the names stand for, supplied by the phase that evaluates.
     * @param context    What the phase knows of the layout.
     * @return The value, the smaller tree a later phase computes, the error to report, or
     *         {@link EvaluationResult.Reported} if the error stands at a definition.
     */
    public EvaluationResult evaluate(Expression expression, INameResolution names, IEvaluationContext context) {
        return switch (expression) {
            case LiteralExpression literal -> new EvaluationResult.Value(literal.value());
            case NameExpression name -> answer(name, names.resolve(name));
            case OperationExpression operation -> apply(operation, names, context);
        };
    }

    private static EvaluationResult answer(NameExpression name, NameAnswer answer) {
        return switch (answer) {
            case NameAnswer.Value value -> new EvaluationResult.Value(value.value());
            case NameAnswer.Later later -> new EvaluationResult.Partial(
                    new NameExpression(later.qualifiedName(), name.sourceInfo(), name.end()), later.kind());
            case NameAnswer.Reported reported -> new EvaluationResult.Reported();
            case NameAnswer.NotUsable notUsable -> new EvaluationResult.Error(notUsable.explanation(), name.sourceInfo());
        };
    }

    private EvaluationResult apply(OperationExpression expression, INameResolution names, IEvaluationContext context) {
        Optional<IOperation> found = operationFor(expression);
        if (found.isEmpty()) {
            return new EvaluationResult.Error("Unknown operation '" + expression.operation() + "'.", expression.sourceInfo());
        }
        IOperation operation = found.get();

        List<EvaluationResult> results = new ArrayList<>();
        List<Kind> kinds = new ArrayList<>();
        boolean partial = false;
        for (Expression operand : expression.operands()) {
            EvaluationResult result = evaluate(operand, names, context);
            switch (result) {
                case EvaluationResult.Value value -> kinds.add(value.value().kind());
                case EvaluationResult.Partial rest -> {
                    kinds.add(rest.kind());
                    partial = true;
                }
                case EvaluationResult.Error error -> {
                    return error;
                }
                case EvaluationResult.Reported reported -> {
                    return reported;
                }
            }
            results.add(result);
        }

        Optional<Signature> signature = operation.signatures().stream().filter(s -> s.accepts(kinds)).findFirst();
        if (signature.isEmpty()) {
            return mismatch(expression, operation, kinds);
        }
        Kind kind = signature.get().result();
        if (partial) {
            return remaining(expression, results, kind);
        }

        List<Value> values = results.stream().map(result -> ((EvaluationResult.Value) result).value()).toList();
        return switch (operation.compute(values, context)) {
            case OperationResult.Value value -> {
                if (value.value().kind() != kind) {
                    throw new IllegalStateException("Operation '" + operation.name() + "' gave a "
                            + lower(value.value().kind()) + " where its signature promises a " + lower(kind) + ".");
                }
                yield new EvaluationResult.Value(value.value());
            }
            case OperationResult.NotYet notYet -> remaining(expression, results, kind);
            case OperationResult.Error error -> new EvaluationResult.Error(error.reason(), expression.sourceInfo());
        };
    }

    /**
     * Finds the operation an expression applies. A function is found by its name alone; an
     * operator by its name and the notation its number of operands allows: one operand a prefix,
     * two an infix or a chain, more a chain. The registry keeps a name from being a function and
     * an operator at once, and an operator from being an infix and a chain at once.
     */
    private Optional<IOperation> operationFor(OperationExpression expression) {
        String name = expression.operation();
        Optional<IOperation> function = operations.find(name, Notation.FUNCTION);
        if (function.isPresent()) {
            return function;
        }
        return switch (expression.operands().size()) {
            case 0 -> Optional.empty();
            case 1 -> operations.find(name, Notation.PREFIX);
            case 2 -> operations.find(name, Notation.INFIX).or(() -> operations.find(name, Notation.CHAIN));
            default -> operations.find(name, Notation.CHAIN);
        };
    }

    /**
     * The tree that stays for a later phase: the operation with every operand that has a value
     * replaced by its literal.
     */
    private static EvaluationResult remaining(OperationExpression expression, List<EvaluationResult> results, Kind kind) {
        List<Expression> operands = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            Expression original = expression.operands().get(i);
            operands.add(switch (results.get(i)) {
                case EvaluationResult.Value value -> new LiteralExpression(value.value(), original.sourceInfo(), original.end());
                case EvaluationResult.Partial rest -> rest.expression();
                case EvaluationResult.Error error -> throw new IllegalStateException("An operand that failed stays in no tree.");
                case EvaluationResult.Reported reported -> throw new IllegalStateException("An operand that failed stays in no tree.");
            });
        }
        return new EvaluationResult.Partial(
                new OperationExpression(expression.operation(), expression.function(), operands, expression.sourceInfo(), expression.end()), kind);
    }

    /**
     * The error for operands no signature fits. A part of a chain that does not fit is named at
     * the part: a part of the vector {@code a|b|c} by its text and the kind it is, a part of any
     * other chain as the kind the chain expects before or after its operator. Any other operation
     * names the kinds it was given.
     */
    private static EvaluationResult mismatch(OperationExpression expression, IOperation operation, List<Kind> kinds) {
        if (operation.notation() == Notation.CHAIN) {
            for (int i = 0; i < kinds.size(); i++) {
                int index = i;
                List<Kind> expected = operation.signatures().stream()
                        .map(signature -> signature.parameterAt(index))
                        .filter(kind -> kind != null)
                        .toList();
                if (!expected.isEmpty() && !expected.contains(kinds.get(i))) {
                    if (OperationExpression.VECTOR.equals(operation.name())) {
                        return new EvaluationResult.Error("Invalid vector component: '" + expression.operands().get(i).text()
                                + "' is " + described(kinds.get(i)) + ".", expression.operands().get(i).sourceInfo());
                    }
                    return new EvaluationResult.Error("Expected a " + lower(expected.getFirst())
                            + (i == 0 ? " before '" : " after '") + operation.name() + "'.",
                            expression.operands().get(i).sourceInfo());
                }
            }
        }
        return new EvaluationResult.Error(invalidOperands(operation.name(), kinds), expression.sourceInfo());
    }

    /**
     * Names a kind as the specification names the kinds of value, with its article.
     */
    private static String described(Kind kind) {
        return switch (kind) {
            case NUMBER -> "a number";
            case TYPED -> "a typed value";
            case VECTOR -> "a vector";
            case TYPE -> "a molecule type";
            case POSITION -> "a position";
        };
    }

    /**
     * Words the error for operands no signature of an operation fits, naming their kinds:
     * {@code Invalid operands for '*': vector and vector.}
     *
     * @param name  The name of the operation.
     * @param kinds The kinds of the operands.
     * @return The sentence.
     */
    static String invalidOperands(String name, List<Kind> kinds) {
        List<String> words = kinds.stream().map(Evaluator::lower).toList();
        String listed = words.size() <= 1
                ? String.join("", words)
                : String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.getLast();
        return "Invalid operand" + (kinds.size() == 1 ? "" : "s") + " for '" + name + "': " + listed + ".";
    }

    private static String lower(Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
