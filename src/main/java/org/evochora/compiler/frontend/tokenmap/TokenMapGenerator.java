package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.frontend.parser.ast.*;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.parser.features.scope.ScopeNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.frontend.semantics.Symbol.Type;
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
 */
public class TokenMapGenerator {

    private final SymbolTable symbolTable;
    private final Map<AstNode, SymbolTable.Scope> scopeMap;
    private final Map<SourceInfo, TokenInfo> tokenMap = new HashMap<>();
    private final DiagnosticsEngine diagnostics;
    private String currentScope = "global";

    /**
     * Constructs a TokenMapGenerator.
     *
     * @param symbolTable The fully resolved symbol table from the semantic analysis phase.
     * @param scopeMap The map of AST nodes to their corresponding scopes from SemanticAnalyzer.
     * @param diagnostics The diagnostics engine for reporting compilation errors.
     */
    public TokenMapGenerator(SymbolTable symbolTable, Map<AstNode, SymbolTable.Scope> scopeMap, DiagnosticsEngine diagnostics) {
        this.symbolTable = Objects.requireNonNull(symbolTable, "SymbolTable cannot be null.");
        this.scopeMap = scopeMap;
        this.diagnostics = diagnostics;
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
     * Transposes the token map to a file->line->tokens structure for easier line-based lookup.
     *
     * @return A nested map structure: fileName -> lineNumber -> list of tokens
     */
    public Map<String, Map<Integer, List<TokenInfo>>> transposeToFileLineStructure() {
        Map<String, Map<Integer, List<TokenInfo>>> result = new HashMap<>();
        
        for (Map.Entry<SourceInfo, TokenInfo> entry : tokenMap.entrySet()) {
            SourceInfo sourceInfo = entry.getKey();
            TokenInfo tokenInfo = entry.getValue();
            
            String fileName = sourceInfo.fileName();
            Integer lineNumber = sourceInfo.lineNumber();
            
            result.computeIfAbsent(fileName, k -> new HashMap<>())
                  .computeIfAbsent(lineNumber, k -> new ArrayList<>())
                  .add(tokenInfo);
        }
        
        return result;
    }

    /**
     * A custom, stateful recursive walk method that manages the current scope.
     * It correctly handles entering and exiting scopes defined by procedures or .scope directives.
     *
     * @param node The current AST node to visit.
     */
    private void walkAndVisit(AstNode node) {
        if (node == null) {
            return;
        }

        String previousScope = this.currentScope;
        boolean isNewScopeNode = node instanceof ProcedureNode || node instanceof ScopeNode;

        if (node instanceof ProcedureNode procNode) {
            this.currentScope = procNode.name().text().toUpperCase();
        } else if (node instanceof ScopeNode scopeNode) {
            this.currentScope = scopeNode.name().text().toUpperCase();
        }

        // Visit the current node to add its token to the map
        visit(node);

        // Recursively visit all children
        for (AstNode child : node.getChildren()) {
            walkAndVisit(child);
        }

        // Restore the previous scope after leaving the node's subtree
        if (isNewScopeNode) {
            this.currentScope = previousScope;
        }
    }

    /**
     * Processes a single AST node, identifies its token type, and adds it to the token map.
     * It uses the SymbolTable as the definitive source for resolving identifiers.
     *
     * @param node The AST node to process.
     */
    private void visit(AstNode node) {
        if (node instanceof ProcedureNode procNode) {
            // Add the procedure name token to global scope
            addToken(procNode.name(), Symbol.Type.PROCEDURE, "global");
            
            // Add parameter tokens as VARIABLE type in the procedure's scope
            // Parameters are metadata stored in the procedure node, not separate AST nodes
            for (org.evochora.compiler.frontend.lexer.Token param : procNode.parameters()) {
                addToken(param, Symbol.Type.VARIABLE, procNode.name().text().toUpperCase());
            }
        } else if (node instanceof IdentifierNode identifierNode) {
            // Add the identifier token - resolve its type from symbol table
            // Use the current scope context for proper resolution
            Optional<Symbol> symbolOpt = resolveInCurrentScope(identifierNode.identifierToken());
            if (symbolOpt.isPresent()) {
                addToken(identifierNode.identifierToken(), symbolOpt.get().type(), this.currentScope);
            } else {
                // If symbol not found, report it as a compilation error using DiagnosticsEngine
                diagnostics.reportError(
                    "Symbol '" + identifierNode.identifierToken().text() + 
                    "' could not be resolved. This indicates a semantic analysis failure.",
                    identifierNode.identifierToken().fileName(),
                    identifierNode.identifierToken().line()
                );
                // Continue compilation - don't add this token to the map
            }
        } else if (node instanceof RegisterNode registerNode) {
            // Add the register token
            addToken(registerNode.registerToken(), Symbol.Type.VARIABLE, this.currentScope);
        } else if (node instanceof InstructionNode instructionNode) {
            // Only add CALL and RET instructions to token map
            String opcode = instructionNode.opcode().text();
            if ("CALL".equalsIgnoreCase(opcode) || "RET".equalsIgnoreCase(opcode)) {
                addToken(instructionNode.opcode(), Symbol.Type.CONSTANT, this.currentScope);
            }
        }
    }

    /**
     * Resolves a symbol in the current scope context.
     */
    private Optional<Symbol> resolveInCurrentScope(org.evochora.compiler.frontend.lexer.Token token) {
        // Temporarily set the symbol table's current scope to our tracked scope
        SymbolTable.Scope originalScope = symbolTable.getCurrentScope();
        
        // Find the scope object that corresponds to our current scope name
        SymbolTable.Scope targetScope = findScopeByName(this.currentScope);
        if (targetScope != null) {
            symbolTable.setCurrentScope(targetScope);
        }
        
        try {
            return symbolTable.resolve(token);
        } finally {
            // Restore the original scope
            symbolTable.setCurrentScope(originalScope);
        }
    }

    /**
     * Finds a scope by name by searching through the scope map.
     */
    private SymbolTable.Scope findScopeByName(String scopeName) {
        for (Map.Entry<AstNode, SymbolTable.Scope> entry : scopeMap.entrySet()) {
            if (entry.getKey() instanceof ProcedureNode procNode) {
                if (procNode.name().text().toUpperCase().equals(scopeName)) {
                    return entry.getValue();
                }
            } else if (entry.getKey() instanceof ScopeNode scopeNode) {
                if (scopeNode.name().text().toUpperCase().equals(scopeName)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /**
     * A helper method to create a TokenInfo object and add it to the map.
     */
    private void addToken(org.evochora.compiler.frontend.lexer.Token token, Symbol.Type type, String scope) {
        if (token != null) {
            SourceInfo sourceInfo = new SourceInfo(
                token.fileName(),
                token.line(),
                token.column(),
                token.text()
            );
            
            // Create TokenInfo with the proper structure
            TokenInfo tokenInfo = new TokenInfo(
                token.text(),
                type,
                scope
            );
            
            // Overwrite existing tokens to ensure correct line numbers
            tokenMap.put(sourceInfo, tokenInfo);
        }
    }
}
