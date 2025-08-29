package org.evochora.compiler.directives;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.ast.RegisterNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the `.REG` directive for creating register aliases.
 * This test ensures that the parser correctly defines and resolves these aliases.
 * This is a unit test and does not require external resources.
 */
public class RegDirectiveTest {
    /**
     * Verifies that the parser correctly handles a `.REG` directive and subsequent usage of the alias.
     * The test defines a register alias and then uses it in an instruction, checking that the
     * parser resolves the alias back to the original register in the AST.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testRegDirectiveAndAliasUsage() {
        // Arrange
        String source = String.join("\n",
                ".REG STACK_POINTER %DR15",
                "SETI STACK_POINTER 42"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, Path.of("")); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        InstructionNode seti = (InstructionNode) ast.get(0);
        assertThat(seti.arguments()).hasSize(2);

        assertThat(seti.arguments().get(0)).isInstanceOf(RegisterNode.class);
        RegisterNode reg = (RegisterNode) seti.arguments().get(0);
        assertThat(reg.registerToken().text()).isEqualTo("%DR15");
    }
}
