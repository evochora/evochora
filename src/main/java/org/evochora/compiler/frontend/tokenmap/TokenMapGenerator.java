package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.api.PositionedNote;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.api.TokenKind;
import org.evochora.compiler.expression.INameResolution;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.expression.Expression;
import org.evochora.compiler.model.expression.LiteralExpression;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.expression.OperationExpression;
import org.evochora.compiler.model.ast.RegisterNode;

import org.evochora.compiler.frontend.ModuleContextTracker;
import org.evochora.compiler.model.symbols.ResolvedSymbol;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 * (IdentifierNode, ExpressionNode, RegisterNode) are handled as default logic: an identifier is
 * classified by the symbol it names, every name of a computation but the cursor as an identifier
 * at the position of its token, a register as a register. A computation whose value phase 4
 * computed is noted with {@code [=value]} at its last token, for the source view to show; one
 * that only the layout computes is noted by the layout.
 */
public class TokenMapGenerator implements ITokenMapContext {

    private final SymbolTable symbolTable;
    private final Map<SourceInfo, TokenInfo> tokenMap = new HashMap<>();
    private final Set<PositionedNote> notes = new LinkedHashSet<>();
    private final DiagnosticsEngine diagnostics;
    private final TokenMapContributorRegistry contributorRegistry;
    private final ModuleContextTracker contextTracker;
    private SymbolTable.Scope currentScopeObj;
    private String currentScopeName = TokenInfo.MODULE_LEVEL;

    /**
     * Constructs a TokenMapGenerator.
     *
     * @param symbolTable         The fully resolved symbol table from the semantic analysis phase.
     * @param diagnostics         The diagnostics engine for reporting compilation errors.
     * @param contributorRegistry The registry of feature-specific token map contributors.
     * @param contextTracker      The module context tracker, which keeps the symbol table's current
     *                            module in step with the placement of the nodes walked.
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
     * @return A map where the key is the SourceInfo (location) of a token, with instance 0, and
     *         the value is the detailed TokenInfo.
     */
    public Map<SourceInfo, TokenInfo> generate(AstNode root) {
        walkAndVisit(root);
        return tokenMap;
    }

    /**
     * Generates the token map and the notes of the computed values by walking all provided AST
     * nodes. This is useful when there are multiple top-level nodes (e.g., multiple procedures).
     *
     * @param nodes The list of AST nodes to traverse.
     * @return The token map, keyed by the position of a token with instance 0, and the note
     *         {@code [=value]} at the last token of every computation phase 4 computed in a file,
     *         at its full position, each distinct note once, in the order the walk met them.
     */
    public TokenMapResult generateAll(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            if (node != null) {
                walkAndVisit(node);
            }
        }
        return new TokenMapResult(tokenMap, List.copyOf(notes));
    }

    // === ITokenMapContext implementation ===

    @Override
    public void addToken(SourceInfo sourceInfo, String text, TokenKind type, String scope) {
        put(sourceInfo, new TokenInfo(text, type, scope));
    }

    @Override
    public void addToken(SourceInfo sourceInfo, String text, TokenKind type, String scope, String qualifiedName) {
        put(sourceInfo, new TokenInfo(text, type, scope, qualifiedName));
    }

    /**
     * Enters a token under its position without its instance. The map describes the text of a
     * line, which is the same in every instance of injected tokens, and the debugger annotates
     * a token by its position.
     */
    private void put(SourceInfo sourceInfo, TokenInfo tokenInfo) {
        tokenMap.put(sourceInfo.withoutExpansion(), tokenInfo);
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
            classifyName(identifierNode.text(), identifierNode.sourceInfo());
        } else if (node instanceof ExpressionNode expressionNode) {
            classifyNames(expressionNode.expression());
            noteValue(expressionNode);
        } else if (node instanceof RegisterNode registerNode) {
            // A register is written as a register here: aliases and parameters are still
            // identifiers in Phase 5 and are replaced by registers only in Phase 6.
            put(registerNode.sourceInfo(), new TokenInfo(
                registerNode.name(),
                TokenKind.REGISTER,
                this.currentScopeName
            ));
        }
    }

    /**
     * Notes the value phase 4 recorded for a computation at the position of its last token; a
     * computation the layout computes, one that failed, and one outside every file get no note
     * here.
     */
    private void noteValue(ExpressionNode expression) {
        if (!expression.end().inAFile()) {
            return;
        }
        symbolTable.resultOf(expression)
                .filter(outcome -> outcome.value() != null)
                .ifPresent(outcome -> notes.add(new PositionedNote(expression.end(), "[=" + outcome.value().text() + "]")));
    }

    /**
     * Classifies a name by the symbol it names, at the position of its token.
     */
    private void classifyName(String name, SourceInfo at) {
        Optional<ResolvedSymbol> symbolOpt = resolveInCurrentScope(name, at);
        if (symbolOpt.isPresent()) {
            ResolvedSymbol resolved = symbolOpt.get();
            Symbol sym = resolved.symbol();
            put(at, new TokenInfo(name, TokenKindMapper.map(sym.type()), resolved.scope(), resolved.qualifiedName()));
        } else {
            diagnostics.reportError(
                "Internal error: symbol '" + name +
                "' could not be resolved although the semantic analysis accepted it.",
                at.fileName(),
                at.lineNumber()
            );
        }
    }

    /**
     * Classifies every name of a computation as an identifier with that name, except the
     * cursor, which names no symbol.
     */
    private void classifyNames(Expression expression) {
        switch (expression) {
            case NameExpression name -> {
                if (!INameResolution.CURSOR.equals(name.name())) {
                    classifyName(name.name(), name.sourceInfo());
                }
            }
            case OperationExpression operation -> operation.operands().forEach(this::classifyNames);
            case LiteralExpression literal -> {
                // A literal names nothing
            }
        }
    }

    /**
     * Resolves a symbol using the currently tracked scope object.
     */
    private Optional<ResolvedSymbol> resolveInCurrentScope(String name, SourceInfo at) {
        SymbolTable.Scope originalScope = symbolTable.getCurrentScope();
        if (this.currentScopeObj != null) {
            symbolTable.setCurrentScope(this.currentScopeObj);
        }
        try {
            return symbolTable.resolve(name, at).found();
        } finally {
            symbolTable.setCurrentScope(originalScope);
        }
    }
}
