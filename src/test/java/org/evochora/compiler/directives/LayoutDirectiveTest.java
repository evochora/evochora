package org.evochora.compiler.directives;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.features.dir.DirDirectiveHandler;
import org.evochora.compiler.features.org.OrgDirectiveHandler;
import org.evochora.compiler.features.place.PlaceDirectiveHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.model.ast.VectorLiteralNode;
import org.evochora.compiler.features.place.placement.VectorPlacementNode;
import org.evochora.compiler.features.dir.DirNode;
import org.evochora.compiler.features.org.OrgNode;
import org.evochora.compiler.features.place.PlaceNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

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
        Parser parser = new Parser(new Lexer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine(), registry());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(OrgNode.class);
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
        Parser parser = new Parser(new Lexer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine(), registry());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        OrgNode org = (OrgNode) ast.get(0);
        assertThat(org.relative()).containsExactly(false, true);
        assertThat(((VectorLiteralNode) org.originVector()).values()).containsExactly(0, 2);
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
        Parser parser = new Parser(new Lexer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine(), registry());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        OrgNode org = (OrgNode) ast.get(0);
        assertThat(org.relative()).containsExactly(true, true);
        assertThat(((VectorLiteralNode) org.originVector()).values()).containsExactly(-3, 2);
    }

    /**
     * Verifies that a marked component may not carry a sign of its own.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testOrgDirectiveRejectsASignAfterTheMarker() {
        // Arrange
        String source = ".ORG 0|@+-2";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, new DiagnosticsEngine()).scanTokens(), diagnostics, registry());

        // Act
        parser.parse();

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("carries no sign of its own");
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
        Parser parser = new Parser(new Lexer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine(), registry());

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
        Parser parser = new Parser(new Lexer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine(), registry());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        assertThat(((DirNode) ast.get(0)).mode()).isEqualTo(new DirNode.Mode.Rotation(true, 0, 1));
        assertThat(((DirNode) ast.get(1)).mode()).isEqualTo(new DirNode.Mode.Rotation(false, 1, 2));
        assertThat(((DirNode) ast.get(2)).mode()).isInstanceOf(DirNode.Mode.Absolute.class);
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
        Parser parser = new Parser(new Lexer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine(), registry());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(PlaceNode.class);

        PlaceNode placeNode = (PlaceNode) ast.get(0);
        assertThat(placeNode.literal()).isInstanceOf(TypedLiteralNode.class);
        assertThat(placeNode.placements()).hasSize(1);
        assertThat(placeNode.placements().get(0)).isInstanceOf(VectorPlacementNode.class);
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
