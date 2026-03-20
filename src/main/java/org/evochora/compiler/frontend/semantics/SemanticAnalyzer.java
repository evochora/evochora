package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.module.DependencyGraph;
import org.evochora.compiler.frontend.module.IDependencyInfo;
import org.evochora.compiler.frontend.module.ModuleDescriptor;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;
import org.evochora.compiler.frontend.semantics.analysis.ISymbolCollector;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ModuleContextTracker;
import org.evochora.compiler.model.symbols.SymbolTable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final AnalysisHandlerRegistry registry;
    private final ModuleSetupRegistry setupRegistry;
    private final ModuleContextTracker contextTracker;

    /**
     * Constructs a semantic analyzer with an externally built analysis registry.
     *
     * @param diagnostics    The diagnostics engine for reporting errors.
     * @param symbolTable    The symbol table to use for analysis.
     * @param graph          The dependency graph from Phase 0. Null for single-file compilation.
     * @param mainFilePath   The absolute path of the main source file. Null when graph is null.
     * @param rootAliasChain The alias chain for the root module (e.g., "MAIN"). Null when graph is null.
     * @param registry       The pre-built analysis handler registry.
     */
    public SemanticAnalyzer(DiagnosticsEngine diagnostics, SymbolTable symbolTable,
                            DependencyGraph graph, String mainFilePath,
                            String rootAliasChain, AnalysisHandlerRegistry registry,
                            ModuleSetupRegistry setupRegistry) {
        this.diagnostics = diagnostics;
        this.symbolTable = symbolTable;
        this.contextTracker = new ModuleContextTracker(symbolTable);
        this.registry = registry;
        this.setupRegistry = setupRegistry;

        if (graph != null && rootAliasChain != null) {
            setupModuleRelationships(graph, mainFilePath, rootAliasChain);
        }
    }

    /**
     * Analyzes the given list of AST statements.
     * This is the main entry point for the semantic analysis phase.
     * It performs two passes: one to collect top-level symbols (labels, procedures),
     * and a second to analyze the statements in detail.
     *
     * @param statements The list of top-level AST nodes to analyze.
     */
    public void analyze(List<AstNode> statements) {
        collectSymbols(statements);
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

    private void traverseAndAnalyze(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            if (node == null) continue;
            switchModuleContext(node);
            Optional<IAnalysisHandler> handler = registry.resolveHandler(node.getClass());
            handler.ifPresent(h -> h.analyze(node, symbolTable, diagnostics));
            traverseAndAnalyze(node.getChildren());
            handler.ifPresent(h -> h.afterChildren(node, symbolTable, diagnostics));
        }
    }

    private void switchModuleContext(AstNode node) {
        contextTracker.handleNode(node);
    }

    /**
     * Sets up module relationships in the symbol table from the dependency graph.
     * Each module is registered under its alias chain, which is computed from the
     * import hierarchy. The root module uses rootAliasChain, imported modules use
     * their import alias appended to the parent's chain.
     */
    private void setupModuleRelationships(DependencyGraph graph, String mainFilePath, String rootAliasChain) {
        List<ModuleDescriptor> topoOrder = graph.topologicalOrder();

        // Pass 1: Compute alias chains (reverse topological — root first, then dependencies).
        Map<String, String> pathToAliasChain = new HashMap<>();
        pathToAliasChain.put(mainFilePath, rootAliasChain);

        List<ModuleDescriptor> fromRoot = new java.util.ArrayList<>(topoOrder);
        java.util.Collections.reverse(fromRoot);
        for (ModuleDescriptor module : fromRoot) {
            String modulePath = module.sourcePath();
            String moduleAliasChain = pathToAliasChain.get(modulePath);
            if (moduleAliasChain == null) {
                moduleAliasChain = ModuleId.deriveModuleName(modulePath);
                pathToAliasChain.put(modulePath, moduleAliasChain);
            }
            ModuleSetupContext ctx = new ModuleSetupContext(symbolTable, diagnostics, pathToAliasChain, modulePath);
            for (IDependencyInfo dep : module.dependencies()) {
                IDependencySetupHandler<IDependencyInfo> handler = setupRegistry.resolve(dep.getClass());
                if (handler != null) {
                    handler.registerScope(dep, ctx);
                }
            }
        }

        // Pass 2: Register all modules, then dispatch relationship registration.
        for (ModuleDescriptor module : topoOrder) {
            String modulePath = module.sourcePath();
            String moduleAliasChain = pathToAliasChain.getOrDefault(modulePath,
                    ModuleId.deriveModuleName(modulePath));
            symbolTable.registerModule(moduleAliasChain, modulePath);
        }
        for (ModuleDescriptor module : topoOrder) {
            ModuleSetupContext ctx = new ModuleSetupContext(symbolTable, diagnostics, pathToAliasChain, module.sourcePath());
            for (IDependencyInfo dep : module.dependencies()) {
                IDependencySetupHandler<IDependencyInfo> handler = setupRegistry.resolve(dep.getClass());
                if (handler != null) {
                    handler.registerRelationships(dep, ctx);
                }
            }
        }

        // Pass 3: Resolve cross-module bindings (USING etc.).
        for (ModuleDescriptor module : topoOrder) {
            ModuleSetupContext ctx = new ModuleSetupContext(symbolTable, diagnostics, pathToAliasChain, module.sourcePath());
            for (IDependencyInfo dep : module.dependencies()) {
                IDependencySetupHandler<IDependencyInfo> handler = setupRegistry.resolve(dep.getClass());
                if (handler != null) {
                    handler.resolveBindings(dep, ctx);
                }
            }
        }

        symbolTable.setCurrentModule(rootAliasChain);
    }
}
