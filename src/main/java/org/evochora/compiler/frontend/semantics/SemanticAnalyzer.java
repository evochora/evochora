package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.module.DependencyGraph;
import org.evochora.compiler.frontend.module.ModuleDescriptor;
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
 *
 * <p>In multi-module compilation, the analyzer receives a {@link DependencyGraph} at construction
 * time and sets up module relationships (imports, requires, USING bindings) in the symbol table
 * before analysis begins. During traversal, it auto-switches the symbol table's current module
 * context based on the source file of each AST node via the {@link SourceLocatable} interface.
 */
public class SemanticAnalyzer {

    private final DiagnosticsEngine diagnostics;
    private final SymbolTable symbolTable;
    private final AnalysisHandlerRegistry registry;
    private final Map<AstNode, SymbolTable.Scope> scopeMap = new HashMap<>();
    private final ModuleContextTracker contextTracker;

    /**
     * Constructs a semantic analyzer without module support.
     * Suitable for single-file compilation and tests.
     *
     * @param diagnostics The diagnostics engine for reporting errors.
     * @param symbolTable The symbol table to use for analysis.
     */
    public SemanticAnalyzer(DiagnosticsEngine diagnostics, SymbolTable symbolTable) {
        this(diagnostics, symbolTable, null, null, new HashMap<>());
    }

    /**
     * Constructs a module-aware semantic analyzer.
     *
     * @param diagnostics  The diagnostics engine for reporting errors.
     * @param symbolTable  The symbol table to use for analysis.
     * @param graph        The dependency graph from Phase 0. Null for single-file compilation.
     * @param mainFilePath The absolute path of the main source file. Null when graph is null.
     * @param fileToModule Mapping from source file path to module ID, built by the Compiler.
     */
    public SemanticAnalyzer(DiagnosticsEngine diagnostics, SymbolTable symbolTable,
                            DependencyGraph graph, String mainFilePath,
                            Map<String, ModuleId> fileToModule) {
        this.diagnostics = diagnostics;
        this.symbolTable = symbolTable;
        this.contextTracker = new ModuleContextTracker(symbolTable, fileToModule);
        this.registry = AnalysisHandlerRegistry.initializeWithDefaults(symbolTable, scopeMap, diagnostics);

        if (graph != null) {
            setupModuleRelationships(graph, mainFilePath);
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
     * Registers all modules and their import/require/USING relationships.
     */
    private void setupModuleRelationships(DependencyGraph graph, String mainFilePath) {
        for (ModuleDescriptor module : graph.topologicalOrder()) {
            ModuleId moduleId = module.id();
            symbolTable.registerModule(moduleId, module.sourcePath());

            ModuleScope modScope = symbolTable.getModuleScope(moduleId).orElseThrow();

            for (ModuleDescriptor.ImportDecl imp : module.imports()) {
                modScope.imports().put(imp.alias().toUpperCase(), imp.resolvedId());
            }

            for (ModuleDescriptor.RequireDecl req : module.requires()) {
                modScope.requires().put(req.alias().toUpperCase(), req.path());
            }

        }

        // Set up USING bindings: each USING clause on an import wires a source module
        // into the imported module's scope under its required alias
        for (ModuleDescriptor module : graph.topologicalOrder()) {
            for (ModuleDescriptor.ImportDecl imp : module.imports()) {
                ModuleScope importedModScope = symbolTable.getModuleScope(imp.resolvedId()).orElse(null);
                if (importedModScope == null) continue;

                for (ModuleDescriptor.UsingDecl using : imp.usings()) {
                    String sourceAlias = using.sourceAlias().toUpperCase();
                    ModuleScope importerScope = symbolTable.getModuleScope(module.id()).orElseThrow();
                    ModuleId sourceModuleId = importerScope.imports().get(sourceAlias);
                    if (sourceModuleId != null) {
                        importedModScope.usingBindings().put(using.targetAlias().toUpperCase(), sourceModuleId);
                    }
                }
            }
        }

        symbolTable.setCurrentModule(new ModuleId(mainFilePath));
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
