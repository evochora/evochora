package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests cross-module symbol resolution through the module-aware SymbolTable.
 * Verifies that qualified names (ALIAS.SYMBOL) resolve correctly based on
 * import relationships and export visibility.
 */
public class ModuleVisibilityTest {

    private DiagnosticsEngine diagnostics;
    private SymbolTable symbolTable;

    private static final ModuleId MAIN_MODULE = new ModuleId("/test/main.evo");
    private static final ModuleId LIB_MODULE = new ModuleId("/test/lib.evo");

    @BeforeEach
    void setUp() {
        diagnostics = new DiagnosticsEngine();
        symbolTable = new SymbolTable(diagnostics);

        // Register both modules
        symbolTable.registerModule(MAIN_MODULE, "/test/main.evo");
        symbolTable.registerModule(LIB_MODULE, "/test/lib.evo");

        // Set up import relationship: main imports lib as "LIB"
        ModuleScope mainScope = symbolTable.getModuleScope(MAIN_MODULE).orElseThrow();
        mainScope.imports().put("LIB", LIB_MODULE);

        // Define an exported label in the lib module
        symbolTable.setCurrentModule(LIB_MODULE);
        Token exportedLabelToken = new Token(TokenType.IDENTIFIER, "HARVEST", null, 1, 0, "/test/lib.evo");
        Symbol exportedSymbol = new Symbol(exportedLabelToken, Symbol.Type.LABEL, null, true);
        symbolTable.define(exportedSymbol);
        symbolTable.registerLabelMeta(exportedLabelToken, true);

        // Define a non-exported label in the lib module
        Token privateLabelToken = new Token(TokenType.IDENTIFIER, "INTERNAL", null, 2, 0, "/test/lib.evo");
        Symbol privateSymbol = new Symbol(privateLabelToken, Symbol.Type.LABEL, null, false);
        symbolTable.define(privateSymbol);
        symbolTable.registerLabelMeta(privateLabelToken, false);
    }

    @Test
    @Tag("unit")
    void qualifiedNameResolvesExportedSymbol() {
        symbolTable.setCurrentModule(MAIN_MODULE);
        Optional<Symbol> result = symbolTable.resolve("LIB.HARVEST", "/test/main.evo");

        assertThat(result).isPresent();
        assertThat(result.get().name().text()).isEqualToIgnoringCase("HARVEST");
    }

    @Test
    @Tag("unit")
    void qualifiedNameDoesNotResolveNonExportedSymbol() {
        symbolTable.setCurrentModule(MAIN_MODULE);

        Optional<Symbol> result = symbolTable.resolve("LIB.INTERNAL", "/test/main.evo");

        assertThat(result).isEmpty();
    }

    @Test
    @Tag("unit")
    void unknownAliasDoesNotResolve() {
        symbolTable.setCurrentModule(MAIN_MODULE);

        Optional<Symbol> result = symbolTable.resolve("UNKNOWN.HARVEST", "/test/main.evo");

        assertThat(result).isEmpty();
    }

    @Test
    @Tag("unit")
    void unqualifiedNameFromSameModuleResolves() {
        symbolTable.setCurrentModule(LIB_MODULE);

        Optional<Symbol> result = symbolTable.resolve("HARVEST", "/test/lib.evo");

        assertThat(result).isPresent();
    }

    @Test
    @Tag("unit")
    void usingBindingsResolveQualifiedNames() {
        // Set up USING: main has a using binding DEP -> LIB_MODULE
        ModuleScope mainScope = symbolTable.getModuleScope(MAIN_MODULE).orElseThrow();
        mainScope.usingBindings().put("DEP", LIB_MODULE);

        symbolTable.setCurrentModule(MAIN_MODULE);

        Optional<Symbol> result = symbolTable.resolve("DEP.HARVEST", "/test/main.evo");

        assertThat(result).isPresent();
        assertThat(result.get().name().text()).isEqualToIgnoringCase("HARVEST");
    }
}
