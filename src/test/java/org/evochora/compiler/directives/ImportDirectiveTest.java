package org.evochora.compiler.directives;

import org.evochora.compiler.TestLexers;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.features.importdir.ImportDirectiveHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.features.importdir.ImportNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(ImportNode.class);

        ImportNode node = (ImportNode) ast.get(0);
        assertThat(node.path()).isEqualTo("lib.evo");
        assertThat(node.alias()).isEqualToIgnoringCase("LIB");
        assertThat(node.usings()).isEmpty();
    }

    @Test
    @Tag("unit")
    void parsesImportWithUsingClause() {
        String source = ".IMPORT \"lib.evo\" AS LIB USING MODULE DEP AS REQUIRED_DEP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(ImportNode.class);

        ImportNode node = (ImportNode) ast.get(0);
        assertThat(node.path()).isEqualTo("lib.evo");
        assertThat(node.alias()).isEqualToIgnoringCase("LIB");
        assertThat(node.usings()).hasSize(1);
        assertThat(node.usings().get(0).sourceAlias()).isEqualToIgnoringCase("DEP");
        assertThat(node.usings().get(0).targetAlias()).isEqualToIgnoringCase("REQUIRED_DEP");
    }

    @Test
    @Tag("unit")
    void parsesImportWithMultipleUsingClauses() {
        String source = ".IMPORT \"lib.evo\" AS LIB USING MODULE A AS X MODULE B AS Y";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

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
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());
        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
    }

    @Test
    @Tag("unit")
    void missingPathReportsError() {
        String source = ".IMPORT AS LIB";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());
        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
    }

    @Test
    @Tag("unit")
    void aKindWordOpensAListOfGroups() {
        ImportNode node = parseOne(".IMPORT \"lib.evo\" AS LIB USING MODULE A AS X B AS Y");

        assertThat(node.usings()).extracting(ImportNode.UsingClause::sourceAlias, ImportNode.UsingClause::targetAlias)
                .containsExactly(tuple("A", "X"), tuple("B", "Y"));
    }

    @Test
    @Tag("unit")
    void aRepeatedKindWordAddsToItsList() {
        ImportNode node = parseOne(".IMPORT \"lib.evo\" AS LIB USING MODULE A AS X MODULE B AS Y");

        assertThat(node.usings()).extracting(ImportNode.UsingClause::sourceAlias, ImportNode.UsingClause::targetAlias)
                .containsExactly(tuple("A", "X"), tuple("B", "Y"));
    }

    @Test
    @Tag("unit")
    void aUsingWithoutAKindWordReportsTheMissingModule() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = parse(".IMPORT \"lib.evo\" AS LIB USING MATH AS CALC", diagnostics);

        assertThat(ast).isEmpty();
        assertThat(diagnostics.getDiagnostics()).singleElement()
                .satisfies(d -> assertThat(d.message()).isEqualTo("Expected MODULE after USING; found 'MATH'."));
    }

    @Test
    @Tag("unit")
    void aSecondUsingOnOneImportIsReported() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = parse(".IMPORT \"lib.evo\" AS LIB USING MODULE A AS X USING MODULE B AS Y", diagnostics);

        assertThat(ast).isEmpty();
        assertThat(diagnostics.getDiagnostics()).singleElement()
                .satisfies(d -> assertThat(d.message())
                        .isEqualTo("USING stands once on an import; a further list begins with its kind word."));
    }

    private static ImportNode parseOne(String source) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = parse(source, diagnostics);
        assertThat(diagnostics.hasErrors()).as(diagnostics.summary()).isFalse();
        assertThat(ast).hasSize(1);
        return (ImportNode) ast.get(0);
    }

    private static List<AstNode> parse(String source, DiagnosticsEngine diagnostics) {
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());
        return parser.parse().stream().filter(Objects::nonNull).toList();
    }


    private static ParserStatementRegistry registry() {
        ParserStatementRegistry reg = new ParserStatementRegistry();
        reg.register(".IMPORT", new ImportDirectiveHandler());
        reg.registerBlock(new org.evochora.compiler.frontend.BlockKind(java.util.Set.of(".PROC"), ".ENDPROC", java.util.Set.of()), new org.evochora.compiler.features.proc.ProcDirectiveHandler(new org.evochora.compiler.isa.RuntimeInstructionSetAdapter()));
        reg.registerDefault(new org.evochora.compiler.features.instruction.InstructionParsingHandler());
        return reg;
    }
}
