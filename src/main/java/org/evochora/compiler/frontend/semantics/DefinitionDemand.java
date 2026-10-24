package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.IDefinitionDemand;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Analyzes a definition the moment a chain of names needs its value, before the walk of pass 2
 * reaches its line: runs the handler registered for the defining node, as the walk would, with
 * the symbol table standing in the scope and the placement of the definition, and restores both
 * afterwards. The handler records what it made of the definition's computation, and the table
 * finds it when it looks again. A definition without a handler is left as it is.
 * <p>
 * A definition that is asked for while its own handler runs, by the walk or on demand, is
 * circular: its computation needs, through the names it follows, its own value. The circle is
 * reported once, at the definition it was entered through, naming the definitions in progress
 * from it onward and the names the lookup followed back to it; nothing is recorded, so every
 * computation in the circle fails with its error reported here. The walk tells the demand which
 * node it runs a handler for ({@link #walking}), so that a demand for that node is a circle too.
 * One demand belongs to one walk of pass 2.
 */
final class DefinitionDemand implements IDefinitionDemand {

    private final SymbolTable symbolTable;
    private final DiagnosticsEngine diagnostics;
    private final AnalysisHandlerRegistry registry;
    // The node whose handler the walk runs right now; null between two nodes
    private AstNode walking;
    // The definitions whose handlers run on demand right now, the newest first
    private final Deque<Symbol> inProgress = new ArrayDeque<>();

    /**
     * Creates the demand of one walk.
     *
     * @param symbolTable The symbol table the walk resolves from, whose scope and placement the
     *                    demand moves to a definition and back.
     * @param diagnostics Where a circle is reported.
     * @param registry    The handlers of pass 2, by node class.
     */
    DefinitionDemand(SymbolTable symbolTable, DiagnosticsEngine diagnostics, AnalysisHandlerRegistry registry) {
        this.symbolTable = symbolTable;
        this.diagnostics = diagnostics;
        this.registry = registry;
    }

    /**
     * Tells the demand which node the walk runs a handler for; {@code null} between two nodes.
     *
     * @param node The node, or {@code null}.
     */
    void walking(AstNode node) {
        this.walking = node;
    }

    @Override
    public void analyze(Symbol definition, SymbolTable.Scope scope, List<String> through) {
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
        diagnostics.reportError(SymbolTable.circularDefinition(entered.name(), names), at.fileName(), at.lineNumber());
    }
}
