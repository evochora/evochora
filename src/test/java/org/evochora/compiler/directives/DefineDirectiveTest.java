package org.evochora.compiler.directives;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.parser.features.def.DefineNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

public class DefineDirectiveTest {
    @Test
    @Tag("unit")
    void testDefineDirectiveCreatesCorrectAstNode() {
        // Arrange
        String source = ".DEFINE MY_CONST DATA:123";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, Path.of("")); // KORREKTUR


        // Act
        // Wir filtern null-Werte heraus, da der Parser für leere Zeilen null zurückgibt.
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(DefineNode.class);

        DefineNode defineNode = (DefineNode) ast.get(0);
        assertThat(defineNode.name().text()).isEqualTo("MY_CONST");
        assertThat(defineNode.value()).isInstanceOf(TypedLiteralNode.class);
    }
}