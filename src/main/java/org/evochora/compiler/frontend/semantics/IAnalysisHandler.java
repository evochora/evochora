package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Interface for specialized handlers in semantic analysis.
 * Each handler is responsible for analyzing a specific type of AST node.
 */
public interface IAnalysisHandler {
    /**
     * Analyzes a single AST node before its children are traversed.
     * <p>
     * The handler of a node that defines a name whose value is a computation is also run on
     * demand: when a name needs the value before the walk reaches the node's line, the phase
     * runs the handler there and then, with the symbol table moved to the scope and placement
     * of the definition, without the node's children and without {@link #afterChildren}; the
     * walk runs it again at the line if the node stands in the tree. A defining node that a
     * symbol collector made and that stands in no tree is run on demand only, so only when a
     * name needs its value. Such a handler therefore gives the same result when run twice,
     * which the symbol table's records of computations provide, and opens nothing it would
     * close in {@link #afterChildren}. Every handler may find that a resolution of a name runs
     * the handler of another definition before it returns.
     * @param node The node to analyze.
     * @param symbolTable The symbol table for the current scope.
     * @param diagnostics The engine for reporting errors.
     */
    void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics);

    /**
     * Called after all children of the node have been analyzed.
     * Override to perform post-traversal actions such as leaving a scope.
     * @param node The node whose children have been analyzed.
     * @param symbolTable The symbol table for the current scope.
     * @param diagnostics The engine for reporting errors.
     */
    default void afterChildren(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {}
}