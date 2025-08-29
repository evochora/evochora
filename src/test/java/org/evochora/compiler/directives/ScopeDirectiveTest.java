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

/**
 * Tests the parsing of scope blocks defined by `.SCOPE` and `.ENDS` directives.
 * These tests ensure the parser can handle simple, nested, and malformed scope blocks.
 * These are unit tests for the parser and do not require external resources.
 */
public class ScopeDirectiveTest {

    /**
     * Verifies that a simple, well-formed `.SCOPE`/`.ENDS` block is correctly parsed
     * into a {@link ScopeNode} with the correct name and body.
     * This is a unit test for the parser.
     */
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

    /**
     * Verifies that the parser reports an error if a `.SCOPE` block is not closed with `.ENDS`.
     * This is a unit test for the parser's error handling.
     */
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

    /**
     * Verifies that the parser can correctly handle nested `.SCOPE` blocks,
     * creating a corresponding nested structure of {@link ScopeNode}s in the AST.
     * This is a unit test for the parser.
     */
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