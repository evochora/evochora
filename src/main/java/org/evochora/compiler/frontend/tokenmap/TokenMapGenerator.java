package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.RegisterNode;

import org.evochora.compiler.frontend.semantics.ModuleId;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
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
    private final Map<AstNode, SymbolTable.Scope> scopeMap;
    private final Map<SourceInfo, TokenInfo> tokenMap = new HashMap<>();
    private final DiagnosticsEngine diagnostics;
    private final TokenMapContributorRegistry contributorRegistry;
    private final Map<String, ModuleId> fileToModule;
    private SymbolTable.Scope currentScopeObj;
    private String currentScopeName = "global";

    /**
     * Constructs a TokenMapGenerator.
     *
     * @param symbolTable         The fully resolved symbol table from the semantic analysis phase.
     * @param scopeMap            The map of AST nodes to their corresponding scopes from SemanticAnalyzer.
     * @param diagnostics         The diagnostics engine for reporting compilation errors.
     * @param contributorRegistry The registry of feature-specific token map contributors.
     * @param fileToModule        Mapping from source file paths to module identifiers.
     */
    public TokenMapGenerator(SymbolTable symbolTable, Map<AstNode, SymbolTable.Scope> scopeMap,
                             DiagnosticsEngine diagnostics, TokenMapContributorRegistry contributorRegistry,
                             Map<String, ModuleId> fileToModule) {
        this.symbolTable = Objects.requireNonNull(symbolTable, "SymbolTable cannot be null.");
        this.scopeMap = scopeMap;
        this.diagnostics = diagnostics;
        this.contributorRegistry = Objects.requireNonNull(contributorRegistry, "ContributorRegistry cannot be null.");
        this.fileToModule = fileToModule != null ? fileToModule : Map.of();
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
    public void addToken(SourceInfo sourceInfo, String text, Symbol.Type type, String scope) {
        tokenMap.put(sourceInfo, new TokenInfo(text, type, scope));
    }

    @Override
    public void addToken(SourceInfo sourceInfo, String text, Symbol.Type type, String scope, String qualifiedName) {
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
     * A stateful recursive walk method that manages the current scope via the scopeMap.
     * Scope transitions are determined generically by looking up the node in the scopeMap,
     * using {@link SymbolTable.Scope#name()} for the display name.
     *
     * @param node The current AST node to visit.
     */
    private void walkAndVisit(AstNode node) {
        if (node == null) {
            return;
        }

        SymbolTable.Scope previousScopeObj = this.currentScopeObj;
        String previousScopeName = this.currentScopeName;

        SymbolTable.Scope nodeScope = scopeMap.get(node);
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
            Optional<Symbol> symbolOpt = resolveInCurrentScope(identifierNode.text(), identifierNode.sourceInfo().fileName());
            if (symbolOpt.isPresent()) {
                Symbol sym = symbolOpt.get();
                SourceInfo si = identifierNode.sourceInfo();
                String qualifiedName = null;
                if (sym.type() == Symbol.Type.PROCEDURE || sym.type() == Symbol.Type.LABEL) {
                    String symFile = sym.name().fileName();
                    qualifiedName = qualifyName(sym.name().text(), symFile);
                }
                tokenMap.put(si, new TokenInfo(identifierNode.text(), sym.type(), this.currentScopeName, qualifiedName));
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
                String qualifiedAlias = qualifyName(registerNode.getOriginalAlias(), aliasSourceInfo.fileName());
                tokenMap.put(aliasSourceInfo, new TokenInfo(
                    registerNode.getOriginalAlias(),
                    Symbol.Type.ALIAS,
                    this.currentScopeName,
                    qualifiedAlias
                ));
            } else {
                SourceInfo regSourceInfo = registerNode.sourceInfo();
                tokenMap.put(regSourceInfo, new TokenInfo(
                    registerNode.getName(),
                    Symbol.Type.VARIABLE,
                    this.currentScopeName
                ));
            }
        }
    }

    private String qualifyName(String localName, String fileName) {
        ModuleId moduleId = fileToModule.get(fileName);
        String moduleName = moduleId != null
            ? ModuleId.deriveModuleName(moduleId.path())
            : ModuleId.deriveModuleName(fileName);
        return moduleName + "." + localName.toUpperCase();
    }

    /**
     * Resolves a symbol using the currently tracked scope object.
     */
    private Optional<Symbol> resolveInCurrentScope(String name, String fileName) {
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
