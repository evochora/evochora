package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SymbolTable;

/**
 * Interface for specialized handlers in semantic analysis.
 * Each handler is responsible for analyzing a specific type of AST node.
 */
public interface IAnalysisHandler {
    /**
     * Analyzes a single AST node before its children are traversed.
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