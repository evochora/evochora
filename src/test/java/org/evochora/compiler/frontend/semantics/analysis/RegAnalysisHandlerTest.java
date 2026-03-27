package org.evochora.compiler.frontend.semantics.analysis;

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
 * Tests for RegAnalysisHandler to ensure proper validation of .REG directives
 * for both data registers (%DRx) and location registers (%LRx).
 */
@Tag("unit")
class RegAnalysisHandlerTest {

    private RegAnalysisHandler handler;
    private SymbolTable symbolTable;
    private DiagnosticsEngine diagnostics;

    private static final SourceInfo TEST_SOURCE = new SourceInfo("test.s", 1, 1);

    @BeforeEach
    void setUp() {
        handler = new RegAnalysisHandler();
        diagnostics = new DiagnosticsEngine();
        symbolTable = new SymbolTable(diagnostics);
    }

    @Test
    void testValidDataRegister() {
        RegNode regNode = new RegNode("COUNTER", "%DR0", TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertFalse(diagnostics.hasErrors());
        assertTrue(symbolTable.resolve("COUNTER", "test.s").isPresent());
        assertEquals(Symbol.Type.ALIAS, symbolTable.resolve("COUNTER", "test.s").get().symbol().type());
    }

    @Test
    void testValidLocationRegister() {
        RegNode regNode = new RegNode("POSITION", "%LR0", TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertFalse(diagnostics.hasErrors());
        assertTrue(symbolTable.resolve("POSITION", "test.s").isPresent());
        assertEquals(Symbol.Type.ALIAS, symbolTable.resolve("POSITION", "test.s").get().symbol().type());
    }

    @Test
    void testValidLocationRegisterMaxIndex() {
        RegNode regNode = new RegNode("TARGET", "%LR" + (Config.NUM_LOCATION_REGISTERS - 1), TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertFalse(diagnostics.hasErrors());
        assertTrue(symbolTable.resolve("TARGET", "test.s").isPresent());
    }

    @Test
    void testInvalidLocationRegisterOutOfBounds() {
        RegNode regNode = new RegNode("INVALID", "%LR" + Config.NUM_LOCATION_REGISTERS, TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertTrue(diagnostics.hasErrors());
        assertFalse(symbolTable.resolve("INVALID", "test.s").isPresent());
    }

    @Test
    void testInvalidDataRegisterOutOfBounds() {
        RegNode regNode = new RegNode("INVALID", "%DR" + Config.NUM_DATA_REGISTERS, TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertTrue(diagnostics.hasErrors());
        assertFalse(symbolTable.resolve("INVALID", "test.s").isPresent());
    }

    @Test
    void testValidPdrRegister() {
        RegNode regNode = new RegNode("TMP", "%PDR0", TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertFalse(diagnostics.hasErrors());
        assertTrue(symbolTable.resolve("TMP", "test.s").isPresent());
    }

    @Test
    void testPdrOutOfBounds() {
        RegNode regNode = new RegNode("INVALID", "%PDR" + Config.NUM_PDR_REGISTERS, TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertTrue(diagnostics.hasErrors());
        assertFalse(symbolTable.resolve("INVALID", "test.s").isPresent());
    }

    @Test
    void testUnknownRegisterBank() {
        RegNode regNode = new RegNode("INVALID", "%XYZ0", TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertTrue(diagnostics.hasErrors());
        assertFalse(symbolTable.resolve("INVALID", "test.s").isPresent());
    }

    @Test
    void testInvalidRegisterFormat() {
        RegNode regNode = new RegNode("INVALID", "%INVALID", TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertTrue(diagnostics.hasErrors());
        assertFalse(symbolTable.resolve("INVALID", "test.s").isPresent());
    }

    @Test
    void testNonRegisterString() {
        RegNode regNode = new RegNode("INVALID", "NOT_A_REGISTER", TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertTrue(diagnostics.hasErrors());
        assertFalse(symbolTable.resolve("INVALID", "test.s").isPresent());
    }

    @Test
    void testForbiddenFdrRegisterRejected() {
        RegNode regNode = new RegNode("PARAM", "%FDR0", TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertTrue(diagnostics.hasErrors());
        assertFalse(symbolTable.resolve("PARAM", "test.s").isPresent());
    }

    @Test
    void testForbiddenFlrRegisterRejected() {
        RegNode regNode = new RegNode("LOC_PARAM", "%FLR0", TEST_SOURCE);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertTrue(diagnostics.hasErrors());
        assertFalse(symbolTable.resolve("LOC_PARAM", "test.s").isPresent());
    }
}
