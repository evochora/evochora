package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.Token;
import org.evochora.compiler.model.TokenType;
import org.evochora.compiler.frontend.parser.features.reg.RegNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
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

    @BeforeEach
    void setUp() {
        handler = new RegAnalysisHandler();
        diagnostics = new DiagnosticsEngine();
        symbolTable = new SymbolTable(diagnostics);
    }

    @Test
    void testValidDataRegister() {
        // Test valid data register alias
        Token alias = new Token(TokenType.IDENTIFIER, "COUNTER", null, 1, 1, "test.s");
        Token register = new Token(TokenType.REGISTER, "%DR0", null, 1, 10, "test.s");
        RegNode regNode = new RegNode(alias, register);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertFalse(diagnostics.hasErrors());
        Token counterToken = new Token(TokenType.IDENTIFIER, "COUNTER", null, 1, 1, "test.s");
        assertTrue(symbolTable.resolve(counterToken).isPresent());
        assertEquals(Symbol.Type.ALIAS, symbolTable.resolve(counterToken).get().type());
    }

    @Test
    void testValidLocationRegister() {
        // Test valid location register alias
        Token alias = new Token(TokenType.IDENTIFIER, "POSITION", null, 1, 1, "test.s");
        Token register = new Token(TokenType.REGISTER, "%LR0", null, 1, 12, "test.s");
        RegNode regNode = new RegNode(alias, register);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertFalse(diagnostics.hasErrors());
        Token positionToken = new Token(TokenType.IDENTIFIER, "POSITION", null, 1, 1, "test.s");
        assertTrue(symbolTable.resolve(positionToken).isPresent());
        assertEquals(Symbol.Type.ALIAS, symbolTable.resolve(positionToken).get().type());
    }

    @Test
    void testValidLocationRegisterMaxIndex() {
        // Test valid location register with maximum index
        Token alias = new Token(TokenType.IDENTIFIER, "TARGET", null, 1, 1, "test.s");
        Token register = new Token(TokenType.REGISTER, "%LR" + (Config.NUM_LOCATION_REGISTERS - 1), null, 1, 10, "test.s");
        RegNode regNode = new RegNode(alias, register);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertFalse(diagnostics.hasErrors());
        Token targetToken = new Token(TokenType.IDENTIFIER, "TARGET", null, 1, 1, "test.s");
        assertTrue(symbolTable.resolve(targetToken).isPresent());
    }

    @Test
    void testInvalidLocationRegisterOutOfBounds() {
        // Test location register out of bounds
        Token alias = new Token(TokenType.IDENTIFIER, "INVALID", null, 1, 1, "test.s");
        Token register = new Token(TokenType.REGISTER, "%LR" + Config.NUM_LOCATION_REGISTERS, null, 1, 10, "test.s");
        RegNode regNode = new RegNode(alias, register);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertTrue(diagnostics.hasErrors());
        Token invalidToken = new Token(TokenType.IDENTIFIER, "INVALID", null, 1, 1, "test.s");
        assertFalse(symbolTable.resolve(invalidToken).isPresent());
    }

    @Test
    void testInvalidDataRegisterOutOfBounds() {
        // Test data register out of bounds
        Token alias = new Token(TokenType.IDENTIFIER, "INVALID", null, 1, 1, "test.s");
        Token register = new Token(TokenType.REGISTER, "%DR" + Config.NUM_DATA_REGISTERS, null, 1, 10, "test.s");
        RegNode regNode = new RegNode(alias, register);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertTrue(diagnostics.hasErrors());
        Token invalidToken = new Token(TokenType.IDENTIFIER, "INVALID", null, 1, 1, "test.s");
        assertFalse(symbolTable.resolve(invalidToken).isPresent());
    }

    @Test
    void testInvalidRegisterType() {
        // Test unsupported register type (e.g., %PRx)
        Token alias = new Token(TokenType.IDENTIFIER, "INVALID", null, 1, 1, "test.s");
        Token register = new Token(TokenType.REGISTER, "%PR0", null, 1, 10, "test.s");
        RegNode regNode = new RegNode(alias, register);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertTrue(diagnostics.hasErrors());
        Token invalidToken = new Token(TokenType.IDENTIFIER, "INVALID", null, 1, 1, "test.s");
        assertFalse(symbolTable.resolve(invalidToken).isPresent());
    }

    @Test
    void testInvalidRegisterFormat() {
        // Test malformed register
        Token alias = new Token(TokenType.IDENTIFIER, "INVALID", null, 1, 1, "test.s");
        Token register = new Token(TokenType.REGISTER, "%INVALID", null, 1, 10, "test.s");
        RegNode regNode = new RegNode(alias, register);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertTrue(diagnostics.hasErrors());
        Token invalidToken = new Token(TokenType.IDENTIFIER, "INVALID", null, 1, 1, "test.s");
        assertFalse(symbolTable.resolve(invalidToken).isPresent());
    }

    @Test
    void testNonRegisterToken() {
        // Test non-register token
        Token alias = new Token(TokenType.IDENTIFIER, "INVALID", null, 1, 1, "test.s");
        Token register = new Token(TokenType.IDENTIFIER, "NOT_A_REGISTER", null, 1, 10, "test.s");
        RegNode regNode = new RegNode(alias, register);

        handler.analyze(regNode, symbolTable, diagnostics);

        assertTrue(diagnostics.hasErrors());
        Token invalidToken = new Token(TokenType.IDENTIFIER, "INVALID", null, 1, 1, "test.s");
        assertFalse(symbolTable.resolve(invalidToken).isPresent());
    }
}
