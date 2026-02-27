package org.evochora.compiler.directives;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.PregNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the parsing of procedure-related directives, primarily `.PROC` and `.ENDP`.
 * These tests ensure that the parser correctly constructs a {@link ProcedureNode}
 * with its name, parameters, export status, and body.
 * These are unit tests for the parser and do not require external resources.
 */
public class ProcedureDirectiveTest {
    
    /**
     * Initialize the instruction set once for all tests in this class.
     */
    @BeforeAll
    static void setUp() {
        org.evochora.runtime.isa.Instruction.init();
    }
    
    /**
     * Verifies that the parser can handle a basic procedure block with a simple instruction inside.
     * It checks the procedure's name, body content, and its registration in the parser's symbol table.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testParserProcedureBlock() {
        
        // Arrange
        String source = String.join("\n",
                ".PROC MY_PROC",
                "  NOP",
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(ProcedureNode.class);

        ProcedureNode procNode = (ProcedureNode) ast.get(0);
        assertThat(procNode.name().text()).isEqualTo("MY_PROC");
        assertThat(procNode.exported()).isFalse();

        List<AstNode> bodyWithoutNulls = procNode.body().stream().filter(Objects::nonNull).toList();
        assertThat(bodyWithoutNulls).hasSize(1);
        assertThat(bodyWithoutNulls.get(0)).isInstanceOf(InstructionNode.class);

        InstructionNode nopNode = (InstructionNode) bodyWithoutNulls.get(0);
        assertThat(nopNode.opcode().text()).isEqualTo("NOP");

        var procTable = parser.getProcedureTable();
        assertThat(procTable).hasSize(1);
        assertThat(procTable).containsKey("MY_PROC");
        assertThat(procTable.get("MY_PROC")).isSameAs(procNode);
    }

    /**
     * Verifies that the parser correctly handles a procedure definition with parameters.
     * It checks that the parameter names are correctly extracted and stored in the {@link ProcedureNode}.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testParserProcedureWithParameters() {
        
        // Arrange
        String source = String.join("\n",
                ".PROC ADD REF A B",
                "  ADDS",
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(ProcedureNode.class);

        ProcedureNode procNode = (ProcedureNode) ast.get(0);
        assertThat(procNode.name().text()).isEqualTo("ADD");
        assertThat(procNode.exported()).isFalse();
        assertThat(procNode.refParameters()).hasSize(2);
        assertThat(procNode.refParameters().get(0).text()).isEqualTo("A");
        assertThat(procNode.refParameters().get(1).text()).isEqualTo("B");
    }

    /**
     * Verifies that the parser can handle a full procedure definition including the EXPORT keyword,
     * parameters, and nested directives like {@code .PREG}.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testFullProcedureDefinition() {

        // Arrange
        String source = String.join("\n",
                ".PROC FULL_PROC EXPORT REF A",
                "  .PREG %TMP %PR0",
                "  NOP",
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics);

        // Act
        List<AstNode> ast = parser.parse();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(ProcedureNode.class);

        ProcedureNode procNode = (ProcedureNode) ast.get(0);
        assertThat(procNode.name().text()).isEqualTo("FULL_PROC");
        assertThat(procNode.exported()).isTrue();
        assertThat(procNode.refParameters()).hasSize(1).extracting(Token::text).containsExactly("A");

        List<AstNode> bodyDirectives = procNode.body().stream()
                .filter(n -> !(n instanceof InstructionNode))
                .toList();
        assertThat(bodyDirectives).hasSize(1);
        assertThat(bodyDirectives.get(0)).isInstanceOf(PregNode.class);
    }

    @Test
    @Tag("unit")
    void testParserProcedureWithRefOnly() {
        // Arrange
        String source = ".PROC myProc REF rA rB\n.ENDP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics);

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        ProcedureNode procNode = (ProcedureNode) ast.get(0);
        assertThat(procNode.name().text()).isEqualTo("myProc");
        assertThat(procNode.refParameters()).hasSize(2).extracting(Token::text).containsExactly("rA", "rB");
        assertThat(procNode.valParameters()).isEmpty();
        assertThat(procNode.parameters()).isEmpty();
    }

    @Test
    @Tag("unit")
    void testParserProcedureWithValOnly() {
        // Arrange
        String source = ".PROC myProc VAL v1 v2\n.ENDP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics);

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        ProcedureNode procNode = (ProcedureNode) ast.get(0);
        assertThat(procNode.name().text()).isEqualTo("myProc");
        assertThat(procNode.valParameters()).hasSize(2).extracting(Token::text).containsExactly("v1", "v2");
        assertThat(procNode.refParameters()).isEmpty();
        assertThat(procNode.parameters()).isEmpty();
    }

    @Test
    @Tag("unit")
    void testParserProcedureWithRefAndVal() {
        // Arrange
        String source = ".PROC myProc REF rA VAL v1\n.ENDP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics);

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        ProcedureNode procNode = (ProcedureNode) ast.get(0);
        assertThat(procNode.name().text()).isEqualTo("myProc");
        assertThat(procNode.refParameters()).hasSize(1).extracting(Token::text).containsExactly("rA");
        assertThat(procNode.valParameters()).hasSize(1).extracting(Token::text).containsExactly("v1");
        assertThat(procNode.parameters()).isEmpty();
    }

    @Test
    @Tag("unit")
    void testParserProcedureWithValAndRef() {
        // Arrange
        String source = ".PROC myProc VAL v1 REF rA\n.ENDP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics);

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        ProcedureNode procNode = (ProcedureNode) ast.get(0);
        assertThat(procNode.name().text()).isEqualTo("myProc");
        assertThat(procNode.valParameters()).hasSize(1).extracting(Token::text).containsExactly("v1");
        assertThat(procNode.refParameters()).hasSize(1).extracting(Token::text).containsExactly("rA");
        assertThat(procNode.parameters()).isEmpty();
    }

    @Test
    @Tag("unit")
    void testParserProcedureWithNoParameters() {
        // Arrange
        String source = ".PROC myProc\n.ENDP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics);

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        ProcedureNode procNode = (ProcedureNode) ast.get(0);
        assertThat(procNode.name().text()).isEqualTo("myProc");
        assertThat(procNode.refParameters()).isEmpty();
        assertThat(procNode.valParameters()).isEmpty();
        assertThat(procNode.parameters()).isEmpty();
    }
}
