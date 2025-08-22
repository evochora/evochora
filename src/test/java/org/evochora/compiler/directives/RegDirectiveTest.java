package org.evochora.compiler.directives;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.ast.RegisterNode;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

public class RegDirectiveTest {
    @Test
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
