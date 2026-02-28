package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.Diagnostic;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.features.importdir.ImportNode;
import org.evochora.compiler.frontend.semantics.ModuleId;
import org.evochora.compiler.frontend.semantics.ModuleScope;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.model.Token;
import org.evochora.compiler.model.TokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ImportAnalysisHandler}, which validates USING clauses on {@code .IMPORT}
 * directives during pass-2 semantic analysis.
 */
class ImportAnalysisHandlerTest {

    private static final String MAIN_FILE = "/test/main.evo";
    private static final String LIB_FILE = "/test/lib.evo";
    private static final String DEP_FILE = "/test/dep.evo";

    private static final ModuleId MAIN_MODULE = new ModuleId(MAIN_FILE);
    private static final ModuleId LIB_MODULE = new ModuleId(LIB_FILE);
    private static final ModuleId DEP_MODULE = new ModuleId(DEP_FILE);

    private DiagnosticsEngine diagnostics;
    private SymbolTable symbolTable;
    private ImportAnalysisHandler handler;

    @BeforeEach
    void setUp() {
        diagnostics = new DiagnosticsEngine();
        symbolTable = new SymbolTable(diagnostics);
        handler = new ImportAnalysisHandler();

        symbolTable.registerModule(MAIN_MODULE, MAIN_FILE);
        symbolTable.registerModule(LIB_MODULE, LIB_FILE);
        symbolTable.registerModule(DEP_MODULE, DEP_FILE);

        // main imports dep as "D" and lib as "LIB"
        ModuleScope mainScope = symbolTable.getModuleScope(MAIN_MODULE).orElseThrow();
        mainScope.imports().put("D", DEP_MODULE);
        mainScope.imports().put("LIB", LIB_MODULE);

        // lib requires "DEP"
        ModuleScope libScope = symbolTable.getModuleScope(LIB_MODULE).orElseThrow();
        libScope.requires().put("DEP", "dep.evo");

        symbolTable.setCurrentModule(MAIN_MODULE);
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
        // .IMPORT "lib.evo" AS LIB USING D AS DEP
        ImportNode node = importNode("lib.evo", "LIB", List.of(
                usingClause("D", "DEP")
        ));

        handler.analyze(node, symbolTable, diagnostics);

        assertNoErrors();
    }

    @Test
    @Tag("unit")
    void unknownSourceAliasReportsError() {
        // .IMPORT "lib.evo" AS LIB USING UNKNOWN AS DEP
        // "UNKNOWN" is not a known import alias in main
        ImportNode node = importNode("lib.evo", "LIB", List.of(
                usingClause("UNKNOWN", "DEP")
        ));

        handler.analyze(node, symbolTable, diagnostics);

        assertErrorContaining("UNKNOWN", "not a known import alias");
    }

    @Test
    @Tag("unit")
    void targetNotMatchingRequireReportsError() {
        // .IMPORT "lib.evo" AS LIB USING D AS WRONG_NAME
        // lib has .REQUIRE "dep.evo" AS DEP, not WRONG_NAME
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

        assertErrorContaining("DEP", "no USING clause provides it");
    }

    @Test
    @Tag("unit")
    void multipleRequiresAllSatisfied() {
        // lib requires both DEP and EXTRA
        ModuleScope libScope = symbolTable.getModuleScope(LIB_MODULE).orElseThrow();
        libScope.requires().put("EXTRA", "extra.evo");

        ModuleId extraModule = new ModuleId("/test/extra.evo");
        symbolTable.registerModule(extraModule, "/test/extra.evo");
        ModuleScope mainScope = symbolTable.getModuleScope(MAIN_MODULE).orElseThrow();
        mainScope.imports().put("E", extraModule);

        // .IMPORT "lib.evo" AS LIB USING D AS DEP USING E AS EXTRA
        ImportNode node = importNode("lib.evo", "LIB", List.of(
                usingClause("D", "DEP"),
                usingClause("E", "EXTRA")
        ));

        handler.analyze(node, symbolTable, diagnostics);

        assertNoErrors();
    }

    @Test
    @Tag("unit")
    void multipleRequiresPartiallySatisfiedReportsError() {
        // lib requires both DEP and EXTRA, but only DEP is provided
        ModuleScope libScope = symbolTable.getModuleScope(LIB_MODULE).orElseThrow();
        libScope.requires().put("EXTRA", "extra.evo");

        ImportNode node = importNode("lib.evo", "LIB", List.of(
                usingClause("D", "DEP")
        ));

        handler.analyze(node, symbolTable, diagnostics);

        assertErrorContaining("EXTRA", "no USING clause provides it");
    }

    // --- Helper methods ---

    private ImportNode importNode(String path, String alias, List<ImportNode.UsingClause> usings) {
        Token pathToken = new Token(TokenType.STRING, "\"" + path + "\"", path, 1, 0, MAIN_FILE);
        Token aliasToken = new Token(TokenType.IDENTIFIER, alias, null, 1, 20, MAIN_FILE);
        return new ImportNode(pathToken, aliasToken, usings);
    }

    private ImportNode.UsingClause usingClause(String sourceAlias, String targetAlias) {
        Token source = new Token(TokenType.IDENTIFIER, sourceAlias, null, 1, 30, MAIN_FILE);
        Token target = new Token(TokenType.IDENTIFIER, targetAlias, null, 1, 40, MAIN_FILE);
        return new ImportNode.UsingClause(source, target);
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
