package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.PopCtxNode;
import org.evochora.compiler.frontend.parser.ast.PushCtxNode;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks module context during AST traversal using PushCtxNode/PopCtxNode boundaries.
 * Module identity is determined by the import alias chain carried in PushCtxNode,
 * not by source file path. This allows the same physical file to appear as distinct
 * placements with independent module contexts.
 *
 * <p>When entering an .IMPORT section (PushCtxNode with non-null aliasChain), the current
 * alias chain is pushed onto a stack and the symbol table switches to the imported module.
 * When entering a .SOURCE section (PushCtxNode with null aliasChain), the parent context
 * is preserved. When leaving (PopCtxNode), the previous context is restored.</p>
 *
 * <p>Used by SemanticAnalyzer (Phase 4), AstPostProcessor (Phase 6), and TokenMapGenerator (Phase 5)
 * to ensure symbol operations happen in the correct module context.</p>
 */
public class ModuleContextTracker {

    private final Deque<String> stack = new ArrayDeque<>();
    private final SymbolTable symbolTable;

    public ModuleContextTracker(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
    }

    /**
     * Handles module context switching for the given AST node.
     * Must be called for every node during traversal, before any symbol operations.
     *
     * @param node the AST node being visited
     */
    public void handleNode(AstNode node) {
        if (node instanceof PushCtxNode pushCtx) {
            stack.push(symbolTable.getCurrentAliasChain());
            if (pushCtx.aliasChain() != null) {
                symbolTable.setCurrentModule(pushCtx.aliasChain());
            }
            // .SOURCE (null aliasChain): parent context preserved
            return;
        }
        if (node instanceof PopCtxNode) {
            if (!stack.isEmpty()) {
                String restored = stack.pop();
                if (restored != null) {
                    symbolTable.setCurrentModule(restored);
                }
            }
        }
    }

    /**
     * Returns the current alias chain from the symbol table.
     */
    public String currentAliasChain() {
        return symbolTable.getCurrentAliasChain();
    }
}
