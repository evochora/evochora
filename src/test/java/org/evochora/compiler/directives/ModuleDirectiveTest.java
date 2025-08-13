package org.evochora.compiler.directives;

import org.evochora.compiler.core.phases.Lexer;
import org.evochora.compiler.core.phases.Parser;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ModuleDirectiveTest {
    @Test
    void testRequireDirective() {
        // Arrange
        String source = ".REQUIRE \"lib/math.s\" AS math";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics);

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(org.evochora.compiler.core.ast.RequireNode.class);

        org.evochora.compiler.core.ast.RequireNode requireNode = (org.evochora.compiler.core.ast.RequireNode) ast.get(0);
        assertThat(requireNode.path().value()).isEqualTo("lib/math.s");
        assertThat(requireNode.alias().text()).isEqualTo("math");
    }
}
