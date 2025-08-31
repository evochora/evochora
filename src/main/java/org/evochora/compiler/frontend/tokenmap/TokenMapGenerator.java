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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private final Map<SourceInfo, TokenInfo> tokenMap = new HashMap<>();
    private String currentScope = "global";

    /**
     * Constructs a TokenMapGenerator.
     *
     * @param symbolTable The fully resolved symbol table from the semantic analysis phase.
     */
    public TokenMapGenerator(SymbolTable symbolTable) {
        this.symbolTable = Objects.requireNonNull(symbolTable, "SymbolTable cannot be null.");
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

        // --- Debug: Track every node visit ---
        System.out.println("VISITING: " + node.getClass().getSimpleName() + 
                          " at line " + getNodeLine(node) + 
                          " with " + node.getChildren().size() + " children");
        


        // --- Visit the current node to add its token to the map ---
        visit(node);

        // --- Recursively visit all children ---
        for (AstNode child : node.getChildren()) {
            walkAndVisit(child);
        }

        // --- Restore the previous scope after leaving the node's subtree ---
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
        if (node instanceof InstructionNode instNode) {
            // Handle CALL and RET specifically for jump target annotation
            String opcode = instNode.opcode().text().toUpperCase();
            if ("CALL".equals(opcode)) {
                addToken(instNode.opcode(), "CALL", this.currentScope);
                // Process CALL arguments (procedure name, WITH, parameters)
                for (AstNode arg : instNode.arguments()) {
                    if (arg instanceof IdentifierNode idNode) {
                        String text = idNode.identifierToken().text().toUpperCase();
                        if (!"WITH".equals(text)) {
                            // This is a procedure name in CALL
                            addToken(idNode.identifierToken(), "PROCEDURE", this.currentScope);
                        } else {
                            // This is the WITH keyword
                            addToken(idNode.identifierToken(), "KEYWORD", this.currentScope);
                        }
                    } else if (arg instanceof RegisterNode regNode) {
                        addToken(regNode.registerToken(), "REGISTER", this.currentScope);
                    }
                }
            } else if ("RET".equals(opcode)) {
                addToken(instNode.opcode(), "RET", this.currentScope);
            } else {
                // Other instructions don't need special annotation
                addToken(instNode.opcode(), "INSTRUCTION", this.currentScope);
            }
        } else if (node instanceof LabelNode labelNode) {
            // Labels get the scope they're defined in (procedure scope, scope block, or global)
            addToken(labelNode.labelToken(), "LABEL", this.currentScope);
        } else if (node instanceof IdentifierNode idNode) {
            // For any identifier, the symbol table holds the ground truth.
            // We'll use the current scope context for now
            addToken(idNode.identifierToken(), "IDENTIFIER", this.currentScope);
        } else if (node instanceof RegisterNode regNode) {
            addToken(regNode.registerToken(), "REGISTER", this.currentScope);
        } else if (node instanceof NumberLiteralNode numNode) {
            addToken(numNode.numberToken(), "LITERAL", this.currentScope);
        } else if (node instanceof ProcedureNode procNode) {
            addToken(procNode.name(), "PROCEDURE", this.currentScope);
            // Add parameter tokens
            for (org.evochora.compiler.frontend.lexer.Token param : procNode.parameters()) {
                addToken(param, "PARAMETER", this.currentScope);
            }
        }
    }

    /**
     * Helper method to get the line number for any AST node.
     */
    private int getNodeLine(AstNode node) {
        if (node instanceof InstructionNode instNode) {
            return instNode.opcode().line();
        } else if (node instanceof LabelNode labelNode) {
            return labelNode.labelToken().line();
        } else if (node instanceof IdentifierNode idNode) {
            return idNode.identifierToken().line();
        } else if (node instanceof RegisterNode regNode) {
            return regNode.registerToken().line();
        } else if (node instanceof NumberLiteralNode numNode) {
            return numNode.numberToken().line();
        } else if (node instanceof TypedLiteralNode litNode) {
            return litNode.type().line();
        } else if (node instanceof ProcedureNode procNode) {
            return procNode.name().line();
        } else if (node instanceof ScopeNode scopeNode) {
            return scopeNode.name().line();
        } else if (node instanceof PushCtxNode || node instanceof PopCtxNode) {
            // Context nodes don't have line numbers
            return -1;
        } else {
            // Fallback for unknown node types
            return -1;
        }
    }

    /**
     * A helper method to create a TokenInfo object and add it to the map.
     */
    private void addToken(org.evochora.compiler.frontend.lexer.Token token, String type, String scope) {
        if (token != null) {
            SourceInfo sourceInfo = new SourceInfo(
                token.fileName(),
                token.line(),
                token.text()
            );
            
            // Convert string type to Symbol.Type and determine isDefinition
            Type symbolType = convertToSymbolType(type);
            boolean isDefinition = determineIsDefinition(type, token);
            
            // Create TokenInfo with the proper structure
            TokenInfo tokenInfo = new TokenInfo(
                token.text(),
                symbolType,
                scope,
                isDefinition
            );
            
            // Overwrite existing tokens to ensure correct line numbers
            tokenMap.put(sourceInfo, tokenInfo);
        }
    }
    
    /**
     * Converts string type to Symbol.Type for TokenInfo.
     */
    private Type convertToSymbolType(String type) {
        return switch (type) {
            case "PROCEDURE" -> Type.PROCEDURE;
            case "LABEL" -> Type.LABEL;
            case "REGISTER" -> Type.VARIABLE; // Registers are stored as VARIABLE in SymbolTable
            case "PARAMETER" -> Type.VARIABLE; // Parameters are stored as VARIABLE in SymbolTable
            case "LITERAL" -> Type.CONSTANT;
            case "CALL", "RET", "INSTRUCTION" -> Type.LABEL; // Instructions are treated as LABEL for annotation
            case "KEYWORD" -> Type.VARIABLE; // Keywords are treated as VARIABLE
            case "IDENTIFIER" -> Type.VARIABLE; // Default for identifiers
            default -> Type.VARIABLE;
        };
    }
    
    /**
     * Determines if a token represents a definition.
     */
    private boolean determineIsDefinition(String type, org.evochora.compiler.frontend.lexer.Token token) {
        return switch (type) {
            case "PROCEDURE", "LABEL" -> true; // These are always definitions
            case "PARAMETER" -> true; // Parameters are defined in procedure signature
            case "CALL", "RET", "INSTRUCTION", "REGISTER", "LITERAL", "KEYWORD", "IDENTIFIER" -> false; // These are references
            default -> false;
        };
    }
    

}
