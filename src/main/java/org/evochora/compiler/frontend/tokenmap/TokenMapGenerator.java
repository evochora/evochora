package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.api.TokenKind;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.RegisterNode;

import org.evochora.compiler.model.ModuleContextTracker;
import org.evochora.compiler.model.symbols.ResolvedSymbol;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Traverses a syntactically and semantically valid Abstract Syntax Tree (AST)
 * to generate a map of tokens and their associated semantic information.
 * This map is intended for use by debuggers and other tools to provide features
 * like syntax highlighting and contextual information.
 * <p>
 * This generator is stateful and maintains the current scope as it walks the tree,
 * ensuring that all tokens are correctly contextualized. It relies on a pre-populated
 * SymbolTable as the source of truth for symbol resolution.
 * <p>
 * Feature-specific AST node handling is delegated to {@link ITokenMapContributor}
 * implementations via the {@link TokenMapContributorRegistry}. Core node types
 * (IdentifierNode, RegisterNode) are handled as default logic.
 */
public class TokenMapGenerator implements ITokenMapContext {

    private final SymbolTable symbolTable;
    private final Map<SourceInfo, TokenInfo> tokenMap = new HashMap<>();
    private final DiagnosticsEngine diagnostics;
    private final TokenMapContributorRegistry contributorRegistry;
    private final ModuleContextTracker contextTracker;
    private SymbolTable.Scope currentScopeObj;
    private String currentScopeName = "global";

    /**
     * Constructs a TokenMapGenerator.
     *
     * @param symbolTable         The fully resolved symbol table from the semantic analysis phase.
     * @param diagnostics         The diagnostics engine for reporting compilation errors.
     * @param contributorRegistry The registry of feature-specific token map contributors.
     * @param contextTracker      The module context tracker for alias chain qualification.
     */
    public TokenMapGenerator(SymbolTable symbolTable,
                             DiagnosticsEngine diagnostics, TokenMapContributorRegistry contributorRegistry,
                             ModuleContextTracker contextTracker) {
        this.symbolTable = Objects.requireNonNull(symbolTable, "SymbolTable cannot be null.");
        this.diagnostics = diagnostics;
        this.contributorRegistry = Objects.requireNonNull(contributorRegistry, "ContributorRegistry cannot be null.");
        this.contextTracker = contextTracker;
        this.currentScopeObj = symbolTable.getRootScope();
    }

    /**
     * Generates the token map by walking the provided AST root node.
     *
     * @param root The root of the AST to traverse.
     * @return A map where the key is the SourceInfo (location) of a token and the value is the detailed TokenInfo.
     */
    public Map<SourceInfo, TokenInfo> generate(AstNode root) {
        walkAndVisit(root);
        return tokenMap;
    }

