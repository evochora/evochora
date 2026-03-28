package org.evochora.compiler.frontend.postprocess;

import org.evochora.compiler.frontend.TreeWalker;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IParameterBinding;
import org.evochora.compiler.model.ast.IRegisterAlias;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.model.ModuleContextTracker;
import org.evochora.compiler.model.ScopeTracker;
import org.evochora.compiler.model.symbols.ResolvedSymbol;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.api.SourceInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A dedicated compiler phase that transforms the AST after semantic analysis.
 * It resolves identifiers like constants and register aliases into their concrete forms.
 * This runs *after* the TokenMapGenerator to ensure debug info is based on the original source.
 *
 * <p>Register aliases are resolved via the {@link SymbolTable} with scope-aware lookup,
 * giving correct behavior for proc-scoped aliases and shadowing. Constants are still
 * collected into a module-qualified map (scope-aware constant resolution is a separate effort).</p>
 */
public class AstPostProcessor implements IPostProcessContext {
    private final SymbolTable symbolTable;
    private final ModuleContextTracker contextTracker;
    private final ScopeTracker scopeTracker;
    private final PostProcessHandlerRegistry registry;
    // Module-qualified constants: aliasChain -> (constantName -> value)
    private final Map<String, Map<String, TypedLiteralNode>> constants = new HashMap<>();
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
     * Transforms the given AST by replacing aliases and constants.
     * @param root The root of the AST to transform.
     * @return The transformed AST root.
     */
    public AstNode process(AstNode root) {
        replacements.clear();

        // First pass: collect constants and replacements with module/scope context tracking
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

        // Orchestrator infrastructure: IdentifierNode replacement (not feature-specific)
        if (node instanceof IdentifierNode) {
            collectReplacements(node);
        }

        SymbolTable.Scope savedScope = scopeTracker.enterNode(node);
        for (AstNode child : node.getChildren()) {
            collectPass(child);
        }
        scopeTracker.leaveNode(savedScope);
    }

    @Override
    public void collectConstant(String constantName, TypedLiteralNode value) {
        String moduleKey = currentModuleKey();
        constants.computeIfAbsent(moduleKey, k -> new HashMap<>()).put(constantName, value);
    }

    private void collectReplacements(AstNode node) {
        if (!(node instanceof IdentifierNode idNode)) {
            return;
        }

        String identifierName = idNode.text();

        Optional<ResolvedSymbol> symbolOpt = symbolTable.resolve(identifierName, idNode.sourceInfo().fileName());
        if (symbolOpt.isPresent()) {
            Symbol symbol = symbolOpt.get().symbol();
            if ((symbol.type() == Symbol.Type.REGISTER_ALIAS_DATA || symbol.type() == Symbol.Type.REGISTER_ALIAS_LOCATION)
                    && symbol.node() instanceof IRegisterAlias alias) {
                createRegisterReplacement(idNode, identifierName.toUpperCase(), alias.register());
                return;
            }
            if ((symbol.type() == Symbol.Type.PARAMETER_DATA || symbol.type() == Symbol.Type.PARAMETER_LOCATION)
                    && symbol.node() instanceof IParameterBinding pb) {
                createRegisterReplacement(idNode, identifierName.toUpperCase(), pb.targetRegister());
                return;
            }
            if (symbol.type() == Symbol.Type.CONSTANT) {
                String moduleKey = currentModuleKey();
                Map<String, TypedLiteralNode> moduleConstants = constants.get(moduleKey);
                if (moduleConstants != null && moduleConstants.containsKey(identifierName)) {
                    replacements.put(idNode, moduleConstants.get(identifierName));
                }
            }
        }
    }

    private String currentModuleKey() {
        String chain = symbolTable.getCurrentAliasChain();
        return chain != null ? chain : "";
    }

    /**
     * Creates a RegisterNode replacement for an identifier that resolves to a register alias.
     *
     * @param originalNode the original identifier node
     * @param aliasName the alias name (e.g., "TMP")
     * @param resolvedRegister the resolved register (e.g., "%PDR0")
     */
    private void createRegisterReplacement(IdentifierNode originalNode, String aliasName, String resolvedRegister) {
        SourceInfo sourceInfo = originalNode.sourceInfo();
        RegisterNode replacement = new RegisterNode(
            resolvedRegister,
            aliasName,
            sourceInfo
        );
        replacements.put(originalNode, replacement);
    }

}
