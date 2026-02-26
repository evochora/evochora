package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SymbolTable;

/**
 * Interface for pass-1 symbol collection handlers.
 * Each collector is responsible for registering symbols (labels, procedures, scopes)
 * into the symbol table before the main analysis pass.
 */
public interface ISymbolCollector {
    /**
     * Collects symbols from a single AST node before its children are visited.
     * @param node The node to collect symbols from.
     * @param symbolTable The symbol table to register symbols in.
     * @param diagnostics The engine for reporting errors.
     */
    void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics);

    /**
     * Called after all children of the node have been visited during symbol collection.
     * Override to perform post-traversal actions such as leaving a scope.
     * @param node The node whose children have been visited.
     * @param symbolTable The symbol table.
     * @param diagnostics The engine for reporting errors.
     */
    default void collectAfterChildren(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {}
}
