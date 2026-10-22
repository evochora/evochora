package org.evochora.compiler.features.importdir;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;
import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.expression.OperationExpression;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrLiteral;
import org.evochora.compiler.model.ir.IrValue;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The values of a module that are computations: a {@code USING VALUE} computed at the import
 * line in the importer's placement, a default computed at the requirement's line in the module's
 * placement, and a configured value of the main program. A value is known after the analysis; a
 * value only the layout computes travels in the IR as a tree, for a binding under the
 * requirement's own name.
 */
@Tag("integration")
class ModuleValueComputationTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @TempDir
    Path sourceRoot;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void aBoundComputationWithAValueIsTheModulesValue() throws Exception {
        write("walk.evo",
                ".REQUIRE VALUE STEPS",
                "  SETI %DR0 STEPS");
        write("main.evo",
                ".CONST A DATA:4",
                ".IMPORT \"walk.evo\" AS WALK USING VALUE STEPS (A + 1)",
                "START:",
                "  NOP");

        List<IrItem> ir = new ArrayList<>();
        ProgramArtifact artifact = compile(ir, Map.of());

        assertThat(operandOf(ir, "SETI", 1)).isEqualTo("DATA:5");
        assertThat(artifact.constantValues()).containsEntry("WALK.STEPS", "DATA:5");
        assertThat(directives(ir, "bound_value")).isEmpty();
    }

    /**
     * A bound computation only the layout computes stays at the import line, under the name of
     * the requirement it binds; the requirement names itself as its value, and a use of it in the
     * module is that name.
     */
    @Test
    void aBoundComputationOnlyTheLayoutComputesStaysAtTheImportLine() throws Exception {
        write("walk.evo",
                ".REQUIRE VALUE P",
                ".CONST Q (P + (0|1))",
                "  NOP");
        write("main.evo",
                ".IMPORT \"walk.evo\" AS WALK USING VALUE P (@ + (0|2))",
                "START:",
                "  NOP");

        List<IrItem> ir = new ArrayList<>();
        compile(ir, Map.of());

        IrDirective bound = directives(ir, "bound_value").getFirst();
        assertThat(bound.args().get("name")).isEqualTo(new IrValue.Str("WALK.P"));
        assertThat(bound.args().get("value")).isInstanceOfSatisfying(IrValue.Expression.class, tree ->
                assertThat(tree.expression()).isInstanceOf(OperationExpression.class));
        assertThat(bound.source().fileName()).endsWith("main.evo");
        assertThat(bound.source().lineNumber()).isEqualTo(1);

        IrDirective value = valueOf(ir, "WALK.P");
        assertThat(value.args().get("value")).isInstanceOfSatisfying(IrValue.Expression.class, tree ->
                assertThat(tree.expression()).isInstanceOfSatisfying(NameExpression.class, name ->
                        assertThat(name.name()).isEqualTo("WALK.P")));
        assertThat(constValueOf(ir, "WALK.Q").args().get("value")).isInstanceOfSatisfying(IrValue.Expression.class,
                tree -> assertThat(tree.expression().text()).contains("WALK.P"));
    }

    @Test
    void anErrorInABoundComputationIsReportedAtTheImportLine() throws Exception {
        write("walk.evo",
                ".REQUIRE VALUE STEPS",
                "  SETI %DR0 STEPS");
        write("main.evo",
                ".CONST A DATA:4",
                ".IMPORT \"walk.evo\" AS WALK USING VALUE STEPS (A / 0)",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile(new ArrayList<>(), Map.of()))
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("main.evo") + ":2: Division by zero."));
    }

    /**
     * A default is computed in the module's placement: its names are the module's.
     */
    @Test
    void aDefaultComputationWithAValueIsTheModulesValue() throws Exception {
        write("walk.evo",
                ".CONST A DATA:4",
                ".REQUIRE VALUE STEPS (A + 1)",
                "  SETI %DR0 STEPS");
        write("main.evo",
                ".IMPORT \"walk.evo\" AS WALK",
                "START:",
                "  NOP");

        List<IrItem> ir = new ArrayList<>();
        ProgramArtifact artifact = compile(ir, Map.of());

        assertThat(operandOf(ir, "SETI", 1)).isEqualTo("DATA:5");
        assertThat(artifact.constantValues()).containsEntry("WALK.STEPS", "DATA:5");
    }

    @Test
    void aDefaultOnlyTheLayoutComputesCarriesItsTree() throws Exception {
        write("walk.evo",
                ".REQUIRE VALUE X (@ + (0|2))",
                "  NOP");
        write("main.evo",
                ".IMPORT \"walk.evo\" AS WALK",
                "START:",
                "  NOP");

        List<IrItem> ir = new ArrayList<>();
        compile(ir, Map.of());

        assertThat(valueOf(ir, "WALK.X").args().get("value")).isInstanceOfSatisfying(IrValue.Expression.class, tree ->
                assertThat(tree.expression()).isInstanceOf(OperationExpression.class));
        assertThat(directives(ir, "bound_value")).isEmpty();
    }

    @Test
    void aConfiguredComputationIsComputedWithTheNamesOfTheMainProgram() throws Exception {
        write("main.evo",
                ".CONST WIDTH 100",
                ".REQUIRE VALUE X",
                "START:",
                "  NOP");

        ProgramArtifact artifact = compile(new ArrayList<>(), Map.of("X", "(WIDTH-1)"));

        assertThat(artifact.constantValues()).containsEntry("X", "99");
    }

    @Test
    void anErrorInAConfiguredComputationIsReportedAgainstTheConfiguration() throws Exception {
        write("main.evo",
                ".CONST A 1",
                ".REQUIRE VALUE X",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile(new ArrayList<>(), Map.of("X", "(A/0)")))
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] Configured value 'X' = (A/0): division by zero."));
    }

    /**
     * A binding that names a constant of the importer only the layout computes is that constant:
     * the requirement carries the constant's qualified name, and the import line computes nothing.
     */
    @Test
    void aBindingNamingALayoutValuedConstantIsThatConstant() throws Exception {
        write("walk.evo",
                ".REQUIRE VALUE P",
                "  NOP");
        write("main.evo",
                ".CONST HERE (@ + (0|1))",
                ".IMPORT \"walk.evo\" AS WALK USING VALUE P HERE",
                "START:",
                "  NOP");

        List<IrItem> ir = new ArrayList<>();
        compile(ir, Map.of());

        assertThat(valueOf(ir, "WALK.P").args().get("value")).isEqualTo(new IrValue.Str("HERE"));
        assertThat(directives(ir, "bound_value")).isEmpty();
    }

    private static String operandOf(List<IrItem> ir, String opcode, int index) {
        IrInstruction instruction = ir.stream()
                .filter(item -> item instanceof IrInstruction i && i.opcode().equals(opcode))
                .map(IrInstruction.class::cast)
                .findFirst().orElseThrow();
        return ((IrLiteral) instruction.operands().get(index)).value().text();
    }

    private static List<IrDirective> directives(List<IrItem> ir, String name) {
        return ir.stream()
                .filter(item -> item instanceof IrDirective d && d.namespace().equals("importdir") && d.name().equals(name))
                .map(IrDirective.class::cast)
                .toList();
    }

    private static IrDirective valueOf(List<IrItem> ir, String qualifiedName) {
        return directives(ir, "value").stream()
                .filter(d -> d.args().get("name").equals(new IrValue.Str(qualifiedName)))
                .findFirst().orElseThrow();
    }

    private static IrDirective constValueOf(List<IrItem> ir, String qualifiedName) {
        return ir.stream()
                .filter(item -> item instanceof IrDirective d && d.name().equals("const_value")
                        && d.args().get("name").equals(new IrValue.Str(qualifiedName)))
                .map(IrDirective.class::cast)
                .findFirst().orElseThrow();
    }

    /**
     * Compiles main.evo with the given configured values, and collects every IR item the emitter
     * hands its contributors.
     */
    private ProgramArtifact compile(List<IrItem> ir, Map<String, String> values) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)), Map.of(), values);
        ICompilerFeature collector = new ICompilerFeature() {
            @Override
            public String name() {
                return "test-ir-collector";
            }

            @Override
            public void register(IFeatureRegistrationContext ctx) {
                ctx.emissionContributor((item, context) -> ir.add(item));
            }
        };
        return TestRegistries.compilerWith(collector).compile("main.evo", ENV, options);
    }

    private String resolved(String fileName) {
        return sourceRoot.resolve(fileName).toString().replace('\\', '/');
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }
}
