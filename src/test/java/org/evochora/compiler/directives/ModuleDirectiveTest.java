package org.evochora.compiler.directives;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.features.require.RequireNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ModuleDirectiveTest {
    @Test
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
