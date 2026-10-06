package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.symbols.ModuleScope;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.ResolvedSymbol;
import org.evochora.compiler.model.symbols.SymbolTable;
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

    private static final String MAIN_CHAIN = "MAIN";
    private static final String LIB_CHAIN = "LIB";

    @BeforeEach
    void setUp() {
        diagnostics = new DiagnosticsEngine();
        symbolTable = new SymbolTable(diagnostics);

        // Register both modules using alias chains
        symbolTable.registerModule(MAIN_CHAIN, "/test/main.evo");
        symbolTable.registerModule(LIB_CHAIN, "/test/lib.evo");

        // Set up import relationship: main imports lib as "LIB"
        ModuleScope mainScope = symbolTable.getModuleScope(MAIN_CHAIN).orElseThrow();
        mainScope.addImport("LIB", LIB_CHAIN);

        // Define an exported label in the lib module
        symbolTable.setCurrentModule(LIB_CHAIN);
        Symbol exportedSymbol = new Symbol("HARVEST", new SourceInfo("/test/lib.evo", 1, 0, "", 0), Symbol.Type.LABEL, null, true);
        symbolTable.define(exportedSymbol);

        // Define a non-exported label in the lib module
        Symbol privateSymbol = new Symbol("INTERNAL", new SourceInfo("/test/lib.evo", 2, 0, "", 0), Symbol.Type.LABEL, null, false);
        symbolTable.define(privateSymbol);
    }

    @Test
    @Tag("unit")
    void qualifiedNameResolvesExportedSymbol() {
        symbolTable.setCurrentModule(MAIN_CHAIN);
        Optional<ResolvedSymbol> result = symbolTable.resolve("LIB.HARVEST", new SourceInfo("/test/main.evo", 1, 0, MAIN_CHAIN, 0)).found();

        assertThat(result).isPresent();
        assertThat(result.get().symbol().name()).isEqualToIgnoringCase("HARVEST");
    }

    @Test
    @Tag("unit")
    void qualifiedNameDoesNotResolveNonExportedSymbol() {
        symbolTable.setCurrentModule(MAIN_CHAIN);

        Optional<ResolvedSymbol> result = symbolTable.resolve("LIB.INTERNAL", new SourceInfo("/test/main.evo", 1, 0, MAIN_CHAIN, 0)).found();

        assertThat(result).isEmpty();
    }

    @Test
    @Tag("unit")
    void unknownAliasDoesNotResolve() {
        symbolTable.setCurrentModule(MAIN_CHAIN);

        Optional<ResolvedSymbol> result = symbolTable.resolve("UNKNOWN.HARVEST", new SourceInfo("/test/main.evo", 1, 0, MAIN_CHAIN, 0)).found();

        assertThat(result).isEmpty();
    }

    @Test
    @Tag("unit")
    void unqualifiedNameFromSameModuleResolves() {
        symbolTable.setCurrentModule(LIB_CHAIN);

        Optional<ResolvedSymbol> result = symbolTable.resolve("HARVEST", new SourceInfo("/test/lib.evo", 1, 0, LIB_CHAIN, 0)).found();

        assertThat(result).isPresent();
    }

    @Test
    @Tag("unit")
    void usingBindingsResolveQualifiedNames() {
        // Set up USING: main has a using binding DEP -> LIB_CHAIN
        ModuleScope mainScope = symbolTable.getModuleScope(MAIN_CHAIN).orElseThrow();
        mainScope.bindUsing("DEP", LIB_CHAIN);

        symbolTable.setCurrentModule(MAIN_CHAIN);

        Optional<ResolvedSymbol> result = symbolTable.resolve("DEP.HARVEST", new SourceInfo("/test/main.evo", 1, 0, MAIN_CHAIN, 0)).found();

        assertThat(result).isPresent();
        assertThat(result.get().symbol().name()).isEqualToIgnoringCase("HARVEST");
    }

    /**
     * The alias of an import is a name of the module level: a label of the same name in a
     * procedure of the importing module is reported by the shadowing check, while the labels of
     * the imported module, filed under its own placement, are not compared with it.
     */
    @Test
    @Tag("unit")
    void aLabelInAProcedureNamedLikeAnImportAliasIsReported() {
        symbolTable.setCurrentModule(MAIN_CHAIN);
        symbolTable.define(new Symbol("LIB", new SourceInfo("/test/main.evo", 1, 0, MAIN_CHAIN, 0), Symbol.Type.MODULE_ALIAS, null));
        symbolTable.define(new Symbol("P", new SourceInfo("/test/main.evo", 2, 0, MAIN_CHAIN, 0), Symbol.Type.PROCEDURE, null));
        symbolTable.enterScope("P");
        symbolTable.define(new Symbol("LIB", new SourceInfo("/test/main.evo", 3, 0, MAIN_CHAIN, 0), Symbol.Type.LABEL, null));
        symbolTable.define(new Symbol("HARVEST", new SourceInfo("/test/main.evo", 4, 0, MAIN_CHAIN, 0), Symbol.Type.LABEL, null));
        symbolTable.leaveScope();

        symbolTable.reportShadowing();

        assertThat(diagnostics.getDiagnostics()).singleElement().satisfies(d -> {
            assertThat(d.lineNumber()).isEqualTo(3);
            assertThat(d.message()).contains("'LIB'").contains("already defined at /test/main.evo:1, on an enclosing level");
        });
    }
}
