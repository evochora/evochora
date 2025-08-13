package org.evochora.compiler.phases;

import org.evochora.compiler.diagnostics.Diagnostic;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SemanticAnalyzerTest {

    private List<AstNode> getAst(String source, DiagnosticsEngine diagnostics) {
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics);
        return parser.parse();
    }

    @Test
    void testDuplicateLabelInGlobalScopeIsReported() {
        // Arrange
        String source = String.join("\n",
                "START:",
                "  NOP",
                "START:  # Dieses Label ist doppelt",
                "  NOP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        // Assert
        List<Diagnostic> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.type() == Diagnostic.Type.ERROR)
                .toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("Symbol 'START' is already defined in this scope.");
    }

    @Test
    void testSameLabelInDifferentScopesIsAllowed() {
        // Arrange
        String source = String.join("\n",
                ".SCOPE FIRST_SCOPE",
                "  MY_LABEL: NOP",
                ".ENDS",
                ".SCOPE SECOND_SCOPE",
                "  MY_LABEL: NOP",
                ".ENDS"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors())
                .as("Gleiche Label-Namen in unterschiedlichen Scopes sollten erlaubt sein")
                .isFalse();
    }

    @Test
    void testDuplicateLabelWithinSameScopeIsReported() {
        // Arrange
        String source = String.join("\n",
                ".SCOPE MY_SCOPE",
                "  LOOP: NOP",
                "  LOOP: NOP # Fehler: Duplikat im selben Scope",
                ".ENDS"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        // Assert
        List<Diagnostic> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.type() == Diagnostic.Type.ERROR)
                .toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("Symbol 'LOOP' is already defined in this scope.");
    }

    // --- NEUE TESTS FÜR ARGUMENTEN-PRÜFUNG ---

    @Test
    void testInstructionWithTooFewArgumentsReportsError() {
        // Arrange
        String source = "ADDI %DR0  # Fehler: Ein Argument fehlt";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Instruction 'ADDI' expects 2 argument(s), but got 1.");
    }

    @Test
    void testInstructionWithTooManyArgumentsReportsError() {
        // Arrange
        String source = "NOP %DR0  # Fehler: NOP erwartet keine Argumente";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Instruction 'NOP' expects 0 argument(s), but got 1.");
    }

    @Test
    void testInstructionWithCorrectNumberOfArgumentsIsAllowed() {
        // Arrange
        String source = "ADDI %DR0 DATA:1";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
    }
}