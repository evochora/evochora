package org.evochora.compiler.frontend.postprocess;

import org.evochora.compiler.frontend.TreeWalker;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.parser.ast.PregNode;
import org.evochora.compiler.frontend.parser.features.def.DefineNode;
import org.evochora.compiler.frontend.parser.features.reg.RegNode;
import org.evochora.compiler.frontend.semantics.ModuleContextTracker;
import org.evochora.compiler.frontend.semantics.ResolvedSymbol;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.api.SourceInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A dedicated compiler phase that transforms the AST after semantic analysis.
 * It resolves identifiers like constants and register aliases into their concrete forms.
 * This runs *after* the TokenMapGenerator to ensure debug info is based on the original source.
 */
public class AstPostProcessor {
    private final SymbolTable symbolTable;
    private final ModuleContextTracker contextTracker;
    // Module-qualified register aliases, self-extracted from RegNode/PregNode during the walk
    private final Map<String, String> registerAliases = new HashMap<>();
    // Module-qualified constants: aliasChain -> (constantName -> value)
    private final Map<String, Map<String, TypedLiteralNode>> constants = new HashMap<>();
    private final Map<AstNode, AstNode> replacements = new HashMap<>();

    /**
     * Constructs a post-processor for single-file compilation (no module context).
     */
    public AstPostProcessor(SymbolTable symbolTable) {
        this(symbolTable, new ModuleContextTracker(symbolTable));
    }

    /**
     * Constructs a module-aware post-processor.
     */
    public AstPostProcessor(SymbolTable symbolTable, ModuleContextTracker contextTracker) {
        this.symbolTable = symbolTable;
        this.contextTracker = contextTracker;
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

        if (node instanceof RegNode regNode) {
            collectRegisterAlias(regNode.alias().text(), regNode.register().text());
        } else if (node instanceof PregNode pregNode) {
            collectRegisterAlias(pregNode.alias().text(), pregNode.targetRegister().text());
        } else if (node instanceof DefineNode) {
            collectConstants(node);
        } else if (node instanceof IdentifierNode) {
            collectReplacements(node);
        }

        for (AstNode child : node.getChildren()) {
            collectPass(child);
        }
    }

    private void collectRegisterAlias(String aliasText, String registerText) {
        String qualifiedAlias = qualifyAliasName(aliasText.toUpperCase());
        registerAliases.put(qualifiedAlias, registerText);
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
     * @param resolvedRegister the resolved register (e.g., "%PR0")
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

    private void collectConstants(AstNode node) {
        if (!(node instanceof DefineNode defineNode)) {
            return;
        }

        String constantName = defineNode.name().text();
        if (defineNode.value() instanceof TypedLiteralNode typedValue) {
            String moduleKey = currentModuleKey();
            constants.computeIfAbsent(moduleKey, k -> new HashMap<>()).put(constantName, typedValue);
        }
    }
}
