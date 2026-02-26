package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;
import org.evochora.compiler.frontend.semantics.analysis.ISymbolCollector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Performs semantic analysis on the AST. This includes tasks like symbol table management,
 * type checking (in a broader sense), and ensuring that the program logic is sound.
 * It operates by traversing the AST and dispatching nodes to specific handlers.
 */
public class SemanticAnalyzer {

    private final DiagnosticsEngine diagnostics;
    private final SymbolTable symbolTable;
    private final AnalysisHandlerRegistry registry;
    private final Map<AstNode, SymbolTable.Scope> scopeMap = new HashMap<>();

    /**
     * Constructs a new semantic analyzer.
     * @param diagnostics The diagnostics engine for reporting errors.
     * @param symbolTable The symbol table to use for analysis.
     */
    public SemanticAnalyzer(DiagnosticsEngine diagnostics, SymbolTable symbolTable) {
        this.diagnostics = diagnostics;
        this.symbolTable = symbolTable;
        this.registry = AnalysisHandlerRegistry.initializeWithDefaults(symbolTable, scopeMap, diagnostics);
    }

    /**
     * Analyzes the given list of AST statements.
     * This is the main entry point for the semantic analysis phase.
     * It performs two passes: one to collect top-level symbols (labels, procedures),
     * and a second to analyze the statements in detail.
     * @param statements The list of top-level AST nodes to analyze.
     */
    public void analyze(List<AstNode> statements) {
        collectLabels(statements);
        symbolTable.resetScope();
        traverseAndAnalyze(statements);
    }

    private void collectLabels(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            if (node == null) continue;
            Optional<ISymbolCollector> collector = registry.resolveCollector(node.getClass());
            collector.ifPresent(c -> c.collect(node, symbolTable, diagnostics));
            collectLabels(node.getChildren());
            collector.ifPresent(c -> c.collectAfterChildren(node, symbolTable, diagnostics));
        }
    }

    private void traverseAndAnalyze(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            if (node == null) continue;
            Optional<IAnalysisHandler> handler = registry.resolveHandler(node.getClass());
            handler.ifPresent(h -> h.analyze(node, symbolTable, diagnostics));
            traverseAndAnalyze(node.getChildren());
            handler.ifPresent(h -> h.afterChildren(node, symbolTable, diagnostics));
        }
    }

    /**
     * Gets the scope map that maps AST nodes to their scopes.
     * @return The scope map
     */
    public Map<AstNode, SymbolTable.Scope> getScopeMap() {
        return scopeMap;
    }

    /**
     * Gets the handler registry for external registration of additional handlers.
     * @return The analysis handler registry
     */
    public AnalysisHandlerRegistry getRegistry() {
        return registry;
    }
}
