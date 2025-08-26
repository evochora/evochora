package org.evochora.compiler.directives;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.scope.ScopeNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

public class ScopeDirectiveTest {

    @Test
    @Tag("unit")
    void testSimpleScopeDirective() {
        // Arrange
        String source = String.join(System.lineSeparator(),
                ".SCOPE MY_SCOPE",
                "  NOP",
                ".ENDS"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics, Path.of("")); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(ScopeNode.class);
        ScopeNode scopeNode = (ScopeNode) ast.get(0);
        assertThat(scopeNode.name().text()).isEqualTo("MY_SCOPE");
        assertThat(scopeNode.body()).hasSize(1);
    }

    @Test
    @Tag("unit")
    void testUnclosedScopeReportsError() {
        // Arrange
        String source = String.join(System.lineSeparator(),
                ".SCOPE MY_SCOPE",
                "  NOP" // .ENDS fehlt
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics, Path.of("")); // KORREKTUR

        // Act
        parser.parse();

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("Expected .ENDS directive to close scope block.");
    }

    @Test
    @Tag("unit")
    void testNestedScopesAreParsedCorrectly() {
        // Arrange
        String source = String.join(System.lineSeparator(),
                ".SCOPE OUTER",
                " .SCOPE INNER",
                "  NOP",
                " .ENDS",
                ".ENDS"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics, Path.of("")); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(ScopeNode.class);
        ScopeNode outer = (ScopeNode) ast.get(0);
        assertThat(outer.name().text()).isEqualTo("OUTER");
        assertThat(outer.body()).hasSize(1).first().isInstanceOf(ScopeNode.class);
        ScopeNode inner = (ScopeNode) outer.body().get(0);
        assertThat(inner.name().text()).isEqualTo("INNER");
    }
}