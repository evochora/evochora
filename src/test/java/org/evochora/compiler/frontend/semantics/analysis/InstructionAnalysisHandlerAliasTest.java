package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.instruction.InstructionAnalysisHandler;
import org.evochora.compiler.features.reg.RegNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
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
 * Tests for register alias and module alias type safety in {@link InstructionAnalysisHandler}.
 * Verifies that data register aliases are only accepted in REGISTER positions, location
 * register aliases only in LOCATION_REGISTER positions, and module aliases are always rejected.
 */
@Tag("unit")
class InstructionAnalysisHandlerAliasTest {

    private InstructionAnalysisHandler handler;
    private SymbolTable symbolTable;
    private DiagnosticsEngine diagnostics;

    private static final SourceInfo SRC = new SourceInfo("test.s", 1, 0);

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        handler = new InstructionAnalysisHandler();
        diagnostics = new DiagnosticsEngine();
        symbolTable = new SymbolTable(diagnostics);
        symbolTable.registerModule("TEST", "test.s");
        symbolTable.setCurrentModule("TEST");
    }

    @Test
    void dataAliasInDataInstruction_noError() {
        RegNode regNode = new RegNode("COUNTER", "%DR0", SRC);
        symbolTable.define(new Symbol("COUNTER", SRC, Symbol.Type.REGISTER_ALIAS_DATA, regNode));

        // SETI expects (REGISTER, IMMEDIATE)
        InstructionNode instr = new InstructionNode("SETI",
                List.of(new IdentifierNode("COUNTER", SRC), new TypedLiteralNode("DATA", 42, SRC)), SRC);

        handler.analyze(instr, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
    }

    @Test
    void locationAliasInLocationInstruction_noError() {
        RegNode regNode = new RegNode("POS", "%LR0", SRC);
        symbolTable.define(new Symbol("POS", SRC, Symbol.Type.REGISTER_ALIAS_LOCATION, regNode));

        // SKLR expects (LOCATION_REGISTER)
        InstructionNode instr = new InstructionNode("SKLR",
                List.of(new IdentifierNode("POS", SRC)), SRC);

        handler.analyze(instr, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
    }

    @Test
    void locationAliasInDataInstruction_reportsError() {
        RegNode regNode = new RegNode("POS", "%LR0", SRC);
        symbolTable.define(new Symbol("POS", SRC, Symbol.Type.REGISTER_ALIAS_LOCATION, regNode));

        // SETI expects (REGISTER, IMMEDIATE) — location alias in REGISTER position is wrong
        InstructionNode instr = new InstructionNode("SETI",
                List.of(new IdentifierNode("POS", SRC), new TypedLiteralNode("DATA", 42, SRC)), SRC);

        handler.analyze(instr, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("location register alias"))).isTrue();
    }

    @Test
    void dataAliasInLocationInstruction_reportsError() {
        RegNode regNode = new RegNode("COUNTER", "%DR0", SRC);
        symbolTable.define(new Symbol("COUNTER", SRC, Symbol.Type.REGISTER_ALIAS_DATA, regNode));

        // CRLR expects (LOCATION_REGISTER) — data alias in LOCATION_REGISTER position is wrong
        InstructionNode instr = new InstructionNode("CRLR",
                List.of(new IdentifierNode("COUNTER", SRC)), SRC);

        handler.analyze(instr, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("data register alias"))).isTrue();
    }

    @Test
    void moduleAliasInInstruction_reportsError() {
        symbolTable.define(new Symbol("MATH", SRC, Symbol.Type.MODULE_ALIAS));

        // SETI expects (REGISTER, IMMEDIATE) — module alias is never valid
        InstructionNode instr = new InstructionNode("SETI",
                List.of(new IdentifierNode("MATH", SRC), new TypedLiteralNode("DATA", 42, SRC)), SRC);

        handler.analyze(instr, symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.message().contains("Module alias"))).isTrue();
    }
}
