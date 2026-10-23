package org.evochora.compiler.directives;

import org.evochora.compiler.TestParsers;
import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.ast.LiteralNode;
import org.evochora.compiler.TestLexers;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.features.dir.DirDirectiveHandler;
import org.evochora.compiler.features.org.OrgDirectiveHandler;
import org.evochora.compiler.features.place.PlaceDirectiveHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.place.placement.PlacementNode;
import org.evochora.compiler.features.place.placement.RangeValueComponent;
import org.evochora.compiler.features.place.placement.SingleValueComponent;
import org.evochora.compiler.features.place.placement.SteppedRangeValueComponent;
import org.evochora.compiler.features.place.placement.WildcardValueComponent;
import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.features.dir.DirNode;
import org.evochora.compiler.features.org.OrgNode;
import org.evochora.compiler.features.place.PlaceNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the parsing of layout-related directives like `.ORG`, `.DIR`, and `.PLACE`.
 * These tests ensure that the parser correctly creates the corresponding AST nodes.
 * These are unit tests and do not require any external resources.
 */
public class LayoutDirectiveTest {
    /**
     * Verifies that the parser correctly parses an `.ORG` directive into an {@link OrgNode}.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testOrgDirective() {
        // Arrange
        String source = ".ORG 10|20";
        Parser parser = TestParsers.parser(TestLexers.tokenizer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine(), registry());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(OrgNode.class);
    }

    /**
     * An axis of a rotation names a dimension, which the layout checks against the world; here
     * it has to fit an {@code int}, so that the layout can.
     */
    @Test
    @Tag("unit")
    void testRotationAxisBeyondAnIntIsReported() {
        String source = ".DIR @+4294967296|1";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = TestParsers.parser(TestLexers.tokenizer(source, diagnostics).scanTokens(), diagnostics, registry());

        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message()).isEqualTo("Value 4294967296 out of range for an axis.");
    }

    /**
     * Verifies that each component of an `.ORG` vector carries its own mark, and that a marked
     * component keeps the sign of its marker.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testOrgDirectiveWithRelativeComponents() {
        // Arrange
        String source = ".ORG 0|@+2";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = TestParsers.parser(TestLexers.tokenizer(source, diagnostics).scanTokens(), diagnostics, registry());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        OrgNode org = (OrgNode) ast.get(0);
        assertThat(org.relative()).containsExactly(false, true);
        assertThat(numbers(org.components())).containsExactly(0L, 2L);
    }

    /**
     * Verifies that the marker's sign is applied to the component.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testOrgDirectiveWithBackwardMarker() {
        // Arrange
        String source = ".ORG @-3|@+2";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = TestParsers.parser(TestLexers.tokenizer(source, diagnostics).scanTokens(), diagnostics, registry());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        OrgNode org = (OrgNode) ast.get(0);
        assertThat(org.relative()).containsExactly(true, true);
        assertThat(org.backward()).containsExactly(true, false);
        assertThat(numbers(org.components())).containsExactly(3L, 2L);
    }

    /**
     * Verifies that a space between the marker and its sign is accepted, and that an unmarked
     * component may carry a sign of its own.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testOrgDirectiveAcceptsASpaceAfterTheMarkerAndASignedComponent() {
        // Arrange
        String source = ".ORG @ -3|-1|@ +2";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = TestParsers.parser(TestLexers.tokenizer(source, diagnostics).scanTokens(), diagnostics, registry());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        OrgNode org = (OrgNode) ast.get(0);
        assertThat(org.relative()).containsExactly(true, false, true);
        assertThat(org.backward()).containsExactly(true, false, false);
        assertThat(numbers(org.components())).containsExactly(3L, -1L, 2L);
    }

    /**
     * Verifies that a marked component may not carry a sign of its own.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testOrgDirectiveRejectsASignAfterTheMarker() {
        // Arrange
        String source = "# A comment line.\n.ORG 0|@+-2";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = TestParsers.parser(TestLexers.tokenizer(source, diagnostics).scanTokens(), diagnostics, registry());

        // Act
        parser.parse();

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("<memory>:2: A component marked with '@+' or '@-' carries no sign of its own.");
    }

    /**
     * Verifies that the parser correctly parses a `.DIR` directive into a {@link DirNode}.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testDirDirective() {
        // Arrange
        String source = ".DIR 1|0";
        Parser parser = TestParsers.parser(TestLexers.tokenizer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine(), registry());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(DirNode.class);
    }

    /**
     * Verifies that a marked `.DIR` is read as a rotation naming its plane, while the written-out
     * form stays a direction.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testDirDirectiveWithRotation() {
        // Arrange
        String source = ".DIR @+0|1\n.DIR @-1|2\n.DIR 1|0";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = TestParsers.parser(TestLexers.tokenizer(source, diagnostics).scanTokens(), diagnostics, registry());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        assertThat(((DirNode) ast.get(0)).mode()).isEqualTo(new DirNode.Mode.Rotation(true, 0, 1));
        assertThat(((DirNode) ast.get(1)).mode()).isEqualTo(new DirNode.Mode.Rotation(false, 1, 2));
        assertThat(((DirNode) ast.get(2)).mode()).isInstanceOf(DirNode.Mode.Absolute.class);
    }

    /**
     * Verifies that a `.DIR` which is neither a direction nor a rotation is reported, and that
     * no node is produced for it.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testDirDirectiveRejectsWhatIsNeitherDirectionNorRotation() {
        // Arrange
        String source = "# A comment line.\n.DIR %DR0";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = TestParsers.parser(TestLexers.tokenizer(source, diagnostics).scanTokens(), diagnostics, registry());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("<memory>:2: Expected a vector literal or a rotation after .DIR.");
        assertThat(ast).isEmpty();
    }

    /**
     * Verifies that the parser correctly parses a `.PLACE` directive into a {@link PlaceNode}.
     * It also checks that the literal and position components of the node are of the correct type.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testPlaceDirective() {
        // Arrange
        String source = ".PLACE DATA:100 5|-5";
        Parser parser = TestParsers.parser(TestLexers.tokenizer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine(), registry());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(PlaceNode.class);

        PlaceNode placeNode = (PlaceNode) ast.get(0);
        assertThat(placeNode.literal()).isInstanceOf(LiteralNode.class);
        assertThat(placeNode.placements()).hasSize(1);
        assertThat(placeNode.placements().get(0).components()).hasSize(2)
                .allMatch(component -> component instanceof SingleValueComponent);
    }

    /**
     * A range written in hexadecimal keeps its range symbol: the number ends before the dots.
     */
    @Test
    @Tag("unit")
    void testPlaceRangeInHexadecimal() {
        String source = ".PLACE DATA:1 0x1..0x3|0";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = TestParsers.parser(TestLexers.tokenizer(source, diagnostics).scanTokens(), diagnostics, registry());

        List<AstNode> ast = parser.parse();

        assertThat(diagnostics.hasErrors()).isFalse();
        PlaceNode placeNode = (PlaceNode) ast.get(0);
        RangeValueComponent range = (RangeValueComponent) placeNode.placements().get(0).components().get(0);
        assertThat(numbers(List.of(range.start(), range.end()))).containsExactly(1L, 3L);
    }

    /**
     * The values of operands that are written numbers.
     */
    private static List<Long> numbers(List<OperandNode> operands) {
        return operands.stream().map(operand -> ((Value.Number) ((LiteralNode) operand).value()).value()).toList();
    }

    /**
     * Parses a source with the operations of computations, as the compiler does.
     */
    private static List<AstNode> parse(String source, DiagnosticsEngine diagnostics) {
        return new Parser(TestLexers.tokenizer(source, diagnostics).scanTokens(), diagnostics, registry(), null, Map.of(),
                TestRegistries.operations(), new RuntimeInstructionSetAdapter()).parse();
    }

    /**
     * A name alone is one component, whose value the analysis checks: a vector or a position.
     */
    @Test
    @Tag("unit")
    void testOrgWithANameIsOneComponent() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = parse(".ORG BODY_NW", diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
        OrgNode org = (OrgNode) ast.get(0);
        assertThat(org.components()).singleElement().isInstanceOf(IdentifierNode.class);
        assertThat(org.relative()).containsExactly(false);
    }

    /**
     * A computation keeps its own separators: the {@code |} inside parentheses does not cut the
     * directive's line.
     */
    @Test
    @Tag("unit")
    void testOrgWithAComputationIsOneComponent() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = parse(".ORG (@ + (0|2))", diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
        OrgNode org = (OrgNode) ast.get(0);
        assertThat(org.components()).singleElement().isInstanceOf(ExpressionNode.class);
    }

    /**
     * A marked component may be any operand; the marker {@code @-} puts it under the sign, and
     * the computation ends right after the operand's last token.
     */
    @Test
    @Tag("unit")
    void testOrgMarksAnOperandOfAnyForm() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = parse(".ORG X|@-(ROW+1)", diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
        OrgNode org = (OrgNode) ast.get(0);
        assertThat(org.relative()).containsExactly(false, true);
        assertThat(org.backward()).containsExactly(false, true);
        assertThat(org.components().get(0)).isInstanceOf(IdentifierNode.class);
        ExpressionNode marked = (ExpressionNode) org.components().get(1);
        assertThat(marked.expression().text()).isEqualTo("(ROW+1)");
        assertThat(marked.end().columnNumber()).isEqualTo(17);
    }

    /**
     * A missing component and a piece that holds more than one operand are reported.
     */
    @Test
    @Tag("unit")
    void testOrgReportsAMissingAndAnOverlongComponent() {
        DiagnosticsEngine missing = new DiagnosticsEngine();
        assertThat(parse("# A comment line.\n.ORG 1|", missing)).isEmpty();
        assertThat(missing.summary()).contains("<memory>:2: Expected a number after .ORG.");

        DiagnosticsEngine overlong = new DiagnosticsEngine();
        assertThat(parse("# A comment line.\n.ORG 1 2|3", overlong)).isEmpty();
        assertThat(overlong.summary()).contains("<memory>:2: Unexpected '2'.");
    }

    /**
     * A name and a computation give a direction as one operand.
     */
    @Test
    @Tag("unit")
    void testDirTakesANameOrAComputation() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = parse(".DIR V\n.DIR (V * 2)", diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(((DirNode.Mode.Absolute) ((DirNode) ast.get(0)).mode()).vector()).isInstanceOf(IdentifierNode.class);
        assertThat(((DirNode.Mode.Absolute) ((DirNode) ast.get(1)).mode()).vector()).isInstanceOf(ExpressionNode.class);
    }

    /**
     * A placement is cut at its separators outside brackets: the bounds of a range may be
     * computations, and the {@code |} inside them belongs to them.
     */
    @Test
    @Tag("unit")
    void testPlaceCutsRangesOfComputations() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = parse(".PLACE DATA:1 (A+1)..(B-1)|0", diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
        PlacementNode placement = ((PlaceNode) ast.get(0)).placements().getFirst();
        assertThat(placement.components()).hasSize(2);
        RangeValueComponent range = (RangeValueComponent) placement.components().get(0);
        assertThat(range.start()).isInstanceOf(ExpressionNode.class);
        assertThat(range.end()).isInstanceOf(ExpressionNode.class);
        assertThat(placement.components().get(1)).isInstanceOf(SingleValueComponent.class);
    }

    /**
     * Placements are separated by commas; each may be a vector constant, a stepped range with
     * names or a wildcard.
     */
    @Test
    @Tag("unit")
    void testPlaceReadsEveryFormOfPlacement() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = parse(".PLACE DATA:1 CORNER, A:STEP:B|*, MID[0]|(@[1])", diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
        List<PlacementNode> placements = ((PlaceNode) ast.get(0)).placements();
        assertThat(placements).hasSize(3);
        assertThat(((SingleValueComponent) placements.get(0).components().getFirst()).value()).isInstanceOf(IdentifierNode.class);
        assertThat(placements.get(1).components().get(0)).isInstanceOf(SteppedRangeValueComponent.class);
        assertThat(placements.get(1).components().get(1)).isInstanceOf(WildcardValueComponent.class);
        assertThat(placements.get(2).components()).allMatch(component ->
                ((SingleValueComponent) component).value() instanceof ExpressionNode);
    }

    /**
     * A part missing from a range is reported, and the directive yields no node.
     */
    @Test
    @Tag("unit")
    void testPlaceReportsAMissingBound() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        assertThat(parse("# A comment line.\n.PLACE DATA:1 1..|0", diagnostics)).isEmpty();
        assertThat(diagnostics.summary()).contains("<memory>:2: Expected a number for the end of the range.");
    }

    private static ParserStatementRegistry registry() {
        ParserStatementRegistry reg = new ParserStatementRegistry();
        reg.register(".ORG", new OrgDirectiveHandler());
        reg.register(".DIR", new DirDirectiveHandler());
        reg.register(".PLACE", new PlaceDirectiveHandler());
        reg.registerDefault(new org.evochora.compiler.features.instruction.InstructionParsingHandler());
        return reg;
    }
}
