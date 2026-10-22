package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.model.ast.LiteralNode;
import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.proc.CallAnalysisHandler;
import org.evochora.compiler.features.proc.CallNode;
import org.evochora.compiler.features.proc.ParameterBinding;
import org.evochora.compiler.features.label.LabelNode;
import org.evochora.compiler.features.constdir.ConstNode;
import org.evochora.compiler.features.proc.ProcedureNode;
import org.evochora.compiler.features.reg.RegNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for symbol type validation in {@link CallAnalysisHandler}.
 * Verifies that data register aliases are only accepted in REF/VAL positions,
 * location register aliases only in LREF/LVAL positions, and module aliases
 * are always rejected.
 */
@Tag("unit")
class CallAnalysisHandlerTypeSafetyTest {

    private CallAnalysisHandler handler;
    private SymbolTable symbolTable;
    private DiagnosticsEngine diagnostics;

    private static final SourceInfo SRC = new SourceInfo("test.s", 1, 0, "TEST", 0);

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        handler = new CallAnalysisHandler(new RuntimeInstructionSetAdapter(), new OperationRegistry());
        diagnostics = new DiagnosticsEngine();
        symbolTable = new SymbolTable(diagnostics);
        symbolTable.registerModule("TEST", "test.s");
        symbolTable.setCurrentModule("TEST");

        // Define a procedure with 1 REF param and 1 LREF param
        ProcedureNode proc = new ProcedureNode("MY_PROC", true,
                List.of(new ProcedureNode.ParamDecl("R", SRC)),
                List.of(),
                List.of(new ProcedureNode.ParamDecl("L", SRC)),
                List.of(),
                List.of(), SRC);
        symbolTable.define(new Symbol("MY_PROC", SRC, Symbol.Type.PROCEDURE, proc, true));

