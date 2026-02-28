package org.evochora.compiler.directives;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.model.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.importdir.ImportNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests parsing of the {@code .IMPORT} directive.
 */
public class ImportDirectiveTest {

    @BeforeAll
    static void setUp() {
        org.evochora.runtime.isa.Instruction.init();
    }

    @Test
    @Tag("unit")
    void parsesSimpleImport() {
        String source = ".IMPORT \"lib.evo\" AS LIB";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(ImportNode.class);

        ImportNode node = (ImportNode) ast.get(0);
        assertThat((String) node.path().value()).isEqualTo("lib.evo");
        assertThat(node.alias().text()).isEqualToIgnoringCase("LIB");
        assertThat(node.usings()).isEmpty();
    }

    @Test
    @Tag("unit")
    void parsesImportWithUsingClause() {
        String source = ".IMPORT \"lib.evo\" AS LIB USING DEP AS REQUIRED_DEP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(ImportNode.class);

        ImportNode node = (ImportNode) ast.get(0);
        assertThat((String) node.path().value()).isEqualTo("lib.evo");
        assertThat(node.alias().text()).isEqualToIgnoringCase("LIB");
        assertThat(node.usings()).hasSize(1);
        assertThat(node.usings().get(0).sourceAlias().text()).isEqualToIgnoringCase("DEP");
        assertThat(node.usings().get(0).targetAlias().text()).isEqualToIgnoringCase("REQUIRED_DEP");
    }

    @Test
    @Tag("unit")
    void parsesImportWithMultipleUsingClauses() {
        String source = ".IMPORT \"lib.evo\" AS LIB USING A AS X USING B AS Y";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);

        ImportNode node = (ImportNode) ast.get(0);
        assertThat(node.usings()).hasSize(2);
    }

    @Test
    @Tag("unit")
    void missingAsKeywordReportsError() {
        String source = ".IMPORT \"lib.evo\" LIB";
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
        String source = ".IMPORT AS LIB";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);
        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
    }
}
