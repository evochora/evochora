package org.evochora.compiler.features.control;

import org.evochora.compiler.FeatureRegistry;
import org.evochora.compiler.StandardFeatures;
import org.evochora.compiler.TestLexers;
import org.evochora.compiler.diagnostics.Diagnostic;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.label.LabelNode;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for how the parser reads a control block, {@code .CONTROL} … {@code .CASE} …
 * {@code .ENDCONTROL}, into a {@link ControlNode} with its {@link ControlCase}s. The parser is set
 * up with the parser handlers and block kinds of the standard features, as the compiler sets it up;
 * the sources are written as the preprocessor hands them on, with a label as {@code .LABEL L}.
 */
@Tag("unit")
class ControlDirectiveTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void aBlockWithHeadCasesAndANestedBlockParsesIntoTheNodeTree() {
        Parsed parsed = parse(
                ".CONTROL WALK",
                "  NOP",
                "  NOP",
                ".CASE A",
                "  NOP",
                "  .CONTROL INNER",
                "    NOP",
                "  .CASE X",
                "  .ENDCONTROL",
                ".CASE B",
                ".CASE C",
                "  NOP",
                ".ENDCONTROL",
                "NOP");

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.ast()).hasSize(2);
        assertThat(parsed.ast().get(1)).isInstanceOf(InstructionNode.class);

        ControlNode walk = (ControlNode) parsed.ast().getFirst();
        assertThat(walk.name()).isEqualTo("WALK");
        assertThat(walk.exported()).isFalse();
        assertThat(walk.sourceInfo().lineNumber()).isEqualTo(1);
        assertThat(walk.statements()).hasSize(2).allMatch(InstructionNode.class::isInstance);
        assertThat(walk.cases()).extracting(ControlCase::name).containsExactly("A", "B", "C");
        assertThat(walk.cases()).extracting(ControlCase::exported).containsExactly(false, false, false);
        assertThat(walk.cases()).extracting(c -> c.sourceInfo().lineNumber()).containsExactly(4, 10, 11);
        assertThat(walk.cases().get(1).statements()).isEmpty();
        assertThat(walk.cases().get(2).statements()).singleElement().isInstanceOf(InstructionNode.class);
        assertThat(walk.endExported()).isFalse();
        assertThat(walk.endSourceInfo().lineNumber()).isEqualTo(13);

        List<AstNode> caseA = walk.cases().getFirst().statements();
        assertThat(caseA).hasSize(2);
        assertThat(caseA.get(0)).isInstanceOf(InstructionNode.class);
        ControlNode inner = (ControlNode) caseA.get(1);
        assertThat(inner.name()).isEqualTo("INNER");
        assertThat(inner.statements()).singleElement().isInstanceOf(InstructionNode.class);
        assertThat(inner.cases()).extracting(ControlCase::name).containsExactly("X");
        assertThat(inner.cases().getFirst().statements()).isEmpty();
        assertThat(inner.endSourceInfo().lineNumber()).isEqualTo(9);
    }

    @Test
    void theChildrenOfABlockAreItsStatementsFollowedByItsCasesAndRebuildTheBlock() {
        Parsed parsed = parse(
                ".CONTROL WALK",
                "  NOP",
                ".CASE A",
                "  NOP",
                ".CASE B",
                ".ENDCONTROL");

        assertThat(parsed.errors()).isEmpty();
        ControlNode walk = (ControlNode) parsed.ast().getFirst();
        assertThat(walk.getChildren()).hasSize(3);
        assertThat(walk.getChildren().get(0)).isInstanceOf(InstructionNode.class);
        assertThat(walk.getChildren().subList(1, 3)).containsExactlyElementsOf(walk.cases());
        assertThat(walk.reconstructWithChildren(walk.getChildren())).isEqualTo(walk);

        ControlCase caseA = walk.cases().getFirst();
        assertThat(caseA.getChildren()).isEqualTo(caseA.statements());
        assertThat(caseA.reconstructWithChildren(caseA.getChildren())).isEqualTo(caseA);
    }

    @Test
    void exportLandsOnTheBlockTheCaseAndTheEnd() {
        Parsed parsed = parse(
                "EXPORT .CONTROL WALK",
                "EXPORT .CASE A",
                ".CASE B",
                "EXPORT .ENDCONTROL");

        assertThat(parsed.errors()).isEmpty();
        ControlNode walk = (ControlNode) parsed.ast().getFirst();
        assertThat(walk.exported()).isTrue();
        assertThat(walk.statements()).isEmpty();
        assertThat(walk.cases()).extracting(ControlCase::exported).containsExactly(true, false);
        assertThat(walk.endExported()).isTrue();
        assertThat(walk.endSourceInfo().lineNumber()).isEqualTo(4);
    }

    @Test
    void aMissingBlockNameIsReportedAndTheBlockIsSkipped() {
        Parsed parsed = parse(
                ".CONTROL",
                "  NOP",
                ".ENDCONTROL",
                "NOP");

        assertThat(parsed.errors()).containsExactly("<memory>:1: Expected block name after .CONTROL.");
        assertThat(parsed.ast()).singleElement().isInstanceOf(InstructionNode.class);
    }

    @Test
    void aWordAfterTheBlockNameIsReportedAndTheBlockIsSkipped() {
        Parsed parsed = parse(
                ".CONTROL WALK X",
                "  NOP",
                ".ENDCONTROL",
                "NOP");

        assertThat(parsed.errors()).containsExactly("<memory>:1: Expected newline after .CONTROL declaration.");
        assertThat(parsed.ast()).singleElement().isInstanceOf(InstructionNode.class);
    }

    @Test
    void aCaseWithoutANameIsReportedAndTheBlockIsSkipped() {
        Parsed parsed = parse(
                ".CONTROL WALK",
                ".CASE",
                "  NOP",
                ".ENDCONTROL",
                "NOP");

        assertThat(parsed.errors()).containsExactly("<memory>:2: Expected case name after .CASE.");
        assertThat(parsed.ast()).singleElement().isInstanceOf(InstructionNode.class);
    }

    @Test
    void aCaseWhoseOperandIsNoNameIsReportedAndTheBlockIsSkipped() {
        Parsed parsed = parse(
                ".CONTROL WALK",
                ".CASE 5",
                ".ENDCONTROL",
                "NOP");

        assertThat(parsed.errors()).containsExactly("<memory>:2: Expected case name after .CASE.");
        assertThat(parsed.ast()).singleElement().isInstanceOf(InstructionNode.class);
    }

    @Test
    void aWordAfterTheCaseNameIsReportedAndTheBlockIsSkipped() {
        Parsed parsed = parse(
                ".CONTROL WALK",
                ".CASE A B",
                ".ENDCONTROL",
                "NOP");

        assertThat(parsed.errors()).containsExactly("<memory>:2: Expected newline after .CASE A.");
        assertThat(parsed.ast()).singleElement().isInstanceOf(InstructionNode.class);
    }

    @Test
    void theEndOfAProcedureInsideAnOpenBlockIsReportedOnceWithBothPlaces() {
        Parsed parsed = parse(
                ".PROC P",
                "  .CONTROL WALK",
                ".ENDPROC",
                "NOP");

        assertThat(parsed.errors()).containsExactly(
                "<memory>:3: .ENDPROC closes the .PROC opened at <memory>:1, but the .CONTROL opened at <memory>:2 is still open");
        assertThat(parsed.ast()).singleElement().isInstanceOf(InstructionNode.class);
    }

    @Test
    void aCaseOrAnEndOutsideABlockIsReportedWhereItStands() {
        Parsed parsed = parse(
                "NOP",
                ".CASE A",
                ".ENDCONTROL",
                "NOP");

        assertThat(parsed.errors()).containsExactly(
                "<memory>:2: .CASE divides no open block",
                "<memory>:3: .ENDCONTROL closes no open block");
        assertThat(parsed.ast()).hasSize(2).allMatch(InstructionNode.class::isInstance);
    }

    @Test
    void aBlockOpenAtTheEndOfTheInputIsReportedAtItsOpener() {
        Parsed parsed = parse(
                "NOP",
                ".CONTROL WALK",
                "  NOP");

        assertThat(parsed.errors()).containsExactly(
                "<memory>:2: .CONTROL opened at <memory>:2 is not closed before the end of the input");
        assertThat(parsed.ast()).singleElement().isInstanceOf(InstructionNode.class);
    }

    @Test
    void aLabelAloneOnItsLineBeforeACaseOrTheEndLeavesThemInPlace() {
        Parsed parsed = parse(
                ".CONTROL WALK",
                ".LABEL L",
                ".CASE A",
                ".LABEL M",
                ".ENDCONTROL");

        assertThat(parsed.errors()).isEmpty();
        ControlNode walk = (ControlNode) parsed.ast().getFirst();
        assertThat(walk.statements()).singleElement()
                .isInstanceOfSatisfying(LabelNode.class, label -> assertThat(label.name()).isEqualTo("L"));
        assertThat(walk.cases()).extracting(ControlCase::name).containsExactly("A");
        assertThat(walk.cases().getFirst().statements()).singleElement()
                .isInstanceOfSatisfying(LabelNode.class, label -> assertThat(label.name()).isEqualTo("M"));
        assertThat(walk.endSourceInfo().lineNumber()).isEqualTo(5);
    }

    // --- helpers ---

    private record Parsed(List<AstNode> ast, DiagnosticsEngine diagnostics) {
        List<String> errors() {
            return diagnostics.getDiagnostics().stream()
                    .filter(d -> d.type() == Diagnostic.Type.ERROR)
                    .map(d -> d.fileName() + ":" + d.lineNumber() + ": " + d.message())
                    .toList();
        }
    }

    /**
     * Parses the lines with the parser handlers and block kinds of the standard features,
     * registered as the compiler registers them before the parser runs.
     */
    private static Parsed parse(String... lines) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<Token> tokens = new Lexer(String.join("\n", lines) + "\n", diagnostics, TestLexers.symbols()).scanTokens();
        FeatureRegistry features = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        StandardFeatures.all().forEach(f -> f.register(features));
        ParserStatementRegistry registry = new ParserStatementRegistry();
        features.parserStatementHandlers().forEach(registry::register);
        features.parserBlocks().forEach(block -> registry.registerBlock(block.kind(), block.handler()));
        registry.registerDefault(features.defaultParserStatementHandler());
        Parser parser = new Parser(tokens, diagnostics, registry);
        return new Parsed(parser.parse(), diagnostics);
    }
}
