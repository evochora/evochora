package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.Diagnostic;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.importdir.ImportAnalysisHandler;
import org.evochora.compiler.features.importdir.ImportNode;
import org.evochora.compiler.features.importdir.Requirements;
import org.evochora.compiler.model.symbols.ModuleScope;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ImportAnalysisHandler}, which validates USING clauses on {@code .IMPORT}
 * directives during pass-2 semantic analysis. The scopes are built as pass 1 leaves them: the
 * aliases bound, the requirements declared.
 */
class ImportAnalysisHandlerTest {

    private static final String MAIN_FILE = "/test/main.evo";
    private static final String LIB_FILE = "/test/lib.evo";
    private static final String DEP_FILE = "/test/dep.evo";

    private static final String MAIN_CHAIN = "MAIN";
    private static final String LIB_CHAIN = "LIB";
    private static final String DEP_CHAIN = "D";

    private DiagnosticsEngine diagnostics;
    private SymbolTable symbolTable;
    private ImportAnalysisHandler handler;

    @BeforeEach
    void setUp() {
        diagnostics = new DiagnosticsEngine();
        symbolTable = new SymbolTable(diagnostics);
        handler = new ImportAnalysisHandler();

        symbolTable.registerModule(MAIN_CHAIN, MAIN_FILE);
        symbolTable.registerModule(LIB_CHAIN, LIB_FILE);
        symbolTable.registerModule(DEP_CHAIN, DEP_FILE);

        // main imports dep as "D" and lib as "LIB"
        ModuleScope mainScope = symbolTable.getModuleScope(MAIN_CHAIN).orElseThrow();
        mainScope.bindAlias("D", DEP_CHAIN);
        mainScope.bindAlias("LIB", LIB_CHAIN);

        // lib requires "DEP"
        requirementsOfLib().declareModule("DEP", "dep.evo");

        symbolTable.setCurrentModule(MAIN_CHAIN);
    }

    @Test
    @Tag("unit")
    void importWithoutUsingPassesWhenNoRequires() {
        // Import dep module which has no .REQUIRE declarations
        ImportNode node = importNode("dep.evo", "D", List.of());

        handler.analyze(node, symbolTable, diagnostics);

        assertNoErrors();
    }

    @Test
    @Tag("unit")
    void validUsingClausePassesValidation() {
        // .IMPORT "lib.evo" AS LIB USING MODULE D AS DEP
        ImportNode node = importNode("lib.evo", "LIB", List.of(
                usingClause("D", "DEP")
        ));
        bindInLib("DEP", DEP_CHAIN);

        handler.analyze(node, symbolTable, diagnostics);

        assertNoErrors();
    }

    @Test
    @Tag("unit")
    void unknownSourceAliasReportsError() {
        // .IMPORT "lib.evo" AS LIB USING MODULE UNKNOWN AS DEP
        // "UNKNOWN" is neither imported nor required by main
        ImportNode node = importNode("lib.evo", "LIB", List.of(
                usingClause("UNKNOWN", "DEP")
        ));

        handler.analyze(node, symbolTable, diagnostics);

        assertErrorContaining("UNKNOWN", "neither an import nor a requirement");
    }

    @Test
    @Tag("unit")
    void targetNotMatchingRequireReportsError() {
        // .IMPORT "lib.evo" AS LIB USING MODULE D AS WRONG_NAME
        // lib has .REQUIRE MODULE "dep.evo" AS DEP, not WRONG_NAME
        ImportNode node = importNode("lib.evo", "LIB", List.of(
                usingClause("D", "WRONG_NAME")
        ));

        handler.analyze(node, symbolTable, diagnostics);

        assertErrorContaining("WRONG_NAME", "does not match any .REQUIRE");
    }

    @Test
    @Tag("unit")
    void missingUsingForRequireReportsError() {
        // .IMPORT "lib.evo" AS LIB  (no USING, but lib requires DEP)
        ImportNode node = importNode("lib.evo", "LIB", List.of());

        handler.analyze(node, symbolTable, diagnostics);

        assertErrorContaining("DEP", "add USING MODULE <module> AS");
    }

    @Test
    @Tag("unit")
    void multipleRequiresAllSatisfied() {
        // lib requires both DEP and EXTRA
        requirementsOfLib().declareModule("EXTRA", "extra.evo");

        String extraChain = "E";
        symbolTable.registerModule(extraChain, "/test/extra.evo");
        ModuleScope mainScope = symbolTable.getModuleScope(MAIN_CHAIN).orElseThrow();
        mainScope.bindAlias("E", extraChain);

        // .IMPORT "lib.evo" AS LIB USING MODULE D AS DEP MODULE E AS EXTRA
        ImportNode node = importNode("lib.evo", "LIB", List.of(
                usingClause("D", "DEP"),
                usingClause("E", "EXTRA")
        ));
        bindInLib("DEP", DEP_CHAIN);
        bindInLib("EXTRA", extraChain);

        handler.analyze(node, symbolTable, diagnostics);

        assertNoErrors();
    }

    @Test
    @Tag("unit")
    void multipleRequiresPartiallySatisfiedReportsError() {
        // lib requires both DEP and EXTRA, but only DEP is provided
        requirementsOfLib().declareModule("EXTRA", "extra.evo");

        ImportNode node = importNode("lib.evo", "LIB", List.of(
                usingClause("D", "DEP")
        ));
        bindInLib("DEP", DEP_CHAIN);

        handler.analyze(node, symbolTable, diagnostics);

        assertErrorContaining("EXTRA", "add USING MODULE <module> AS");
    }

    // --- Helper methods ---

    private Requirements requirementsOfLib() {
        return symbolTable.getModuleScope(LIB_CHAIN).orElseThrow().state(Requirements.class, Requirements::new);
    }

    /** Binds a requirement alias of lib as pass 1 binds it for a USING clause whose source is known. */
    private void bindInLib(String alias, String aliasChain) {
        symbolTable.getModuleScope(LIB_CHAIN).orElseThrow().bindAlias(alias, aliasChain);
    }

    private ImportNode importNode(String path, String alias, List<ImportNode.UsingClause> usings) {
        SourceInfo sourceInfo = new SourceInfo(MAIN_FILE, 1, 20, "", 0);
        return new ImportNode(path, alias, usings, false, sourceInfo);
    }

    private ImportNode.UsingClause usingClause(String sourceAlias, String targetAlias) {
        SourceInfo sourceSourceInfo = new SourceInfo(MAIN_FILE, 1, 30, "", 0);
        SourceInfo targetSourceInfo = new SourceInfo(MAIN_FILE, 1, 40, "", 0);
        return new ImportNode.UsingClause(sourceAlias, targetAlias, sourceSourceInfo, targetSourceInfo);
    }

    private void assertNoErrors() {
        assertThat(diagnostics.hasErrors())
                .as("Expected no errors but got: %s", diagnostics.getDiagnostics())
                .isFalse();
    }

    private void assertErrorContaining(String... substrings) {
        assertThat(diagnostics.hasErrors()).isTrue();
        List<Diagnostic> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.type() == Diagnostic.Type.ERROR)
                .toList();
        assertThat(errors).anyMatch(error -> {
            String msg = error.message();
            for (String sub : substrings) {
                if (!msg.toLowerCase().contains(sub.toLowerCase())) return false;
            }
            return true;
        });
    }
}
