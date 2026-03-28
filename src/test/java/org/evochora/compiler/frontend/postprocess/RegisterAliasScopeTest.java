package org.evochora.compiler.frontend.postprocess;

import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.proc.ProcedureNode;
import org.evochora.compiler.features.reg.RegNode;
import org.evochora.compiler.model.ModuleContextTracker;
import org.evochora.compiler.model.ScopeTracker;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests scope-aware register alias resolution in the {@link AstPostProcessor}.
 * Verifies that proc-scoped aliases, shadowing, and scope inheritance work correctly
 * when aliases are resolved via the {@link SymbolTable} with {@link ScopeTracker}.
 */
@Tag("unit")
class RegisterAliasScopeTest {

    private SymbolTable symbolTable;
    private DiagnosticsEngine diagnostics;

    private static final SourceInfo SRC = new SourceInfo("test.s", 1, 0);

    @BeforeEach
    void setUp() {
        diagnostics = new DiagnosticsEngine();
        symbolTable = new SymbolTable(diagnostics);
        symbolTable.registerModule("", "test.s");
        symbolTable.setCurrentModule("");
    }

    @Test
    void twoProcsSameAliasName_independentResolution() {
        // Module-level: no COUNTER alias
        // Proc A: .REG %COUNTER %DR0
        // Proc B: .REG %COUNTER %DR1
        RegNode regA = new RegNode("COUNTER", "%DR0", SRC);
        RegNode regB = new RegNode("COUNTER", "%DR1", SRC);

        IdentifierNode useInA = new IdentifierNode("COUNTER", SRC);
        IdentifierNode useInB = new IdentifierNode("COUNTER", SRC);

        InstructionNode instrA = new InstructionNode("SETI", List.of(useInA), SRC);
        InstructionNode instrB = new InstructionNode("SETI", List.of(useInB), SRC);

        ProcedureNode procA = new ProcedureNode("PROC_A", false, List.of(), List.of(), List.of(), List.of(),
                List.of(regA, instrA), SRC);
        ProcedureNode procB = new ProcedureNode("PROC_B", false, List.of(), List.of(), List.of(), List.of(),
                List.of(regB, instrB), SRC);

        // Set up scopes like ProcedureSymbolCollector does
        symbolTable.define(new Symbol("PROC_A", SRC, Symbol.Type.PROCEDURE, procA));
        SymbolTable.Scope scopeA = symbolTable.enterScope("PROC_A");
        symbolTable.registerNodeScope(procA, scopeA);
        symbolTable.define(new Symbol("COUNTER", SRC, Symbol.Type.REGISTER_ALIAS_DATA, regA));
        symbolTable.leaveScope();

        symbolTable.define(new Symbol("PROC_B", SRC, Symbol.Type.PROCEDURE, procB));
        SymbolTable.Scope scopeB = symbolTable.enterScope("PROC_B");
        symbolTable.registerNodeScope(procB, scopeB);
        symbolTable.define(new Symbol("COUNTER", SRC, Symbol.Type.REGISTER_ALIAS_DATA, regB));
        symbolTable.leaveScope();

        // Process
        AstPostProcessor processor = createProcessor();
        AstNode resultA = processor.process(procA);
        AstNode resultB = processor.process(procB);

        // Proc A: COUNTER → %DR0
        ProcedureNode resultProcA = (ProcedureNode) resultA;
        InstructionNode resultInstrA = (InstructionNode) resultProcA.body().get(1);
        assertThat(resultInstrA.arguments().get(0)).isInstanceOf(RegisterNode.class);
        assertThat(((RegisterNode) resultInstrA.arguments().get(0)).getName()).isEqualTo("%DR0");

        // Proc B: COUNTER → %DR1
        ProcedureNode resultProcB = (ProcedureNode) resultB;
        InstructionNode resultInstrB = (InstructionNode) resultProcB.body().get(1);
        assertThat(resultInstrB.arguments().get(0)).isInstanceOf(RegisterNode.class);
        assertThat(((RegisterNode) resultInstrB.arguments().get(0)).getName()).isEqualTo("%DR1");
    }

