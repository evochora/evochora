package org.evochora.compiler.frontend;

import org.evochora.compiler.diagnostics.Diagnostic;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.frontend.semantics.SymbolTable; // NEUER IMPORT
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains unit tests for the {@link SemanticAnalyzer}.
 * These tests verify that the semantic analyzer correctly identifies a wide range of semantic errors,
 * such as scope violations, type mismatches, incorrect argument counts, and duplicate definitions.
 * All tests are pure unit tests and do not require external resources.
 */
public class SemanticAnalyzerTest {

    private List<AstNode> getAst(String source, DiagnosticsEngine diagnostics) {
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, Path.of(""));
        return parser.parse();
    }

    /**
     * Verifies that defining the same label twice in the global scope is reported as an error.
     * This is a unit test for symbol table management.
     */
    @Test
    @Tag("unit")
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
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        List<Diagnostic> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.type() == Diagnostic.Type.ERROR)
                .toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("Symbol 'START' is already defined in this scope.");
    }

    /**
     * Verifies that using the same label name in different, non-overlapping scopes is allowed.
     * This is a unit test for scope-based symbol resolution.
     */
    @Test
    @Tag("unit")
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
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors())
                .as("Gleiche Label-Namen in unterschiedlichen Scopes sollten erlaubt sein")
                .isFalse();
    }

    /**
     * Verifies that defining the same label twice within the same scope is reported as an error.
     * This is a unit test for symbol table management within a single scope.
     */
    @Test
    @Tag("unit")
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
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        List<Diagnostic> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.type() == Diagnostic.Type.ERROR)
                .toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("Symbol 'LOOP' is already defined in this scope.");
    }

    /**
     * Verifies that using an instruction with too few arguments is reported as an error.
     * This is a unit test for instruction arity checking.
     */
    @Test
    @Tag("unit")
    void testInstructionWithTooFewArgumentsReportsError() {
        // Arrange
        String source = "ADDI %DR0  # Fehler: Ein Argument fehlt";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Instruction 'ADDI' expects 2 argument(s), but got 1.");
    }

    /**
     * Verifies that using an instruction with too many arguments is reported as an error.
     * This is a unit test for instruction arity checking.
     */
    @Test
    @Tag("unit")
    void testInstructionWithTooManyArgumentsReportsError() {
        // Arrange
        String source = "NOP %DR0  # Fehler: NOP erwartet keine Argumente";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Instruction 'NOP' expects 0 argument(s), but got 1.");
    }

    /**
     * Verifies that an instruction with the correct number of arguments passes semantic analysis.
     * This is a unit test for instruction arity checking.
     */
    @Test
    @Tag("unit")
    void testInstructionWithCorrectNumberOfArgumentsIsAllowed() {
        // Arrange
        String source = "ADDI %DR0 DATA:1";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
    }

    /**
     * Verifies that an instruction with an argument of the wrong type is reported as an error.
     * This is a unit test for instruction argument type checking.
     */
    @Test
    @Tag("unit")
    void testInstructionWithWrongArgumentTypeReportsError() {
        // Arrange
        String source = "SETI 1|0 DATA:1  # Fehler: SETI erwartet ein REGISTER, kein VECTOR";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Argument 1 for instruction 'SETI' has the wrong type. Expected REGISTER, but got VECTOR.");
    }

    /**
     * Verifies that an instruction with multiple arguments of the wrong type reports multiple errors.
     * This is a unit test for instruction argument type checking.
     */
    @Test
    @Tag("unit")
    void testInstructionWithMultipleWrongArgumentTypesReportsMultipleErrors() {
        // Arrange
        String source = "ADDI 1|0 %DR0  # Fehler: Arg1=VECTOR statt REGISTER, Arg2=REGISTER statt LITERAL";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        List<Diagnostic> errors = diagnostics.getDiagnostics();
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0).message()).contains("Argument 1 for instruction 'ADDI' has the wrong type. Expected REGISTER, but got VECTOR.");
        assertThat(errors.get(1).message()).contains("Argument 2 for instruction 'ADDI' has the wrong type. Expected LITERAL, but got REGISTER.");
    }

    /**
     * Verifies that an instruction with the correct argument types passes semantic analysis.
     * This is a unit test for instruction argument type checking.
     */
    @Test
    @Tag("unit")
    void testInstructionWithCorrectArgumentTypesIsAllowed() {
        // Arrange
        String source = "SETV %DR0 1|0"; // Korrekte Typen: REGISTER, VECTOR
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
    }

    /**
     * Verifies that attempting to access a label defined in an inner scope from an outer scope
     * is reported as an undefined symbol error.
     * This is a unit test for scope-based symbol resolution.
     */
    @Test
    @Tag("unit")
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
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Symbol 'INNER_LABEL' is not defined.");
    }

    /**
     * Verifies that attempting to access a label defined inside a procedure from outside
     * that procedure is reported as an undefined symbol error.
     * This is a unit test for procedure scope rules.
     */
    @Test
    @Tag("unit")
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
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Symbol 'INTERNAL_LABEL' is not defined.");
    }

    /**
     * Verifies that attempting to use a constant symbol where a label is expected
     * (e.g., in a jump instruction) is reported as a type error.
     * This is a unit test for symbol type checking.
     */
    @Test
    @Tag("unit")
    void testJumpingToAConstantReportsError() {
        // Arrange
        String source = String.join("\n",
                ".DEFINE MY_CONST 42",
                "JMPI MY_CONST  # Fehler: MY_CONST ist eine Konstante, kein Label"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Argument 1 for instruction 'JMPI' has the wrong type. Expected LABEL, but got CONSTANT.");
    }

    /**
     * Verifies that using a label that has not been defined is reported as an error.
     * This is a unit test for undefined symbol checking.
     */
    @Test
    @Tag("unit")
    void testUsingUndefinedLabelReportsError() {
        // Arrange
        String source = "JMPI NON_EXISTENT_LABEL";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Symbol 'NON_EXISTENT_LABEL' is not defined.");
    }

    /**
     * Verifies that a defined constant can be correctly used as a literal value in an instruction.
     * This is a unit test for constant symbol resolution.
     */
    @Test
    @Tag("unit")
    void testUsingDefinedConstantAsLiteralIsAllowed() {
        // Arrange
        String source = String.join("\n",
                ".DEFINE MY_CONST 123",
                "SETI %DR0 MY_CONST"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
    }

    /**
     * Verifies that using an unknown or invalid register name is reported as an error.
     * This is a unit test for ISA validation.
     */
    @Test
    @Tag("unit")
    void testUnknownRegisterIsReported() {
        // %0 ist kein valider Registername in unserem ISA-Schema
        String source = "SETI %0 DATA:1";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message()).contains("Unknown register '%0'");
    }

    /**
     * Verifies that using an untyped number where a typed literal is required
     * (based on compiler config) is reported as an error.
     * This is a unit test for strict typing rules.
     */
    @Test
    @Tag("unit")
    void testStrictTypingRejectsUntypedLiteral() {
        // STRICT_TYPING ist in Config true → ungetypte Zahl 42 ist nicht erlaubt bei SETI
        String source = "SETI %DR0 42";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .contains("requires a typed literal");
    }

    /**
     * Verifies that direct access to formal parameter registers (e.g., %FPR0) is forbidden
     * outside of the compiler-generated procedure prologue/epilogue.
     * This is a unit test for ISA rule enforcement.
     */
    @Test
    @Tag("unit")
    void testDirectAccessToFprIsForbidden() {
        String source = "ADDI %FPR0 DATA:1";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .contains("Access to formal parameter registers (%FPRx) is not allowed");
    }

    /**
     * Verifies that a label defined after a `RET` instruction but before the end of the procedure (`.ENDP`)
     * is still correctly recognized and resolved within that procedure's scope.
     * This is a unit test for symbol resolution within procedure scopes.
     */
    @Test
    @Tag("unit")
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

        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors())
                .as("Ein Label, das nach einem RET, aber vor .ENDP definiert wird, sollte gefunden werden.")
                .isFalse();
    }
}