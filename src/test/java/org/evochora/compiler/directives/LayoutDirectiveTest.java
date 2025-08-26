package org.evochora.compiler.directives;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;
import org.evochora.compiler.frontend.parser.features.dir.DirNode;
import org.evochora.compiler.frontend.parser.features.org.OrgNode;
import org.evochora.compiler.frontend.parser.features.place.PlaceNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class LayoutDirectiveTest {
    @Test
    @Tag("unit")
    void testOrgDirective() {
        // Arrange
        String source = ".ORG 10|20";
        Parser parser = new Parser(new Lexer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine(), Path.of("")); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(OrgNode.class);
    }

    @Test
    @Tag("unit")
    void testDirDirective() {
        // Arrange
        String source = ".DIR 1|0";
        Parser parser = new Parser(new Lexer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine(), Path.of("")); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(DirNode.class);
    }

    @Test
    @Tag("unit")
    void testPlaceDirective() {
        // Arrange
        String source = ".PLACE DATA:100 5|-5";
        Parser parser = new Parser(new Lexer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine(), Path.of("")); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(PlaceNode.class);

        PlaceNode placeNode = (PlaceNode) ast.get(0);
        assertThat(placeNode.literal()).isInstanceOf(TypedLiteralNode.class);
        assertThat(placeNode.position()).isInstanceOf(VectorLiteralNode.class);
    }
}