    @Test
    void shadowingModuleLevelAlias() {
        // Module-level: .REG %X %DR0
        // Proc: .REG %X %PDR0
        // Inside proc: X → %PDR0
        // Outside proc: X → %DR0
        RegNode moduleReg = new RegNode("X", "%DR0", SRC);
        RegNode procReg = new RegNode("X", "%PDR0", SRC);

        symbolTable.define(new Symbol("X", SRC, Symbol.Type.REGISTER_ALIAS_DATA, moduleReg));

        IdentifierNode useOutside = new IdentifierNode("X", SRC);
        IdentifierNode useInside = new IdentifierNode("X", SRC);
        InstructionNode instrInside = new InstructionNode("SETI", List.of(useInside), SRC);

        ProcedureNode proc = new ProcedureNode("MY_PROC", false, List.of(), List.of(), List.of(), List.of(),
                List.of(procReg, instrInside), SRC);

        symbolTable.define(new Symbol("MY_PROC", SRC, Symbol.Type.PROCEDURE, proc));
        SymbolTable.Scope procScope = symbolTable.enterScope("MY_PROC");
        symbolTable.registerNodeScope(proc, procScope);
        symbolTable.define(new Symbol("X", SRC, Symbol.Type.REGISTER_ALIAS_DATA, procReg));
        symbolTable.leaveScope();

        AstPostProcessor processor = createProcessor();

        // Outside proc: X → %DR0
        AstNode outsideResult = processor.process(useOutside);
        assertThat(outsideResult).isInstanceOf(RegisterNode.class);
        assertThat(((RegisterNode) outsideResult).getName()).isEqualTo("%DR0");

        // Inside proc: X → %PDR0 (shadowed)
        AstNode procResult = processor.process(proc);
        ProcedureNode resultProc = (ProcedureNode) procResult;
        InstructionNode resultInstr = (InstructionNode) resultProc.body().get(1);
        assertThat(resultInstr.arguments().get(0)).isInstanceOf(RegisterNode.class);
        assertThat(((RegisterNode) resultInstr.arguments().get(0)).getName()).isEqualTo("%PDR0");
    }

    @Test
    void moduleLevelAliasVisibleInsideProc() {
        // Module-level: .REG %GLOBAL %DR7
        // Proc: no .REG for GLOBAL — should inherit from module scope
        RegNode moduleReg = new RegNode("GLOBAL", "%DR7", SRC);
        symbolTable.define(new Symbol("GLOBAL", SRC, Symbol.Type.REGISTER_ALIAS_DATA, moduleReg));

        IdentifierNode useInProc = new IdentifierNode("GLOBAL", SRC);
        InstructionNode instrInProc = new InstructionNode("SETI", List.of(useInProc), SRC);

        ProcedureNode proc = new ProcedureNode("MY_PROC", false, List.of(), List.of(), List.of(), List.of(),
                List.of(instrInProc), SRC);

        symbolTable.define(new Symbol("MY_PROC", SRC, Symbol.Type.PROCEDURE, proc));
        SymbolTable.Scope procScope = symbolTable.enterScope("MY_PROC");
        symbolTable.registerNodeScope(proc, procScope);
        // No GLOBAL alias defined in proc scope — should inherit
        symbolTable.leaveScope();

        AstPostProcessor processor = createProcessor();
        AstNode procResult = processor.process(proc);

        ProcedureNode resultProc = (ProcedureNode) procResult;
        InstructionNode resultInstr = (InstructionNode) resultProc.body().get(0);
        assertThat(resultInstr.arguments().get(0)).isInstanceOf(RegisterNode.class);
        assertThat(((RegisterNode) resultInstr.arguments().get(0)).getName()).isEqualTo("%DR7");
    }

    private AstPostProcessor createProcessor() {
        return new AstPostProcessor(
                symbolTable,
                new ModuleContextTracker(symbolTable),
                new ScopeTracker(symbolTable),
                TestRegistries.postProcessRegistry());
    }
}
