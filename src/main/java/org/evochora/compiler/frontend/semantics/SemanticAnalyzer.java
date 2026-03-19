package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.module.DependencyGraph;
import org.evochora.compiler.frontend.module.ModuleDescriptor;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;
import org.evochora.compiler.frontend.semantics.analysis.ISymbolCollector;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ModuleContextTracker;
import org.evochora.compiler.model.symbols.ModuleScope;
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
                            String rootAliasChain, AnalysisHandlerRegistry registry) {
        this.diagnostics = diagnostics;
        this.symbolTable = symbolTable;
        this.contextTracker = new ModuleContextTracker(symbolTable);
        this.registry = registry;

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

        // Pass 1: Compute alias chains for all modules, starting from root.
        // Reverse topological order processes the root first, then its dependencies,
        // ensuring parent alias chains are available before child chains.
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
            for (ModuleDescriptor.ImportDecl imp : module.imports()) {
                String importedPath = imp.resolvedId().path();
                String importAlias = imp.alias().toUpperCase();
                String importedAliasChain = moduleAliasChain.isEmpty()
                        ? importAlias
                        : moduleAliasChain + "." + importAlias;
                pathToAliasChain.put(importedPath, importedAliasChain);
            }
        }

        // Pass 2: Register all modules under their correct alias chains and populate relationships.
        for (ModuleDescriptor module : topoOrder) {
            String modulePath = module.sourcePath();
            String moduleAliasChain = pathToAliasChain.getOrDefault(modulePath,
                    ModuleId.deriveModuleName(modulePath));
            symbolTable.registerModule(moduleAliasChain, modulePath);

            ModuleScope modScope = symbolTable.getModuleScope(moduleAliasChain).orElseThrow();

            for (ModuleDescriptor.ImportDecl imp : module.imports()) {
                String importAlias = imp.alias().toUpperCase();
                String importedAliasChain = pathToAliasChain.get(imp.resolvedId().path());
                modScope.imports().put(importAlias, importedAliasChain);
            }

            for (ModuleDescriptor.RequireDecl req : module.requires()) {
                modScope.requires().put(req.alias().toUpperCase(), req.path());
            }
        }

        // Pass 3: Set up USING bindings (requires all modules to be registered first).
        for (ModuleDescriptor module : topoOrder) {
            String modulePath = module.sourcePath();
            String moduleAliasChain = pathToAliasChain.get(modulePath);
            if (moduleAliasChain == null) continue;

            for (ModuleDescriptor.ImportDecl imp : module.imports()) {
                String importedAliasChain = pathToAliasChain.get(imp.resolvedId().path());
                ModuleScope importedModScope = symbolTable.getModuleScope(importedAliasChain).orElse(null);
                if (importedModScope == null) continue;

                for (ModuleDescriptor.UsingDecl using : imp.usings()) {
                    String sourceAlias = using.sourceAlias().toUpperCase();
                    ModuleScope importerScope = symbolTable.getModuleScope(moduleAliasChain).orElseThrow();
                    String sourceAliasChain = importerScope.imports().get(sourceAlias);
                    if (sourceAliasChain != null) {
                        importedModScope.usingBindings().put(using.targetAlias().toUpperCase(), sourceAliasChain);
                    }
                }
            }
        }

        symbolTable.setCurrentModule(rootAliasChain);
    }

    /**
     * Gets the handler registry for external registration of additional handlers.
     * @return The analysis handler registry
     */
    public AnalysisHandlerRegistry getRegistry() {
        return registry;
    }

}
