package org.evochora.compiler.directives;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.features.proc.ExportNode;
import org.evochora.compiler.frontend.parser.features.proc.PregNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.parser.features.require.RequireNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

public class ProcedureDirectiveTest {
    @Test
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
        Parser parser = new Parser(tokens, diagnostics);

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(ProcedureNode.class);

        ProcedureNode procNode = (ProcedureNode) ast.get(0);
        assertThat(procNode.name().text()).isEqualTo("MY_PROC");

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

    @Test
    void testParserProcedureWithParameters() {
        // Arrange
        String source = String.join("\n",
                ".PROC ADD WITH A B",
                "  ADDS",
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics);

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(ProcedureNode.class);

        ProcedureNode procNode = (ProcedureNode) ast.get(0);
        assertThat(procNode.name().text()).isEqualTo("ADD");
        assertThat(procNode.parameters()).hasSize(2);
        assertThat(procNode.parameters().get(0).text()).isEqualTo("A");
        assertThat(procNode.parameters().get(1).text()).isEqualTo("B");
    }

    @Test
    void testFullProcedureDefinition() {
        // Arrange
        String source = String.join("\n",
                ".PROC FULL_PROC WITH A",
                "  .PREG %TMP 0",
                "  .EXPORT FULL_PROC",
                "  .REQUIRE \"lib/utils.s\" AS utils",
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
        assertThat(procNode.parameters()).hasSize(1).extracting(Token::text).containsExactly("A");

        List<AstNode> bodyDirectives = procNode.body().stream()
                .filter(n -> !(n instanceof InstructionNode))
                .toList();
        assertThat(bodyDirectives).hasSize(3);

        assertThat(bodyDirectives.get(0)).isInstanceOf(PregNode.class);
        PregNode pregNode = (PregNode) bodyDirectives.get(0);
        assertThat(pregNode.alias().text()).isEqualTo("%TMP");
        assertThat(pregNode.index().value()).isEqualTo(0);

        assertThat(bodyDirectives.get(1)).isInstanceOf(ExportNode.class);

        assertThat(bodyDirectives.get(2)).isInstanceOf(RequireNode.class);
        RequireNode requireNode = (RequireNode) bodyDirectives.get(2);
        assertThat(requireNode.path().value()).isEqualTo("lib/utils.s");
        assertThat(requireNode.alias().text()).isEqualTo("utils");
    }
}
