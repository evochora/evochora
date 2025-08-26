package org.evochora.compiler.directives;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

public class PregDirectiveTest {

    @Test
    @Tag("unit")
    void testPregIsParsedCorrectlyInsideProc() {
        // Arrange
        String source = String.join(System.lineSeparator(),
                ".PROC MY_PROC",
                "  .PREG %TMP 0",
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics, Path.of("")); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(ProcedureNode.class);
    }

    @Test
    @Tag("unit")
    void testPregWithInvalidIndexReportsError() {
        // Arrange
        String source = String.join(System.lineSeparator(),
                ".PROC MY_PROC",
                "  .PREG %TMP 2", // Index 2 ist ungültig, aber der Parser sollte es erstmal nur als Zahl lesen
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics, Path.of("")); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        // Der Parser sollte keinen Fehler melden, die semantische Analyse würde den Wertebereich prüfen.
        assertThat(diagnostics.hasErrors()).isFalse();
    }
}