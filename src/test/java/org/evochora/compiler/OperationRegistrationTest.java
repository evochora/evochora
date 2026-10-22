package org.evochora.compiler;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.InternalCompilerException;
import org.evochora.compiler.expression.ExpressionFixture;
import org.evochora.compiler.expression.IOperation;
import org.evochora.compiler.expression.IEvaluationContext;
import org.evochora.compiler.expression.Level;
import org.evochora.compiler.expression.Notation;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.expression.OperationResult;
import org.evochora.compiler.expression.Signature;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.Value;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the operations of computations reach a compilation: a feature of its own adds a function
 * the compiler's registry then knows, the registry is checked against the preprocessor once every
 * feature has registered, and the names of the operations are reserved in the symbol table.
 */
@Tag("unit")
class OperationRegistrationTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /**
     * A feature that registers one operation and keeps the registry it registered into, so that
     * the test can look into the compiler's registry after the compilation.
     */
    private static ICompilerFeature registering(IOperation operation, AtomicReference<OperationRegistry> seen) {
        return new ICompilerFeature() {
            @Override
            public String name() {
                return "test-operation";
            }

            @Override
            public void register(IFeatureRegistrationContext ctx) {
                ctx.operation(operation);
                seen.set(ctx.operations());
            }
        };
    }

    @Test
    void aFunctionOfAFeatureBecomesKnownToTheCompilation() throws Exception {
        AtomicReference<OperationRegistry> seen = new AtomicReference<>();
        TestRegistries.compilerWith(registering(ExpressionFixture.min(), seen)).compile(List.of("START:", "  NOP"), "main.evo",
                new EnvironmentProperties(new int[]{100, 100}, true));

        assertThat(seen.get().find("MIN", Notation.FUNCTION)).isPresent();
        assertThat(seen.get().find("+", Notation.INFIX)).isPresent();
        assertThat(seen.get().reservedNames()).containsEntry("MIN", "a function");
    }

    @Test
    void anOperationNamedLikeAPreprocessorDirectiveStopsTheCompiler() {
        IOperation caret = new IOperation() {
            @Override public String name() { return "^"; }
            @Override public Notation notation() { return Notation.INFIX; }
            @Override public Level level() { return Level.UNORDERED; }
            @Override public List<Signature> signatures() { return List.of(Signature.of(Kind.NUMBER, Kind.NUMBER, Kind.NUMBER)); }
            @Override public OperationResult compute(List<Value> operands, IEvaluationContext context) {
                return new OperationResult.Value(operands.get(0));
            }
        };
        Compiler compiler = TestRegistries.compilerWith(registering(caret, new AtomicReference<>()));

        assertThatThrownBy(() -> compiler.compile(List.of("START:", "  NOP"), "main.evo"))
                .isInstanceOf(InternalCompilerException.class)
                .hasMessageContaining("Operation '^' is a preprocessor directive.");
    }

    @Test
    void theNameOfAnOperationCannotBeDefined() {
        Compiler compiler = TestRegistries.compilerWith(registering(ExpressionFixture.min(), new AtomicReference<>()));

        assertThatThrownBy(() -> compiler.compile(List.of("# A comment line.", ".CONST MIN DATA:1", "START:", "  NOP"), "main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: 'MIN' is a function and cannot be defined.");
    }
}