        // A procedure with 1 VAL param and 1 LVAL param
        ProcedureNode valProc = new ProcedureNode("VAL_PROC", true,
                List.of(),
                List.of(new ProcedureNode.ParamDecl("V", SRC)),
                List.of(),
                List.of(new ProcedureNode.ParamDecl("LV", SRC)),
                List.of(), SRC);
        symbolTable.define(new Symbol("VAL_PROC", SRC, Symbol.Type.PROCEDURE, valProc, true));
    }

    @Test
    void dataAliasAsRef_noError() {
        RegNode reg = new RegNode("X", "%DR0", SRC);
        symbolTable.define(new Symbol("X", SRC, Symbol.Type.REGISTER_ALIAS_DATA, reg));

        CallNode call = callWithRef("X");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
    }

    @Test
    void locationAliasAsLref_noError() {
        RegNode reg = new RegNode("X", "%LR0", SRC);
        symbolTable.define(new Symbol("X", SRC, Symbol.Type.REGISTER_ALIAS_LOCATION, reg));

        CallNode call = callWithLref("X");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
    }

    @Test
    void dataAliasAsLref_reportsError() {
        RegNode reg = new RegNode("X", "%DR0", SRC);
        symbolTable.define(new Symbol("X", SRC, Symbol.Type.REGISTER_ALIAS_DATA, reg));

        CallNode call = callWithLref("X");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("is a data register; expected a location register"))).isTrue();
    }

    @Test
    void locationAliasAsRef_reportsError() {
        RegNode reg = new RegNode("X", "%LR0", SRC);
        symbolTable.define(new Symbol("X", SRC, Symbol.Type.REGISTER_ALIAS_LOCATION, reg));

        CallNode call = callWithRef("X");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("is a location register; expected a data register"))).isTrue();
    }

    @Test
    void dataParameterAsLref_reportsError() {
        symbolTable.define(new Symbol("P", SRC, Symbol.Type.PARAMETER_DATA, new ParameterBinding("%FDR0")));

        CallNode call = callWithLref("P");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("is a data register; expected a location register"))).isTrue();
    }

    @Test
    void locationParameterAsRef_reportsError() {
        symbolTable.define(new Symbol("P", SRC, Symbol.Type.PARAMETER_LOCATION, new ParameterBinding("%FLR0")));

        CallNode call = callWithRef("P");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("is a location register; expected a data register"))).isTrue();
    }

    @Test
    void labelAsRef_reportsError() {
        symbolTable.define(new Symbol("MY_LABEL", SRC, Symbol.Type.LABEL, new LabelNode("MY_LABEL", SRC, false)));

        CallNode call = callWithRef("MY_LABEL");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("is a label; expected a data register"))).isTrue();
    }

    @Test
    void constantAsLref_reportsError() {
        symbolTable.define(new Symbol("MY_CONST", SRC, Symbol.Type.CONSTANT, new ConstNode("MY_CONST", SRC, new LiteralNode(new Value.Typed("DATA", 1), SRC), false)));

        CallNode call = callWithLref("MY_CONST");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("is a literal; expected a location register"))).isTrue();
    }

    @Test
    void moduleAliasAsRef_reportsError() {
        symbolTable.define(new Symbol("MATH", SRC, Symbol.Type.MODULE_ALIAS));

        CallNode call = callWithRef("MATH");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("is not a register or a label"))).isTrue();
    }

    @Test
    void moduleAliasAsLref_reportsError() {
        symbolTable.define(new Symbol("MATH", SRC, Symbol.Type.MODULE_ALIAS));

        CallNode call = callWithLref("MATH");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("is not a register or a label"))).isTrue();
    }

    @Test
    void unresolvedIdentifierAsRef_reportsError() {
        CallNode call = callWithRef("UNKNOWN");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("not defined"))).isTrue();
    }

    @Test
    void unresolvedIdentifierAsVal_reportsError() {
        CallNode call = callWithVal("SOME_NAME");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("Invalid argument: 'SOME_NAME' for VAL"))).isTrue();
    }

    @Test
    void labelAsVal_reportsError() {
        symbolTable.define(new Symbol("MY_LABEL", SRC, Symbol.Type.LABEL, new LabelNode("MY_LABEL", SRC, false)));

        CallNode call = callWithVal("MY_LABEL");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("is a label; expected a register, a typed literal or a vector"))).isTrue();
    }

    @Test
    void vectorConstantAsVal_noError() {
        symbolTable.define(new Symbol("EAST", SRC, Symbol.Type.CONSTANT,
                new ConstNode("EAST", SRC, new LiteralNode(new Value.Vector(List.of(1L, 0L)), SRC), false)));

        CallNode call = callWithVal("EAST");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
    }

    @Test
    void writtenVectorAsVal_noError() {
        CallNode call = new CallNode(
                new IdentifierNode("VAL_PROC", SRC),
                List.of(),
                List.of(new LiteralNode(new Value.Vector(List.of(1L, 0L)), SRC)),
                List.of(),
                List.of(new RegisterNode("%LR0", SRC)),
                SRC);
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
    }

    @Test
    void constantAsLval_reportsError() {
        symbolTable.define(new Symbol("MY_CONST", SRC, Symbol.Type.CONSTANT, new ConstNode("MY_CONST", SRC, new LiteralNode(new Value.Typed("DATA", 1), SRC), false)));

        CallNode call = callWithLval("MY_CONST");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("is a literal; expected a location register or a label"))).isTrue();
    }

    @Test
    void unresolvedIdentifierAsLval_reportsError() {
        CallNode call = callWithLval("SOME_NAME");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("Invalid argument: 'SOME_NAME' for LVAL"))).isTrue();
    }

    private CallNode callWithRef(String argName) {
        // MY_PROC has 1 REF + 1 LREF — provide a dummy LREF to satisfy count validation
        return new CallNode(
                new IdentifierNode("MY_PROC", SRC),
                List.of(new IdentifierNode(argName, SRC)),
                List.of(),
                List.of(new RegisterNode("%LR0", SRC)),
                List.of(),
                SRC);
    }

    private CallNode callWithVal(String argName) {
        // VAL_PROC has 1 VAL + 1 LVAL; a location register satisfies the LVAL count
        return new CallNode(
                new IdentifierNode("VAL_PROC", SRC),
                List.of(),
                List.of(new IdentifierNode(argName, SRC)),
                List.of(),
                List.of(new RegisterNode("%LR0", SRC)),
                SRC);
    }

    private CallNode callWithLval(String argName) {
        return new CallNode(
                new IdentifierNode("VAL_PROC", SRC),
                List.of(),
                List.of(new LiteralNode(new Value.Number(1), SRC)),
                List.of(),
                List.of(new IdentifierNode(argName, SRC)),
                SRC);
    }

    private CallNode callWithLref(String argName) {
        // MY_PROC has 1 REF + 1 LREF — provide a dummy REF to satisfy count validation
        return new CallNode(
                new IdentifierNode("MY_PROC", SRC),
                List.of(new RegisterNode("%DR0", SRC)),
                List.of(),
                List.of(new IdentifierNode(argName, SRC)),
                List.of(),
                SRC);
    }
}
