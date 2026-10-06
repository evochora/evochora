package org.evochora.compiler.frontend;

import org.evochora.compiler.TestLexers;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.runtime.Config;
import org.evochora.compiler.diagnostics.Diagnostic;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.features.ctx.PopCtxDirectiveHandler;
import org.evochora.compiler.features.ctx.PushCtxDirectiveHandler;
import org.evochora.compiler.features.constdir.ConstDirectiveHandler;
import org.evochora.compiler.features.dir.DirDirectiveHandler;
import org.evochora.compiler.features.importdir.ImportDirectiveHandler;
import org.evochora.compiler.features.org.OrgDirectiveHandler;
import org.evochora.compiler.features.place.PlaceDirectiveHandler;
import org.evochora.compiler.features.proc.ProcDirectiveHandler;
import org.evochora.compiler.features.reg.RegDirectiveHandler;
import org.evochora.compiler.features.require.RequireDirectiveHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.TestRegistries;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
        // Initialize instruction set for the parser
        org.evochora.runtime.isa.Instruction.init();
        
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens, diagnostics, allHandlers());
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
                ".LABEL START",
                "  NOP",
                ".LABEL START  # Dieses Label ist doppelt",
                "  NOP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        List<Diagnostic> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.type() == Diagnostic.Type.ERROR)
                .toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("Cannot define label 'START': the name is already used at");
    }

    /**
     * Verifies that using the same label name in different, non-overlapping procedure scopes is allowed.
     * This is a unit test for scope-based symbol resolution.
     */
    @Test
    @Tag("unit")
    void testSameLabelInDifferentScopesIsAllowed() {
        // Arrange
        String source = String.join("\n",
                ".PROC FIRST_PROC",
                "  .LABEL MY_LABEL NOP",
                "  RET",
                ".ENDPROC",
                ".PROC SECOND_PROC",
                "  .LABEL MY_LABEL NOP",
                "  RET",
                ".ENDPROC"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors())
                .as("Same label names in different procedure scopes should be allowed")
                .isFalse();
    }

    /**
     * Verifies that defining the same label twice within the same procedure scope is reported as an error.
     * This is a unit test for symbol table management within a single scope.
     */
    @Test
    @Tag("unit")
    void testDuplicateLabelWithinSameScopeIsReported() {
        // Arrange
        String source = String.join("\n",
                ".PROC MY_PROC",
                "  .LABEL LOOP NOP",
                "  .LABEL LOOP NOP",
                "  RET",
                ".ENDPROC"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        List<Diagnostic> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.type() == Diagnostic.Type.ERROR)
                .toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("Cannot define label 'LOOP': the name is already used at");
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
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
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
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
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
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
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
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
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
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
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
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
    }

    /**
     * Verifies that attempting to access a label defined in a procedure from outside
     * is reported as an undefined symbol error.
     * This is a unit test for scope-based symbol resolution.
     */
    @Test
    @Tag("unit")
    void testAccessingInnerScopeLabelFromOuterScopeReportsError() {
        // Arrange
        String source = String.join("\n",
                ".PROC INNER_PROC",
                "  .LABEL INNER_LABEL NOP",
                "  RET",
                ".ENDPROC",
                "JMPI INNER_LABEL"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Cannot use 'INNER_LABEL' as an argument: the name is not defined.");
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
                "  .LABEL INTERNAL_LABEL NOP",
                "  RET",
                ".ENDPROC",
                "JMPI INTERNAL_LABEL  # Fehler: Dieses Label ist privat für MY_PROC"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Cannot use 'INTERNAL_LABEL' as an argument: the name is not defined.");
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
                ".CONST MY_CONST 42",
                "JMPI MY_CONST  # Fehler: MY_CONST ist eine Konstante, kein Label"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Argument 1 for instruction 'JMPI' has the wrong type. Expected LABEL, but got LITERAL.");
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
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .isEqualTo("Cannot use 'NON_EXISTENT_LABEL' as an argument: the name is not defined.");
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
                ".CONST MY_CONST 123",
                "SETI %DR0 MY_CONST"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
    }

    /**
     * Verifies that using an out-of-bounds register name is reported as an error.
     * This is a unit test for ISA validation.
     */
    @Test
    @Tag("unit")
    void testUnknownRegisterIsReported() {
        String source = "SETI %DR" + Config.NUM_DATA_REGISTERS + " DATA:1";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message()).contains("%DR" + Config.NUM_DATA_REGISTERS);
    }

    /**
     * Verifies that an untyped number where a literal operand is expected is reported as an
     * error: every literal carries a molecule type.
     */
    @Test
    @Tag("unit")
    void testUntypedLiteralIsRejected() {
        String source = "SETI %DR0 42";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .contains("requires a typed literal");
    }

    /**
     * Verifies that direct access to formal parameter registers (e.g., %FDR0) is forbidden
     * outside of the compiler-generated procedure prologue/epilogue.
     * This is a unit test for ISA rule enforcement.
     */
    @Test
    @Tag("unit")
    void testDirectAccessToFdrIsForbidden() {
        String source = "ADDI %FDR0 DATA:1";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().get(0).message())
                .contains("Register '%FDR0' is reserved for procedure parameters");
    }

    /**
     * Verifies that a label defined after a `RET` instruction but before the end of the procedure (`.ENDPROC`)
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
                ".LABEL SUCCESS_LABEL NOP",
                ".ENDPROC"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors())
                .as("Ein Label, das nach einem RET, aber vor .ENDPROC definiert wird, sollte gefunden werden.")
                .isFalse();
    }

    @Test
    @Tag("unit")
    void testCallWithCorrectRefAndValArgsIsAllowed() {
        // Arrange
        String source = String.join("\n",
                ".PROC myProc REF rA VAL v1",
                "  RET",
                ".ENDPROC",
                "CALL myProc REF %DR1 VAL 123"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
    }

    @Test
    @Tag("unit")
    void testCallWithNonRegisterRefArgReportsError() {
        // Arrange
        String source = String.join("\n",
                ".PROC myProc REF rA",
                "  RET",
                ".ENDPROC",
                "CALL myProc REF 123"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics()).anyMatch(d -> d.message().contains("Cannot pass a literal as REF argument: REF takes a register."));
    }

    @Test
    @Tag("unit")
    void testCallWithWrongRefCountReportsError() {
        // Arrange
        String source = String.join("\n",
                ".PROC myProc REF rA rB",
                "  RET",
                ".ENDPROC",
                "CALL myProc REF %DR1"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics()).anyMatch(d -> d.message().contains("Procedure 'myProc' expects 2 REF argument(s), but received 1."));
    }

    @Test
    @Tag("unit")
    void testCallWithWrongValCountReportsError() {
        // Arrange
        String source = String.join("\n",
                ".PROC myProc VAL v1",
                "  RET",
                ".ENDPROC",
                "CALL myProc VAL 1 2"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics()).anyMatch(d -> d.message().contains("Procedure 'myProc' expects 1 VAL argument(s), but received 2."));
    }

    @Test
    @Tag("unit")
    void testCallWithRegisterForValArgIsAllowed() {
        // Arrange
        String source = String.join("\n",
                ".PROC myProc VAL v1",
                "  RET",
                ".ENDPROC",
                "CALL myProc VAL %DR1"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(source, diagnostics);

        // Act
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
    }

    /**
     * Analyzes the source as the compiler does, through both passes and the shadowing check that
     * follows them, and returns the errors reported.
     */
    private List<Diagnostic> analyze(String... lines) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<AstNode> ast = getAst(String.join("\n", lines), diagnostics);
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, null, null, TestRegistries.analysisRegistry(symbolTable, diagnostics), new org.evochora.compiler.frontend.semantics.ModuleSetupRegistry());
        analyzer.analyze(ast);
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.type() == Diagnostic.Type.ERROR)
                .toList();
    }

    /**
     * A name inside a procedure may repeat a name of the module around it, in either order of
     * definition: inside the procedure the inner one is meant. Visibility flows inward without
     * restriction, so a level's own names are its only defence against a name added outside it.
     */
    @Test
    @Tag("unit")
    void aNameInAProcedureMayRepeatANameOfTheModuleAroundIt() {
        List<Diagnostic> errors = analyze(
                ".CONST N DATA:5",
                ".LABEL EARLIER NOP",
                ".REG %TMP %DR0",
                ".PROC P REF N",
                "  .LABEL EARLIER NOP",
                "  .REG %TMP %PDR0",
                "  RET",
                ".ENDPROC");

        assertThat(errors).isEmpty();
    }

    @Test
    @Tag("unit")
    void aModuleNameMayRepeatANameOfAProcedureDefinedBefore() {
        List<Diagnostic> errors = analyze(
                ".PROC P REF N",
                "  .LABEL LATER NOP",
                "  .REG %TMP %PDR0",
                "  RET",
                ".ENDPROC",
                ".CONST N DATA:5",
                ".LABEL LATER NOP",
                ".REG %TMP %DR0");

        assertThat(errors).isEmpty();
    }

    @Test
    @Tag("unit")
    void aLabelWithADotInItsNameIsReported() {
        List<Diagnostic> errors = analyze(".LABEL X.Y NOP");

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("'X.Y'").contains("a name is one segment");
    }

    @Test
    @Tag("unit")
    void aProcedureWithADotInItsNameIsReported() {
        List<Diagnostic> errors = analyze(
                ".PROC X.Y",
                "  RET",
                ".ENDPROC");

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("'X.Y'").contains("a name is one segment");
    }

    /**
     * The alias of an import is a name like any other. A single file has no module set up for
     * the import, which the analysis of the import reports as well; only the report of the
     * name is looked at here.
     */
    @Test
    @Tag("unit")
    void anImportAliasWithADotIsReported() {
        List<Diagnostic> errors = analyze(".IMPORT \"lib.evo\" AS X.Y");

        assertThat(errors).filteredOn(d -> d.message().contains("one segment"))
                .singleElement()
                .satisfies(d -> assertThat(d.message()).contains("'X.Y'").contains("a name is one segment"));
    }

    /**
     * The alias of a requirement is a name like any other; only the report of the name is looked
     * at, as for an import.
     */
    @Test
    @Tag("unit")
    void aRequireAliasWithADotIsReported() {
        List<Diagnostic> errors = analyze(".REQUIRE \"lib.evo\" AS X.Y");

        assertThat(errors).filteredOn(d -> d.message().contains("one segment"))
                .singleElement()
                .satisfies(d -> assertThat(d.message()).contains("'X.Y'").contains("a name is one segment"));
    }

    /**
     * A label marked EXPORT inside a procedure is visible one level further out, to the module,
     * through its path.
     */
    @Test
    @Tag("unit")
    void anExportedProcedureLabelIsReachedFromTheModuleByItsPath() {
        List<Diagnostic> errors = analyze(
                ".PROC CLAMP",
                "  EXPORT .LABEL TO_MIN NOP",
                "  RET",
                ".ENDPROC",
                "PSLI CLAMP.TO_MIN");

        assertThat(errors).isEmpty();
    }

    @Test
    @Tag("unit")
    void aProcedureLabelWithoutExportIsNotReachedFromTheModule() {
        List<Diagnostic> errors = analyze(
                ".PROC CLAMP",
                "  .LABEL TO_MIN NOP",
                "  RET",
                ".ENDPROC",
                "PSLI CLAMP.TO_MIN");

        assertThat(errors).singleElement().satisfies(d -> {
            assertThat(d.lineNumber()).isEqualTo(5);
            assertThat(d.message()).isEqualTo(
                    "Cannot use 'CLAMP.TO_MIN' as an argument: 'TO_MIN' of CLAMP is not marked EXPORT.");
        });
    }

    @Test
    @Tag("unit")
    void aPathToANameTheProcedureDoesNotHaveIsReported() {
        List<Diagnostic> errors = analyze(
                ".PROC CLAMP",
                "  RET",
                ".ENDPROC",
                "PSLI CLAMP.NOPE");

        assertThat(errors).singleElement().satisfies(d -> assertThat(d.message())
                .isEqualTo("Cannot use 'CLAMP.NOPE' as an argument: 'CLAMP' has no member 'NOPE'."));
    }

    /**
     * A constant opens no level, so a path cannot continue after it.
     */
    @Test
    @Tag("unit")
    void aPathThroughANameThatOpensNoLevelIsReported() {
        List<Diagnostic> errors = analyze(
                ".CONST X DATA:5",
                "PSLI X.Y");

        assertThat(errors).singleElement().satisfies(d -> assertThat(d.message())
                .isEqualTo("Cannot use 'X.Y' as an argument: 'X' has no member 'Y'."));
    }

    /**
     * Inside the procedure the writer stands in its level, so its own names are reached by path
     * without being exported.
     */
    @Test
    @Tag("unit")
    void aProcedureReachesItsOwnNamesByPathWithoutExport() {
        List<Diagnostic> errors = analyze(
                ".PROC CLAMP",
                "  PSLI CLAMP.TO_MIN",
                "  .LABEL TO_MIN NOP",
                "  RET",
                ".ENDPROC");

        assertThat(errors).isEmpty();
    }

    /**
     * A module's imports and requirements are names of its module level: the directive inside a
     * procedure is reported where the names are collected, which follows every level the symbol
     * table opens.
     */
    @Test
    @Tag("unit")
    void anImportInsideAProcedureIsReported() {
        List<Diagnostic> errors = analyze(
                ".PROC P",
                "  .IMPORT \"lib.evo\" AS LIB",
                "  RET",
                ".ENDPROC");

        assertThat(errors).singleElement().satisfies(d -> {
            assertThat(d.lineNumber()).isEqualTo(2);
            assertThat(d.message()).isEqualTo(".IMPORT may stand only at the module level.");
        });
    }

    @Test
    @Tag("unit")
    void aRequireInsideAProcedureIsReported() {
        List<Diagnostic> errors = analyze(
                ".PROC P",
                "  .REQUIRE \"lib.evo\" AS LIB",
                "  RET",
                ".ENDPROC");

        assertThat(errors).singleElement().satisfies(d -> {
            assertThat(d.lineNumber()).isEqualTo(2);
            assertThat(d.message()).isEqualTo(".REQUIRE may stand only at the module level.");
        });
    }

    private static ParserStatementRegistry allHandlers() {
        ParserStatementRegistry reg = new ParserStatementRegistry();
        reg.register(".CONST", new ConstDirectiveHandler());
        reg.register(".REG", new RegDirectiveHandler(new RuntimeInstructionSetAdapter()));
        reg.register(".PROC", new ProcDirectiveHandler(new RuntimeInstructionSetAdapter()));
        reg.register(".ORG", new OrgDirectiveHandler());
        reg.register(".DIR", new DirDirectiveHandler());
        reg.register(".PLACE", new PlaceDirectiveHandler());
        reg.register(".IMPORT", new ImportDirectiveHandler());
        reg.register(".REQUIRE", new RequireDirectiveHandler());
        reg.register(".PUSH_CTX", new PushCtxDirectiveHandler());
        reg.register(".POP_CTX", new PopCtxDirectiveHandler());
        reg.register(".LABEL", new org.evochora.compiler.features.label.LabelDirectiveHandler());
        reg.register("CALL", new org.evochora.compiler.features.proc.CallStatementHandler());
        reg.registerDefault(new org.evochora.compiler.features.instruction.InstructionParsingHandler());
        return reg;
    }
}