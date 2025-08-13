package org.evochora.compiler.directives;

import org.evochora.compiler.core.phases.Lexer;
import org.evochora.compiler.core.phases.Parser;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class LayoutDirectiveTest {
    @Test
    void testOrgDirective() {
        // Arrange
        String source = ".ORG 10|20";
        Parser parser = new Parser(new Lexer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(org.evochora.compiler.core.ast.OrgNode.class);
    }

    @Test
    void testDirDirective() {
        // Arrange
        String source = ".DIR 1|0";
        Parser parser = new Parser(new Lexer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(org.evochora.compiler.core.ast.DirNode.class);
    }

    @Test
    void testPlaceDirective() {
        // Arrange
        String source = ".PLACE DATA:100 5|-5";
        Parser parser = new Parser(new Lexer(source, new DiagnosticsEngine()).scanTokens(), new DiagnosticsEngine());

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(parser.getDiagnostics().hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(org.evochora.compiler.core.ast.PlaceNode.class);

        org.evochora.compiler.core.ast.PlaceNode placeNode = (org.evochora.compiler.core.ast.PlaceNode) ast.get(0);
        assertThat(placeNode.literal()).isInstanceOf(org.evochora.compiler.core.ast.TypedLiteralNode.class);
        assertThat(placeNode.position()).isInstanceOf(org.evochora.compiler.core.ast.VectorLiteralNode.class);
    }
}
