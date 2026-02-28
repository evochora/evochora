package org.evochora.compiler.directives;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.model.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.require.RequireNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests parsing of the {@code .REQUIRE} directive.
 */
public class RequireDirectiveTest {

    @BeforeAll
    static void setUp() {
        org.evochora.runtime.isa.Instruction.init();
    }

    @Test
    @Tag("unit")
    void parsesSimpleRequire() {
        String source = ".REQUIRE \"dependency.evo\" AS DEP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(RequireNode.class);

        RequireNode node = (RequireNode) ast.get(0);
        assertThat((String) node.path().value()).isEqualTo("dependency.evo");
        assertThat(node.alias().text()).isEqualToIgnoringCase("DEP");
    }

    @Test
    @Tag("unit")
    void missingAsKeywordReportsError() {
        String source = ".REQUIRE \"dependency.evo\" DEP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);
        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
    }

    @Test
    @Tag("unit")
    void missingPathReportsError() {
        String source = ".REQUIRE AS DEP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);
        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
    }

    @Test
    @Tag("unit")
    void missingAliasReportsError() {
        String source = ".REQUIRE \"dependency.evo\" AS";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);
        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
    }

    @Test
    @Tag("unit")
    void requireNodeRetainsSourceFileName() {
        String source = ".REQUIRE \"math.evo\" AS MATH";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, "main.evo");
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(diagnostics.hasErrors()).isFalse();
        RequireNode node = (RequireNode) ast.get(0);
        assertThat(node.getSourceFileName()).isEqualTo("main.evo");
    }

    @Test
    @Tag("unit")
    void requireNodeChildrenAreEmpty() {
        String source = ".REQUIRE \"utils.evo\" AS UTILS";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(diagnostics.hasErrors()).isFalse();
        RequireNode node = (RequireNode) ast.get(0);
        assertThat(node.getChildren()).isEmpty();
    }
}
