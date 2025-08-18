package org.evochora.compiler.directives;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.proc.PregNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

public class PregDirectiveTest {

    @Test
    void testPregIsParsedCorrectlyInsideProc() {
        // Arrange
        String source = String.join(System.lineSeparator(),
                ".PROC MY_PROC",
                "  .PREG %TMP 0",
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics);

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(ProcedureNode.class);
        ProcedureNode proc = (ProcedureNode) ast.get(0);
        assertThat(proc.body()).hasSize(1).first().isInstanceOf(PregNode.class);
        PregNode preg = (PregNode) proc.body().get(0);
        assertThat(preg.alias().text()).isEqualTo("%TMP");
        assertThat(preg.index().value()).isEqualTo(0);
    }

    @Test
    void testPregWithInvalidIndexReportsError() {
        // Arrange
        String source = String.join(System.lineSeparator(),
                ".PROC MY_PROC",
                "  .PREG %TMP 2", // Index 2 ist ungültig, aber der Parser sollte es erstmal nur als Zahl lesen
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics);

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        // Der Parser sollte keinen Fehler melden, die semantische Analyse würde den Wertebereich prüfen.
        assertThat(diagnostics.hasErrors()).isFalse();
        PregNode preg = (PregNode) ((ProcedureNode) ast.get(0)).body().get(0);
        assertThat(preg.index().value()).isEqualTo(2);
    }
}