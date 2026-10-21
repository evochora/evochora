package org.evochora.compiler.expression;

import org.evochora.compiler.FeatureRegistry;
import org.evochora.compiler.StandardFeatures;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.expression.parser.ExpressionParser;
import org.evochora.compiler.expression.parser.ParseResult;
import org.evochora.compiler.frontend.lexer.Tokenizer;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.model.expression.Expression;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.LiteralExpression;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.expression.OperationExpression;
import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.token.Token;
import org.evochora.runtime.isa.Instruction;

import java.util.List;
import java.util.Map;

/**
 * Reads and evaluates computations written as text, with the operations and lexer symbols of the
 * standard features and, if a test asks for it, a function {@code MIN} of its own.
 */
public final class ExpressionFixture {

    private final IInstructionSet isa;
    private final FeatureRegistry registry;

    private ExpressionFixture(boolean withMin) {
        Instruction.init();
        isa = new RuntimeInstructionSetAdapter();
        registry = new FeatureRegistry(isa);
        StandardFeatures.all().forEach(feature -> feature.register(registry));
        if (withMin) {
            registry.operation(min());
        }
    }

    /**
     * A fixture with the standard operations.
     *
     * @return The fixture.
     */
    public static ExpressionFixture standard() {
        return new ExpressionFixture(false);
    }

    /**
     * A fixture with the standard operations and the function {@code MIN} of two numbers.
     *
     * @return The fixture.
     */
    public static ExpressionFixture withMin() {
        return new ExpressionFixture(true);
    }

    /**
     * The function {@code MIN}: the smaller of two numbers.
     *
     * @return The operation.
     */
    public static IOperation min() {
        return new IOperation() {
            @Override public String name() { return "MIN"; }
            @Override public Notation notation() { return Notation.FUNCTION; }
            @Override public Level level() { return Level.INDEX; }
            @Override public List<Signature> signatures() {
                return List.of(Signature.of(Kind.NUMBER,
                        Kind.NUMBER, Kind.NUMBER));
            }
            @Override public OperationResult compute(List<Value> operands, IEvaluationContext context) {
                long a = ((Value.Number) operands.get(0)).value();
                long b = ((Value.Number) operands.get(1)).value();
                return new OperationResult.Value(new Value.Number(Math.min(a, b)));
            }
        };
    }

    /**
     * The operations of the fixture.
     *
     * @return The registry.
     */
    public OperationRegistry operations() {
        return registry.operations();
    }

    /**
     * Tokenizes a text as the lexer does.
     *
     * @param source The text.
     * @return The tokens, ending with an end-of-file token.
     */
    public List<Token> tokens(String source) {
        return new Tokenizer(source, new DiagnosticsEngine(), "main.evo", isa, registry.lexerSymbols()).scanTokens();
    }

    /**
     * Parses a text.
     *
     * @param source The text.
     * @return What the parser made of it.
     */
    public ParseResult parse(String source) {
        return new ExpressionParser(registry.operations(), isa).parse(tokens(source));
    }

    /**
     * Parses a text that has to be a computation.
     *
     * @param source The text.
     * @return The tree.
     */
    public Expression expression(String source) {
        ParseResult result = parse(source);
        if (result instanceof ParseResult.Parsed parsed) {
            return parsed.expression();
        }
        throw new AssertionError("Not a computation: " + source + ": " + result);
    }

    /**
     * Parses and evaluates a text.
     *
     * @param source  The text.
     * @param names   The answers for the names; a name without one is not defined.
     * @param context What is known of the layout.
     * @return The result.
     */
    public EvaluationResult evaluate(String source, Map<String, NameAnswer> names, IEvaluationContext context) {
        INameResolution resolution = name -> INameResolution.cursorLater(name).orElseGet(
                () -> names.getOrDefault(name.name(), new NameAnswer.NotUsable("'" + name.name() + "' is not defined.")));
        return new Evaluator(registry.operations()).evaluate(expression(source), resolution, context);
    }

    /**
     * Parses and evaluates a text in the frontend's context.
     *
     * @param source The text.
     * @param names  The answers for the names.
     * @return The result.
     */
    public EvaluationResult evaluate(String source, Map<String, NameAnswer> names) {
        return evaluate(source, names, IEvaluationContext.none());
    }

    /**
     * Parses and evaluates a text without names, in the frontend's context.
     *
     * @param source The text.
     * @return The result.
     */
    public EvaluationResult evaluate(String source) {
        return evaluate(source, Map.of());
    }

    /**
     * Writes a tree in prefix form, without positions, for comparisons: a literal as its text, a
     * name as written, an operation as {@code (op operand…)}.
     *
     * @param expression The tree.
     * @return The text.
     */
    public static String shape(Expression expression) {
        return switch (expression) {
            case LiteralExpression literal -> literal.value() instanceof Value.Type type ? "type " + type.name() : literal.value().text();
            case NameExpression name -> name.name();
            case OperationExpression operation -> "(" + operation.operation()
                    + operation.operands().stream().map(operand -> " " + shape(operand)).reduce("", String::concat) + ")";
        };
    }
}
