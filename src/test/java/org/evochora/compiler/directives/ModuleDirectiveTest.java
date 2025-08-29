package org.evochora.compiler.directives;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.features.require.RequireNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the parsing of module-related directives, specifically `.REQUIRE`.
 * These are unit tests for the parser and do not require external resources.
 */
public class ModuleDirectiveTest {
    /**
     * Verifies that the parser correctly parses a `.REQUIRE` directive into a {@link RequireNode}.
     * The test checks that the file path and the alias are correctly captured in the AST node.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testRequireDirective() {
        // Arrange
        String source = ".REQUIRE \"lib/math.s\" AS math";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics, Path.of("")); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(RequireNode.class);

        RequireNode requireNode = (RequireNode) ast.get(0);
        assertThat(requireNode.path().value()).isEqualTo("lib/math.s");
        assertThat(requireNode.alias().text()).isEqualTo("math");
    }
}
