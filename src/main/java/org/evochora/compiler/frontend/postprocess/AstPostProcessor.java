package org.evochora.compiler.frontend.postprocess;

import org.evochora.compiler.frontend.TreeWalker;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.model.ModuleContextTracker;
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
 */
public class AstPostProcessor implements IPostProcessContext {
    private final SymbolTable symbolTable;
    private final ModuleContextTracker contextTracker;
    private final PostProcessHandlerRegistry registry;
    // Module-qualified register aliases, collected from RegNode/PregNode via handlers
    private final Map<String, String> registerAliases = new HashMap<>();
    // Module-qualified constants: aliasChain -> (constantName -> value)
    private final Map<String, Map<String, TypedLiteralNode>> constants = new HashMap<>();
    private final Map<AstNode, AstNode> replacements = new HashMap<>();

    /**
     * Constructs a post-processor with an explicit handler registry and module context tracker.
     */
    public AstPostProcessor(SymbolTable symbolTable, ModuleContextTracker contextTracker,
                            PostProcessHandlerRegistry registry) {
        this.symbolTable = symbolTable;
        this.contextTracker = contextTracker;
        this.registry = registry;
    }

    /**
     * Transforms the given AST by replacing aliases and constants.
     * @param root The root of the AST to transform.
     * @return The transformed AST root.
     */
    public AstNode process(AstNode root) {
        replacements.clear();

        // First pass: collect constants and replacements with module context tracking
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

        for (AstNode child : node.getChildren()) {
            collectPass(child);
        }
    }

    @Override
    public void collectRegisterAlias(String aliasText, String registerText) {
        String qualifiedAlias = qualifyAliasName(aliasText.toUpperCase());
        registerAliases.put(qualifiedAlias, registerText);
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

        // Check if this identifier is a register alias (module-qualified keys)
        String upperName = identifierName.toUpperCase();
        String qualifiedAlias = qualifyAliasName(upperName);
        if (registerAliases.containsKey(qualifiedAlias)) {
            createRegisterReplacement(idNode, upperName, registerAliases.get(qualifiedAlias));
            return;
        }

        // Check if this identifier is a constant
        Optional<ResolvedSymbol> symbolOpt = symbolTable.resolve(idNode.text(), idNode.sourceInfo().fileName());
        if (symbolOpt.isPresent()) {
            Symbol symbol = symbolOpt.get().symbol();
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

    private String qualifyAliasName(String upperName) {
        String chain = symbolTable.getCurrentAliasChain();
        if (chain != null && !chain.isEmpty()) {
            return chain + "." + upperName;
        }
        return upperName;
    }

    /**
     * Creates a RegisterNode replacement for an identifier that resolves to a register alias.
     *
     * @param originalNode the original identifier node
     * @param aliasName the alias name (e.g., "TMP")
     * @param resolvedRegister the resolved register (e.g., "%PDR0")
     */
    private void createRegisterReplacement(AstNode originalNode, String aliasName, String resolvedRegister) {
        if (!(originalNode instanceof IdentifierNode idNode)) {
            throw new IllegalArgumentException("Expected IdentifierNode, got: " + originalNode.getClass().getSimpleName());
        }

        SourceInfo sourceInfo = idNode.sourceInfo();

        RegisterNode replacement = new RegisterNode(
            resolvedRegister,
            aliasName,
            sourceInfo
        );
        replacements.put(originalNode, replacement);
    }

}
