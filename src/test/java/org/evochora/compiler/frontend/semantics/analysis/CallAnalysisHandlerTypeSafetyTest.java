package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.proc.CallAnalysisHandler;
import org.evochora.compiler.features.proc.CallNode;
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

    private static final SourceInfo SRC = new SourceInfo("test.s", 1, 0);

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        handler = new CallAnalysisHandler();
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
                .anyMatch(d -> d.message().contains("data register alias"))).isTrue();
    }

    @Test
    void locationAliasAsRef_reportsError() {
        RegNode reg = new RegNode("X", "%LR0", SRC);
        symbolTable.define(new Symbol("X", SRC, Symbol.Type.REGISTER_ALIAS_LOCATION, reg));

        CallNode call = callWithRef("X");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("location register alias"))).isTrue();
    }

    @Test
    void dataParameterAsLref_reportsError() {
        symbolTable.define(new Symbol("P", SRC, Symbol.Type.PARAMETER_DATA));

        CallNode call = callWithLref("P");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("data parameter"))).isTrue();
    }

    @Test
    void locationParameterAsRef_reportsError() {
        symbolTable.define(new Symbol("P", SRC, Symbol.Type.PARAMETER_LOCATION));

        CallNode call = callWithRef("P");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("location parameter"))).isTrue();
    }

    @Test
    void labelAsRef_reportsError() {
        symbolTable.define(new Symbol("MY_LABEL", SRC, Symbol.Type.LABEL));

        CallNode call = callWithRef("MY_LABEL");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("must resolve to a data register"))).isTrue();
    }

    @Test
    void constantAsLref_reportsError() {
        symbolTable.define(new Symbol("MY_CONST", SRC, Symbol.Type.CONSTANT));

        CallNode call = callWithLref("MY_CONST");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("must resolve to a location register"))).isTrue();
    }

    @Test
    void moduleAliasAsRef_reportsError() {
        symbolTable.define(new Symbol("MATH", SRC, Symbol.Type.MODULE_ALIAS));

        CallNode call = callWithRef("MATH");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("Module alias"))).isTrue();
    }

    @Test
    void moduleAliasAsLref_reportsError() {
        symbolTable.define(new Symbol("MATH", SRC, Symbol.Type.MODULE_ALIAS));

        CallNode call = callWithLref("MATH");
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("Module alias"))).isTrue();
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
    void unresolvedIdentifierAsVal_noError() {
        // Redefine proc with 1 VAL param instead of REF
        ProcedureNode proc = new ProcedureNode("VAL_PROC", true,
                List.of(),
                List.of(new ProcedureNode.ParamDecl("V", SRC)),
                List.of(),
                List.of(),
                List.of(), SRC);
        symbolTable.define(new Symbol("VAL_PROC", SRC, Symbol.Type.PROCEDURE, proc, true));

        CallNode call = new CallNode(
                new IdentifierNode("VAL_PROC", SRC),
                List.of(),
                List.of(new IdentifierNode("SOME_LABEL", SRC)),
                List.of(),
                List.of(),
                SRC);
        handler.analyze(call, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
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
