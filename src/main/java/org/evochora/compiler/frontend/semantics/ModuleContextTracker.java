package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.PopCtxNode;
import org.evochora.compiler.frontend.parser.ast.PushCtxNode;
import org.evochora.compiler.frontend.parser.ast.SourceLocatable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Tracks module context during AST traversal using PushCtxNode/PopCtxNode boundaries.
 * When entering a module section (PushCtxNode), the current module is pushed onto a stack.
 * When leaving (PopCtxNode), the previous module is restored.
 *
 * <p>Used by both the SemanticAnalyzer (Phase 4) and AstPostProcessor (Phase 6) to ensure
 * that symbol operations happen in the correct module context.</p>
 */
public class ModuleContextTracker {

    private final Deque<ModuleId> stack = new ArrayDeque<>();
    private final SymbolTable symbolTable;
    private final Map<String, ModuleId> fileToModule;

    public ModuleContextTracker(SymbolTable symbolTable, Map<String, ModuleId> fileToModule) {
        this.symbolTable = symbolTable;
        this.fileToModule = fileToModule;
    }

    /**
     * Handles module context switching for the given AST node.
     * Must be called for every node during traversal, before any symbol operations.
     *
     * @param node the AST node being visited
     */
    public void handleNode(AstNode node) {
        if (fileToModule.isEmpty()) return;

        if (node instanceof PushCtxNode pushCtx) {
            stack.push(symbolTable.getCurrentModuleId());
            if (pushCtx.targetPath() != null) {
                ModuleId targetModule = fileToModule.get(pushCtx.targetPath());
                if (targetModule != null) {
                    symbolTable.setCurrentModule(targetModule);
                }
            }
            return;
        }
        if (node instanceof PopCtxNode) {
            if (!stack.isEmpty()) {
                ModuleId restored = stack.pop();
                if (restored != null && !restored.equals(symbolTable.getCurrentModuleId())) {
                    symbolTable.setCurrentModule(restored);
                }
            }
            return;
        }
        if (node instanceof SourceLocatable locatable) {
            String fileName = locatable.getSourceFileName();
            if (fileName == null) return;
            ModuleId moduleId = fileToModule.get(fileName);
            if (moduleId != null && !moduleId.equals(symbolTable.getCurrentModuleId())) {
                symbolTable.setCurrentModule(moduleId);
            }
        }
    }
}
