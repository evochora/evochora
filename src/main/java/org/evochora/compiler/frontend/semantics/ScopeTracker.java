package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Tracks SymbolTable scope during AST traversal using the node-to-scope mappings that the
 * symbol collector of a node that opens a level registers during semantic analysis.
 *
 * <p>When visiting an AST node that has a registered scope, such as a procedure or a control
 * block, the scope is entered before processing children and restored afterwards.
 * This enables scope-sensitive symbol resolution during post-processing phases.</p>
 *
 * <p>The enter/leave pattern uses explicit saved-scope values rather than an internal
 * stack to ensure the traversal cannot get out of sync with the caller's recursion.</p>
 */
public class ScopeTracker {

    private final SymbolTable symbolTable;

    /**
     * Creates a tracker over the given symbol table. The table is shared, not copied:
     * every scope change made through this tracker is visible to all other users of
     * the same table.
     *
     * @param symbolTable the symbol table whose current scope is moved
     */
    public ScopeTracker(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
    }

    /**
     * Enters the scope associated with the given AST node, if any.
     * Call this before processing the node's children.
     *
     * @param node the AST node being visited
     * @return the previous scope to restore after children are processed,
     *         or {@code null} if no scope change occurred
     */
    public SymbolTable.Scope enterNode(AstNode node) {
        SymbolTable.Scope nodeScope = symbolTable.getNodeScope(node);
        if (nodeScope != null) {
            SymbolTable.Scope saved = symbolTable.getCurrentScope();
            symbolTable.setCurrentScope(nodeScope);
            return saved;
        }
        return null;
    }

    /**
     * Restores the scope saved by a previous {@link #enterNode} call.
     * Call this after processing the node's children.
     *
     * @param savedScope the scope returned by {@link #enterNode}, or {@code null} if
     *                   no scope change occurred
     */
    public void leaveNode(SymbolTable.Scope savedScope) {
        if (savedScope != null) {
            symbolTable.setCurrentScope(savedScope);
        }
    }
}
