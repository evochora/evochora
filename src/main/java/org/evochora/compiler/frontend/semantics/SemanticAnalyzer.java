package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.DependencyGraph;
import org.evochora.compiler.frontend.lexer.IDependencyInfo;
import org.evochora.compiler.frontend.lexer.ModulePlacement;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.frontend.ModuleContextTracker;
import org.evochora.compiler.frontend.ScopeTracker;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Performs semantic analysis on the AST. This includes tasks like symbol table management,
 * type checking (in a broader sense), and ensuring that the program logic is sound.
 * It operates by traversing the AST and dispatching nodes to specific handlers.
 *
 * <p>In multi-module compilation, the analyzer receives a {@link DependencyGraph} at construction
 * time and registers the module placements and their relationships in the symbol table before
 * analysis begins. During traversal, it switches module context using PushCtxNode/PopCtxNode
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
    // The node whose handler the walk runs right now; null between two nodes
    private AstNode walking;
    // The definitions whose handlers run on demand right now, the newest first
    private final Deque<Symbol> inProgress = new ArrayDeque<>();

    /**
     * Constructs a semantic analyzer with an externally built analysis registry. Nothing is
     * analyzed and the symbol table is not touched until {@link #analyze(List)} is called.
     *
     * @param diagnostics    The diagnostics engine for reporting errors.
     * @param symbolTable    The symbol table to use for analysis.
     * @param graph          The dependency graph from the Lexer phase. Null for single-file compilation.
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
     * then performs two passes: one to collect the names the program defines, labels,
     * procedures, constants, aliases, and a second to analyze the statements in detail. The
     * second pass resolves names against the table the first one built; when the first pass
     * reported an error, the table lacks what it rejected, and every resolution that misses it
     * would only repeat the error as a consequence. So the second pass runs only when the first
     * one was clean.
     * <p>
     * The second pass walks the statements in text order, but a definition is analyzed the
     * moment a name needs its value, which may be above its line: the symbol table hands such a
     * definition to {@link #analyzeOnDemand}, which runs its handler there and then. So a name
     * may be used anywhere on its level, above or below its definition.
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
        symbolTable.setDefinitionDemand(this::analyzeOnDemand);
        try {
            analyzeStatements(statements);
        } finally {
            symbolTable.setDefinitionDemand(null);
        }
    }

    /**
     * Pass 1: Collects the names the program defines from the AST.
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
            if (handler.isPresent()) {
                walking = node;
                try {
                    handler.get().analyze(node, symbolTable, diagnostics);
                } finally {
                    walking = null;
                }
            }
            traverseAndAnalyze(node.getChildren());
            handler.ifPresent(h -> h.afterChildren(node, symbolTable, diagnostics));
            scopeTracker.leaveNode(saved);
        }
    }

    private void switchModuleContext(AstNode node) {
        contextTracker.handleNode(node);
    }

    /**
     * Analyzes a definition the moment a chain of names needs its value, before the walk reaches
     * its line: runs the handler registered for the defining node, as the walk would, with the
     * symbol table standing in the scope and the placement of the definition, and restores both
     * afterwards. The handler records what it made of the definition's computation, and the
     * table finds it when it looks again. A definition without a handler is left as it is.
     * <p>
     * A definition that is asked for while its own handler runs, by the walk or on demand, is
     * circular: its computation needs, through the names it follows, its own value. The circle
     * is reported once, at the definition it was entered through, naming the definitions in
     * progress from it onward and the names the lookup followed back to it; nothing is
     * recorded, so every computation in the circle fails with its error reported here.
     */
    private void analyzeOnDemand(Symbol definition, SymbolTable.Scope scope, List<String> through) {
        if (definition.node() == walking || inProgress.stream().anyMatch(symbol -> symbol.node() == definition.node())) {
            reportCircle(definition, through);
            return;
        }
        Optional<IAnalysisHandler> handler = registry.resolveHandler(definition.node().getClass());
        if (handler.isEmpty()) {
            return;
        }
        SymbolTable.Scope savedScope = symbolTable.getCurrentScope();
        String savedModule = symbolTable.getCurrentAliasChain();
        String placement = definition.sourceInfo().placement();
        inProgress.push(definition);
        try {
            symbolTable.setCurrentScope(scope);
            if (placement != null && symbolTable.getModuleScope(placement).isPresent()) {
                symbolTable.setCurrentModule(placement);
            }
            handler.get().analyze(definition.node(), symbolTable, diagnostics);
        } finally {
            inProgress.pop();
            if (savedModule != null) {
                symbolTable.setCurrentModule(savedModule);
            }
            symbolTable.setCurrentScope(savedScope);
        }
    }

    /**
     * Reports a circle of definitions: the one entered again, the definitions in progress after
     * it, the newest last, then the names the lookup followed from the newest back to it. The
     * walk's node, if it is the one entered again, is before every definition in progress.
     */
    private void reportCircle(Symbol entered, List<String> through) {
        List<String> names = new ArrayList<>();
        names.add(entered.name());
        Iterator<Symbol> oldestFirst = inProgress.descendingIterator();
        boolean after = entered.node() == walking;
        while (oldestFirst.hasNext()) {
            Symbol symbol = oldestFirst.next();
            if (after) {
                names.add(symbol.name());
            }
            after = after || symbol.node() == entered.node();
        }
        names.addAll(through);
        SourceInfo at = entered.sourceInfo();
        diagnostics.reportError(
                "Definition of " + entered.name() + " is circular: " + String.join(" -> ", names) + ".",
                at.fileName(), at.lineNumber());
    }

    /**
     * Sets up module relationships in the symbol table from the dependency graph. The root
     * placement is named first; then every module placement is registered under the alias chain
     * the dependency scan gave it, the same chain the preprocessor gave its tokens; a file
     * imported more than once is a module once per import.
     */
    private void setupModuleRelationships(DependencyGraph graph, String rootAliasChain) {
        List<ModulePlacement> placements = graph.placements();

        symbolTable.registerRoot(rootAliasChain);
        for (ModulePlacement placement : placements) {
            symbolTable.registerModule(placement.aliasChain(), placement.sourcePath());
        }

        // Relationships are registered with the imported placements first.
        for (ModulePlacement placement : placements) {
            ModuleSetupContext ctx = new ModuleSetupContext(symbolTable, diagnostics, placement.aliasChain());
            for (IDependencyInfo dep : placement.dependencies()) {
                IDependencySetupHandler<IDependencyInfo> handler = setupRegistry.resolve(dep.getClass());
                if (handler != null) {
                    handler.registerRelationships(dep, ctx);
                }
            }
        }

        symbolTable.setCurrentModule(rootAliasChain);
    }
}
