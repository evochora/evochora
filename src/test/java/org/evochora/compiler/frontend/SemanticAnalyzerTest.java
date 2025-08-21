package org.evochora.compiler.frontend;

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

    // --- NEUE TESTS FÜR ARGUMENT-TYP-PRÜFUNG ---

    @Test
    void testInstructionWithWrongArgumentTypeReportsError() {
        // Arrange
        String source = "SETI 1|0 DATA:1  # Fehler: SETI erwartet ein REGISTER, kein VECTOR";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Argument 1 for instruction 'SETI' has the wrong type. Expected REGISTER, but got VECTOR.");
    }

    @Test
    void testInstructionWithMultipleWrongArgumentTypesReportsMultipleErrors() {
        // Arrange
        String source = "ADDI 1|0 %DR0  # Fehler: Arg1=VECTOR statt REGISTER, Arg2=REGISTER statt LITERAL";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        List<Diagnostic> errors = diagnostics.getDiagnostics();
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0).message()).contains("Argument 1 for instruction 'ADDI' has the wrong type. Expected REGISTER, but got VECTOR.");
        assertThat(errors.get(1).message()).contains("Argument 2 for instruction 'ADDI' has the wrong type. Expected LITERAL, but got REGISTER.");
    }

    @Test
    void testInstructionWithCorrectArgumentTypesIsAllowed() {
        // Arrange
        String source = "SETV %DR0 1|0"; // Korrekte Typen: REGISTER, VECTOR
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
    }

    @Test
    void testAccessingInnerScopeLabelFromOuterScopeReportsError() {
        // Arrange
        String source = String.join("\n",
                ".SCOPE INNER_SCOPE",
                "  INNER_LABEL: NOP",
                ".ENDS",
                "JMPI INNER_LABEL  # Fehler: INNER_LABEL ist hier nicht sichtbar"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Symbol 'INNER_LABEL' is not defined.");
    }

    @Test
    void testAccessingProcedureInternalLabelFromOutsideReportsError() {
        // Arrange
        String source = String.join("\n",
                ".PROC MY_PROC",
                "  INTERNAL_LABEL: NOP",
                "  RET",
                ".ENDP",
                "JMPI INTERNAL_LABEL  # Fehler: Dieses Label ist privat für MY_PROC"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Symbol 'INTERNAL_LABEL' is not defined.");
    }

    @Test
    void testJumpingToAConstantReportsError() {
        // Arrange
        String source = String.join("\n",
                ".DEFINE MY_CONST 42",
                "JMPI MY_CONST  # Fehler: MY_CONST ist eine Konstante, kein Label"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        // Der Test muss jetzt auf die neue, spezifischere Fehlermeldung prüfen
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Argument 1 for instruction 'JMPI' has the wrong type. Expected LABEL, but got CONSTANT.");
    }

    @Test
    void testUsingUndefinedLabelReportsError() {
        // Arrange
        String source = "JMPI NON_EXISTENT_LABEL";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Symbol 'NON_EXISTENT_LABEL' is not defined.");
    }

    @Test
    void testUsingDefinedConstantAsLiteralIsAllowed() {
        // Arrange
        String source = String.join("\n",
                ".DEFINE MY_CONST 123",
                "SETI %DR0 MY_CONST"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
    }

    // --- Neue Tests für verschärfte Syntax ---

    @Test
    void testUnknownRegisterIsReported() {
        // %0 ist kein valider Registername in unserem ISA-Schema
        String source = "SETI %0 DATA:1";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message()).contains("Unknown register '%0'");
    }

    @Test
    void testStrictTypingRejectsUntypedLiteral() {
        // STRICT_TYPING ist in Config true → ungetypte Zahl 42 ist nicht erlaubt bei SETI
        String source = "SETI %DR0 42";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .contains("requires a typed literal");
    }

    @Test
    void testDirectAccessToFprIsForbidden() {
        String source = "ADDI %FPR0 DATA:1";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .contains("Access to formal parameter registers (%FPRx) is not allowed");
    }

    @Test
    void testLabelAfterRetInProcedureIsFound() {
        // Arrange
        String source = String.join("\n",
                ".PROC MY_PROC",
                "  JMPI SUCCESS_LABEL  # Sprung zu einem Label, das nach RET definiert wird",
                "  RET",
                "SUCCESS_LABEL: NOP",
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);

        // Act
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors())
                .as("Ein Label, das nach einem RET, aber vor .ENDP definiert wird, sollte gefunden werden.")
                .isFalse();
    }
}