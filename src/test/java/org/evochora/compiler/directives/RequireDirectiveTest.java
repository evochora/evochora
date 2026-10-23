package org.evochora.compiler.directives;

import org.evochora.compiler.TestLexers;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Tokenizer;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.features.importdir.RequireDirectiveHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.features.importdir.RequireNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.evochora.compiler.TestDiagnostics.located;
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
        String source = ".REQUIRE MODULE \"dependency.evo\" AS DEP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(RequireNode.class);

        RequireNode node = (RequireNode) ast.get(0);
        assertThat(node.path()).isEqualTo("dependency.evo");
        assertThat(node.alias()).isEqualToIgnoringCase("DEP");
    }

    @Test
    @Tag("unit")
    void missingAsKeywordReportsError() {
        String source = ".REQUIRE MODULE \"dependency.evo\" DEP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());
        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
    }

    @Test
    @Tag("unit")
    void missingPathReportsError() {
        String source = ".REQUIRE MODULE AS DEP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());
        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
    }

    @Test
    @Tag("unit")
    void missingAliasReportsError() {
        String source = ".REQUIRE MODULE \"dependency.evo\" AS";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());
        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
    }

    @Test
    @Tag("unit")
    void requireNodeRetainsSourceFileName() {
        String source = ".REQUIRE MODULE \"math.evo\" AS MATH";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics, "main.evo");
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(diagnostics.hasErrors()).isFalse();
        RequireNode node = (RequireNode) ast.get(0);
        assertThat(node.sourceInfo().fileName()).isEqualTo("main.evo");
    }

    @Test
    @Tag("unit")
    void requireNodeChildrenAreEmpty() {
        String source = ".REQUIRE MODULE \"utils.evo\" AS UTILS";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(diagnostics.hasErrors()).isFalse();
        RequireNode node = (RequireNode) ast.get(0);
        assertThat(node.getChildren()).isEmpty();
    }

    @Test
    @Tag("unit")
    void parsesTheKindWordModule() {
        String source = ".REQUIRE MODULE \"d.evo\" AS D";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(diagnostics.hasErrors()).as(diagnostics.summary()).isFalse();
        assertThat(ast).hasSize(1);
        RequireNode node = (RequireNode) ast.get(0);
        assertThat(node.path()).isEqualTo("d.evo");
        assertThat(node.alias()).isEqualToIgnoringCase("D");
    }

    @Test
    @Tag("unit")
    void aValueRequirementWithADefaultThatIsNoOperandIsReported() {
        String source = ".REQUIRE VALUE STEPS :";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(ast).isEmpty();
    }

    @Test
    @Tag("unit")
    void aRequireWithoutAKindWordReportsTheMissingModule() {
        String source = "# A comment line.\n.REQUIRE \"d.evo\" AS D";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, registry());

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(ast).isEmpty();
        assertThat(diagnostics.getDiagnostics()).singleElement()
                .satisfies(d -> assertThat(located(d))
                        .isEqualTo("<memory>:2: Expected MODULE or VALUE after .REQUIRE; found '\"d.evo\"'."));
    }


    private static ParserStatementRegistry registry() {
        ParserStatementRegistry reg = new ParserStatementRegistry();
        reg.register(".REQUIRE", new RequireDirectiveHandler());
        reg.registerBlock(new org.evochora.compiler.frontend.BlockKind(java.util.Set.of(".PROC"), ".ENDPROC", java.util.Set.of()), new org.evochora.compiler.features.proc.ProcDirectiveHandler(new org.evochora.compiler.isa.RuntimeInstructionSetAdapter()));
        reg.registerDefault(new org.evochora.compiler.features.instruction.InstructionParsingHandler());
        return reg;
    }
}
