package org.evochora.compiler.frontend.postprocess;

import org.evochora.compiler.frontend.TreeWalker;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IIdentifierBinding;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.frontend.module.ModuleContextTracker;
import org.evochora.compiler.frontend.semantics.ScopeTracker;
import org.evochora.compiler.model.symbols.SymbolTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A dedicated compiler phase that transforms the AST after semantic analysis.
 * It replaces every identifier that names a definition by what the definition stands for:
 * a register alias or a procedure parameter by the register, a constant by its value, and
 * any other definition, such as a label or a procedure, by its full name. After this phase
 * no name in the AST depends on where it is written, and the backend needs nothing from the
 * symbol table.
 * This runs *after* the TokenMapGenerator to ensure debug info is based on the original source.
 *
 * <p>The phase does not know the kinds of definitions. It resolves the identifier in the
 * {@link SymbolTable}, with scope-aware lookup for proc-scoped aliases and shadowing, and
 * asks the defining node for the replacement through {@link IIdentifierBinding}. A symbol
 * whose node offers no binding is referred to by its qualified name, as the symbol table
 * resolved it with the module's imports and export rules. An identifier that names no symbol
 * is left as it is.</p>
 */
public class AstPostProcessor implements IPostProcessContext {

    private final SymbolTable symbolTable;
    private final ModuleContextTracker contextTracker;
    private final ScopeTracker scopeTracker;
    private final PostProcessHandlerRegistry registry;
    private final Map<AstNode, AstNode> replacements = new HashMap<>();

    /**
     * Constructs a post-processor with handler registry, module context tracker, and scope tracker.
     *
     * @param symbolTable    the symbol table for scope-aware identifier resolution
     * @param contextTracker tracks module context boundaries during traversal
     * @param scopeTracker   tracks procedure scopes during traversal
     * @param registry       dispatches to feature-specific post-process handlers
     */
    public AstPostProcessor(SymbolTable symbolTable, ModuleContextTracker contextTracker,
                            ScopeTracker scopeTracker, PostProcessHandlerRegistry registry) {
        this.symbolTable = symbolTable;
        this.contextTracker = contextTracker;
        this.scopeTracker = scopeTracker;
        this.registry = registry;
    }

    /**
     * Transforms every top-level statement of a program in order. The module and scope
     * context carries from one statement to the next, as it does in the source, so the
     * statements have to be given in program order.
     *
     * @param statements The top-level AST nodes of the program.
     * @return A new list with each statement transformed; the given list is not modified.
     */
    public List<AstNode> process(List<AstNode> statements) {
        List<AstNode> transformed = new ArrayList<>(statements.size());
        for (AstNode statement : statements) {
            transformed.add(process(statement));
        }
        return transformed;
    }

    /**
     * Transforms the given AST by replacing the identifiers that name definitions.
     * @param root The root of the AST to transform.
     * @return The transformed AST root.
     */
    public AstNode process(AstNode root) {
        replacements.clear();

        // First pass: collect replacements with module/scope context tracking
        collectPass(root);

        // Second pass: apply the replacements
        TreeWalker walker = new TreeWalker(new HashMap<>());
        return walker.transform(root, replacements);
    }

    private void collectPass(AstNode node) {
        if (node == null) return;
        contextTracker.handleNode(node);

        // Dispatch through registry for feature-specific handlers
        registry.get(node.getClass()).ifPresent(h -> h.collect(node, this));

        if (node instanceof IdentifierNode identifier) {
            AstNode replacement = resolveBinding(identifier);
            if (replacement != null) {
                replacements.put(identifier, replacement);
            }
        }

        SymbolTable.Scope savedScope = scopeTracker.enterNode(node);
        for (AstNode child : node.getChildren()) {
            collectPass(child);
        }
        scopeTracker.leaveNode(savedScope);
    }

    /**
     * Finds what an identifier stands for, as the symbol table follows its definitions.
     *
     * @return the replacement: what the defining node binds the identifier to, or the
     *         identifier under the symbol's qualified name if the node offers no binding;
     *         {@code null} if the identifier names no symbol, is already written under the
     *         qualified name, or its definitions go in a circle
     */
    private AstNode resolveBinding(IdentifierNode reference) {
        AstNode bound = symbolTable.bindingOf(reference).orElse(null);
        if (bound instanceof IdentifierNode qualified && qualified.text().equals(reference.text())) {
            return null;
        }
        return bound;
    }
}
