package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.TestDiagnostics;
import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.TestSymbolTables;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.constdir.ConstNode;
import org.evochora.compiler.features.instruction.InstructionAnalysisHandler;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.symbols.ExpressionOutcome;
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
 * Tests that {@link InstructionAnalysisHandler} holds a value a constant was computed to against
 * the slot: a position and a molecule type are no operand, wherever they come from.
 */
@Tag("unit")
class InstructionAnalysisHandlerValueTest {

    private static final SourceInfo SRC = new SourceInfo("test.s", 2, 0, "TEST", 0);

    private InstructionAnalysisHandler handler;
    private SymbolTable symbolTable;
    private DiagnosticsEngine diagnostics;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        handler = new InstructionAnalysisHandler(new RuntimeInstructionSetAdapter(), TestRegistries.operations());
        diagnostics = new DiagnosticsEngine();
        symbolTable = TestSymbolTables.symbolTable(diagnostics);
        symbolTable.registerModule("TEST", "test.s");
        symbolTable.setCurrentModule("TEST");
    }

    @Test
    void aConstantWhoseValueIsAPositionIsNoOperand() {
        defineComputed("P", new Value.Position(List.of(1L, 2L)));

        handler.analyze(seti(new IdentifierNode("P", SRC)), symbolTable, diagnostics);

        assertThat(diagnostics.getDiagnostics()).extracting(TestDiagnostics::located)
                .containsExactly("test.s:2: Argument 2 for instruction 'SETI' has the wrong type. Expected LITERAL, but got POSITION.");
    }

    @Test
    void aConstantWhoseValueIsAMoleculeTypeIsNoOperand() {
        defineComputed("T", new Value.Type("DATA"));

        handler.analyze(seti(new IdentifierNode("T", SRC)), symbolTable, diagnostics);

        assertThat(diagnostics.getDiagnostics()).extracting(TestDiagnostics::located)
                .containsExactly("test.s:2: Argument 2 for instruction 'SETI' has the wrong type. Expected LITERAL, but got TYPE.");
    }

    @Test
    void aComputationIsRecordedWithItsValue() {
        defineComputed("W", new Value.Typed("DATA", 7));
        ExpressionNode computation = new ExpressionNode(new NameExpression("W", SRC, SRC.plusColumns(1)), SRC);

        handler.analyze(seti(computation), symbolTable, diagnostics);

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(symbolTable.resultOf(computation)).contains(ExpressionOutcome.of(new Value.Typed("DATA", 7)));
    }

    private void defineComputed(String name, Value value) {
        ConstNode constant = new ConstNode(name, SRC, new ExpressionNode(new NameExpression("X", SRC, SRC.plusColumns(1)), SRC));
        symbolTable.define(new Symbol(name, SRC, Symbol.Type.CONSTANT, constant));
        symbolTable.recordDefinitionValue(constant, ExpressionOutcome.of(value));
    }

    private static InstructionNode seti(org.evochora.compiler.model.ast.AstNode operand) {
        return new InstructionNode("SETI", List.of(new RegisterNode("%DR0", SRC), operand), SRC);
    }
}