    /**
     * Generates the token map by walking all provided AST nodes.
     * This is useful when there are multiple top-level nodes (e.g., multiple procedures).
     *
     * @param nodes The list of AST nodes to traverse.
     * @return A map where the key is the SourceInfo (location) of a token and the value is the detailed TokenInfo.
     */
    public Map<SourceInfo, TokenInfo> generateAll(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            if (node != null) {
                walkAndVisit(node);
            }
        }
        return tokenMap;
    }

    /**
     * Builds a 3-level token lookup structure for efficient file/line/column-based queries.
     *
     * <p>Structure: fileName -> lineNumber -> columnNumber -> list of TokenInfo</p>
     *
     * @param tokenMap The flat token map keyed by {@link SourceInfo}
     * @return A nested lookup map suitable for debuggers and indexers
     */
    public static Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>> buildTokenLookup(Map<SourceInfo, TokenInfo> tokenMap) {
        Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>> result = new HashMap<>();

        for (Map.Entry<SourceInfo, TokenInfo> entry : tokenMap.entrySet()) {
            SourceInfo sourceInfo = entry.getKey();
            TokenInfo tokenInfo = entry.getValue();

            String fileName = sourceInfo.fileName();
            Integer lineNumber = sourceInfo.lineNumber();
            Integer columnNumber = sourceInfo.columnNumber();

            result.computeIfAbsent(fileName, k -> new HashMap<>())
                  .computeIfAbsent(lineNumber, k -> new HashMap<>())
                  .computeIfAbsent(columnNumber, k -> new ArrayList<>())
                  .add(tokenInfo);
        }

        return result;
    }

    // === ITokenMapContext implementation ===

    @Override
    public void addToken(SourceInfo sourceInfo, String text, TokenKind type, String scope) {
        tokenMap.put(sourceInfo, new TokenInfo(text, type, scope));
    }

    @Override
    public void addToken(SourceInfo sourceInfo, String text, TokenKind type, String scope, String qualifiedName) {
        tokenMap.put(sourceInfo, new TokenInfo(text, type, scope, qualifiedName));
    }

    @Override
    public String currentScope() {
        return this.currentScopeName;
    }

    @Override
    public DiagnosticsEngine getDiagnostics() {
        return this.diagnostics;
    }

    // === Tree walking ===

    /**
     * A stateful recursive walk method that manages the current scope via the SymbolTable's
     * node-to-scope map. Scope transitions are determined generically by looking up the node,
     * using {@link SymbolTable.Scope#name()} for the display name.
     *
     * @param node The current AST node to visit.
     */
    private void walkAndVisit(AstNode node) {
        if (node == null) {
            return;
        }

        // Track module context via PushCtxNode/PopCtxNode
        contextTracker.handleNode(node);

        SymbolTable.Scope previousScopeObj = this.currentScopeObj;
        String previousScopeName = this.currentScopeName;

        SymbolTable.Scope nodeScope = symbolTable.getNodeScope(node);
        if (nodeScope != null) {
            this.currentScopeObj = nodeScope;
            this.currentScopeName = nodeScope.name();
        }

        visit(node);

        for (AstNode child : node.getChildren()) {
            walkAndVisit(child);
        }

        this.currentScopeObj = previousScopeObj;
        this.currentScopeName = previousScopeName;
    }

    /**
     * Processes a single AST node. Consults the contributor registry first;
     * if no contributor is registered, falls back to default handling for
     * core node types (IdentifierNode, RegisterNode).
     *
     * @param node The AST node to process.
     */
    private void visit(AstNode node) {
        Optional<ITokenMapContributor> contributor = contributorRegistry.get(node.getClass());
        if (contributor.isPresent()) {
            contributor.get().contribute(node, this);
            return;
        }

        if (node instanceof IdentifierNode identifierNode) {
            Optional<ResolvedSymbol> symbolOpt = resolveInCurrentScope(identifierNode.text(), identifierNode.sourceInfo().fileName());
            if (symbolOpt.isPresent()) {
                ResolvedSymbol resolved = symbolOpt.get();
                Symbol sym = resolved.symbol();
                SourceInfo si = identifierNode.sourceInfo();
                String qualifiedName = resolved.qualifiedName();
                tokenMap.put(si, new TokenInfo(identifierNode.text(), TokenKindMapper.map(sym.type()), this.currentScopeName, qualifiedName));
            } else {
                diagnostics.reportError(
                    "Symbol '" + identifierNode.text() +
                    "' could not be resolved. This indicates a semantic analysis failure.",
                    identifierNode.sourceInfo().fileName(),
                    identifierNode.sourceInfo().lineNumber()
                );
            }
        } else if (node instanceof RegisterNode registerNode) {
            if (registerNode.isAlias()) {
                SourceInfo aliasSourceInfo = registerNode.sourceInfo();
                String qualifiedAlias = qualifyName(registerNode.getOriginalAlias());
                tokenMap.put(aliasSourceInfo, new TokenInfo(
                    registerNode.getOriginalAlias(),
                    TokenKind.ALIAS,
                    this.currentScopeName,
                    qualifiedAlias
                ));
            } else {
                SourceInfo regSourceInfo = registerNode.sourceInfo();
                tokenMap.put(regSourceInfo, new TokenInfo(
                    registerNode.getName(),
                    TokenKind.VARIABLE,
                    this.currentScopeName
                ));
            }
        }
    }

    private String qualifyName(String localName) {
        String chain = contextTracker.currentAliasChain();
        if (chain != null && !chain.isEmpty()) {
            return chain + "." + localName.toUpperCase();
        }
        return localName.toUpperCase();
    }

    /**
     * Resolves a symbol using the currently tracked scope object.
     */
    private Optional<ResolvedSymbol> resolveInCurrentScope(String name, String fileName) {
        SymbolTable.Scope originalScope = symbolTable.getCurrentScope();
        if (this.currentScopeObj != null) {
            symbolTable.setCurrentScope(this.currentScopeObj);
        }
        try {
            return symbolTable.resolve(name, fileName);
        } finally {
            symbolTable.setCurrentScope(originalScope);
        }
    }
}
