package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.module.DependencyGraph;
import org.evochora.compiler.frontend.module.IDependencyInfo;
import org.evochora.compiler.frontend.module.ModulePlacement;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.frontend.module.ModuleContextTracker;
import org.evochora.compiler.model.symbols.SymbolTable;

import java.util.List;
import java.util.Optional;

/**
 * Performs semantic analysis on the AST. This includes tasks like symbol table management,
 * type checking (in a broader sense), and ensuring that the program logic is sound.
 * It operates by traversing the AST and dispatching nodes to specific handlers.
 *
 * <p>In multi-module compilation, the analyzer receives a {@link DependencyGraph} at construction
 * time and sets up module relationships (imports, requires, USING bindings) in the symbol table
 * before analysis begins. During traversal, it switches module context using PushCtxNode/PopCtxNode
 * markers carrying alias chains from the preprocessor.</p>
 */
public class SemanticAnalyzer {

    private final DiagnosticsEngine diagnostics;
    private final SymbolTable symbolTable;
    private final DependencyGraph graph;
    private final String rootAliasChain;
    private final AnalysisHandlerRegistry registry;
    private final ModuleSetupRegistry setupRegistry;
    private final ModuleContextTracker contextTracker;
    private final ScopeTracker scopeTracker;

    /**
     * Constructs a semantic analyzer with an externally built analysis registry. Nothing is
     * analyzed and the symbol table is not touched until {@link #analyze(List)} is called.
     *
     * @param diagnostics    The diagnostics engine for reporting errors.
     * @param symbolTable    The symbol table to use for analysis.
     * @param graph          The dependency graph from Phase 0. Null for single-file compilation.
     * @param rootAliasChain The alias chain for the root module (e.g., "MAIN"). Null when graph is null.
     * @param registry       The pre-built analysis handler registry.
     * @param setupRegistry  The registry of handlers that turn the graph's dependency data
     *                       into module relationships in the symbol table.
     */
    public SemanticAnalyzer(DiagnosticsEngine diagnostics, SymbolTable symbolTable,
                            DependencyGraph graph, String rootAliasChain, AnalysisHandlerRegistry registry,
                            ModuleSetupRegistry setupRegistry) {
        this.diagnostics = diagnostics;
        this.symbolTable = symbolTable;
        this.graph = graph;
        this.rootAliasChain = rootAliasChain;
        this.contextTracker = new ModuleContextTracker(symbolTable);
        this.scopeTracker = new ScopeTracker(symbolTable);
        this.registry = registry;
        this.setupRegistry = setupRegistry;
    }

    /**
     * Analyzes the given list of AST statements.
     * This is the main entry point for the semantic analysis phase.
     * It first sets up the module relationships from the dependency graph, if there is one,
     * then performs two passes: one to collect top-level symbols (labels, procedures),
     * and a second to analyze the statements in detail. The second pass resolves names against
     * the table the first one built; when the first pass reported an error, the table lacks what
     * it rejected, and every resolution that misses it would only repeat the error as a
     * consequence. So the second pass runs only when the first one was clean.
     *
     * @param statements The list of top-level AST nodes to analyze.
     */
    public void analyze(List<AstNode> statements) {
        if (graph != null && rootAliasChain != null) {
            setupModuleRelationships(graph, rootAliasChain);
        }
        collectSymbols(statements);
        if (diagnostics.hasErrors()) {
            return;
        }
        analyzeStatements(statements);
    }

    /**
     * Pass 1: Collects top-level symbols (labels, procedures, constants) from the AST.
     *
     * @param statements The list of top-level AST nodes to collect symbols from.
     */
    private void collectSymbols(List<AstNode> statements) {
        collectLabels(statements);
    }

    /**
     * Pass 2: Analyzes the statements in detail (type checking, reference resolution).
     *
     * @param statements The list of top-level AST nodes to analyze.
     */
    private void analyzeStatements(List<AstNode> statements) {
        symbolTable.resetScope();
        traverseAndAnalyze(statements);
    }

    private void collectLabels(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            if (node == null) continue;
            switchModuleContext(node);
            Optional<ISymbolCollector> collector = registry.resolveCollector(node.getClass());
            collector.ifPresent(c -> c.collect(node, symbolTable, diagnostics));
            collectLabels(node.getChildren());
            collector.ifPresent(c -> c.collectAfterChildren(node, symbolTable, diagnostics));
        }
    }

    /**
     * Walks the nodes in text order and runs the registered handler of each. A node that opened a
     * level in pass 1 has that level registered as its scope; the {@link ScopeTracker} enters it
     * before the node's handler and children and restores the enclosing scope afterwards, so that
     * the names inside the level resolve from the level, whatever feature opened it.
     */
    private void traverseAndAnalyze(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            if (node == null) continue;
            switchModuleContext(node);
            SymbolTable.Scope saved = scopeTracker.enterNode(node);
            Optional<IAnalysisHandler> handler = registry.resolveHandler(node.getClass());
            handler.ifPresent(h -> h.analyze(node, symbolTable, diagnostics));
            traverseAndAnalyze(node.getChildren());
            handler.ifPresent(h -> h.afterChildren(node, symbolTable, diagnostics));
            scopeTracker.leaveNode(saved);
        }
    }

    private void switchModuleContext(AstNode node) {
        contextTracker.handleNode(node);
    }

    /**
     * Sets up module relationships in the symbol table from the dependency graph. Every module
     * placement is registered under the alias chain the dependency scan gave it, the same chain
     * the preprocessor gave its tokens; a file imported more than once is a module once per
     * import.
     */
    private void setupModuleRelationships(DependencyGraph graph, String rootAliasChain) {
        List<ModulePlacement> placements = graph.placements();

        for (ModulePlacement placement : placements) {
            symbolTable.registerModule(placement.aliasChain(), placement.sourcePath());
        }

        // Step 1: Register relationships, imported placements first.
        for (ModulePlacement placement : placements) {
            ModuleSetupContext ctx = new ModuleSetupContext(symbolTable, diagnostics, placement.aliasChain());
            for (IDependencyInfo dep : placement.dependencies()) {
                IDependencySetupHandler<IDependencyInfo> handler = setupRegistry.resolve(dep.getClass());
                if (handler != null) {
                    handler.registerRelationships(dep, ctx);
                }
            }
        }

        // Step 2: Resolve cross-module bindings (USING etc.).
        //
        // Walked from the outermost placement inwards: a module may hand on a dependency it was
        // given itself, and it can only do that once it has been given it. The placements form a
        // tree under the main module, listed with every placement after the placements it imports,
        // so the reversed list puts every placement before them.
        for (ModulePlacement placement : placements.reversed()) {
            ModuleSetupContext ctx = new ModuleSetupContext(symbolTable, diagnostics, placement.aliasChain());
            for (IDependencyInfo dep : placement.dependencies()) {
                IDependencySetupHandler<IDependencyInfo> handler = setupRegistry.resolve(dep.getClass());
                if (handler != null) {
                    handler.resolveBindings(dep, ctx);
                }
            }
        }

        symbolTable.setCurrentModule(rootAliasChain);
    }
}
