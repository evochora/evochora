package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.reg.RegAnalysisHandler;
import org.evochora.compiler.features.reg.RegNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.runtime.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RegAnalysisHandler: a .REG directive defines an alias of the register's kind,
 * data or location. Whether the register exists is settled before the node reaches the handler.
 */
@Tag("unit")
class RegAnalysisHandlerTest {

    private RegAnalysisHandler handler;
    private SymbolTable symbolTable;
    private DiagnosticsEngine diagnostics;

    private static final SourceInfo TEST_SOURCE = new SourceInfo("test.s", 1, 1);

    @BeforeEach
    void setUp() {
        handler = new RegAnalysisHandler(new RuntimeInstructionSetAdapter());
        diagnostics = new DiagnosticsEngine();
        symbolTable = new SymbolTable(diagnostics);
    }

    @Test
    void testValidDataRegister() {
        RegNode regNode = new RegNode("COUNTER", "%DR0", TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertFalse(diagnostics.hasErrors());
        assertTrue(symbolTable.resolve("COUNTER", "test.s").isPresent());
        assertEquals(Symbol.Type.REGISTER_ALIAS_DATA, symbolTable.resolve("COUNTER", "test.s").get().symbol().type());
    }

    @Test
    void testValidLocationRegister() {
        RegNode regNode = new RegNode("POSITION", "%LR0", TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertFalse(diagnostics.hasErrors());
        assertTrue(symbolTable.resolve("POSITION", "test.s").isPresent());
        assertEquals(Symbol.Type.REGISTER_ALIAS_LOCATION, symbolTable.resolve("POSITION", "test.s").get().symbol().type());
    }

    @Test
    void testValidLocationRegisterMaxIndex() {
        RegNode regNode = new RegNode("TARGET", "%LR" + (Config.NUM_LOCATION_REGISTERS - 1), TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertFalse(diagnostics.hasErrors());
        assertTrue(symbolTable.resolve("TARGET", "test.s").isPresent());
    }

    @Test
    void testValidPdrRegister() {
        RegNode regNode = new RegNode("TMP", "%PDR0", TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertFalse(diagnostics.hasErrors());
        assertTrue(symbolTable.resolve("TMP", "test.s").isPresent());
        assertEquals(Symbol.Type.REGISTER_ALIAS_DATA, symbolTable.resolve("TMP", "test.s").get().symbol().type());
    }
}
