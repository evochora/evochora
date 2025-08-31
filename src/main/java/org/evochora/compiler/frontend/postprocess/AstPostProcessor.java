package org.evochora.compiler.frontend.postprocess;

import org.evochora.compiler.frontend.TreeWalker;
import org.evochora.compiler.frontend.parser.ast.*;
import org.evochora.compiler.frontend.parser.features.def.DefineNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.api.SourceInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A dedicated compiler phase that transforms the AST after semantic analysis.
 * It resolves identifiers like constants and register aliases into their concrete forms.
 * This runs *after* the TokenMapGenerator to ensure debug info is based on the original source.
 */
public class AstPostProcessor {
    private final SymbolTable symbolTable;
    private final Map<String, String> registerAliases;    // %COUNTER -> %DR0, %TMP -> %PR0
    private final Map<String, TypedLiteralNode> constants; // MAX_VALUE -> TypedLiteralNode(DATA, 42)
    private final Map<AstNode, AstNode> replacements = new HashMap<>();

    public AstPostProcessor(SymbolTable symbolTable, Map<String, String> registerAliases) {
        this.symbolTable = symbolTable;
        this.registerAliases = registerAliases;
        this.constants = new HashMap<>(); // Will be populated during first pass
    }

    /**
     * Transforms the given AST by replacing aliases and constants.
     * @param root The root of the AST to transform.
     * @return The transformed AST root.
     */
    public AstNode process(AstNode root) {
        // First pass: collect all replacements needed and build constants map
        Map<Class<? extends AstNode>, Consumer<AstNode>> handlers = new HashMap<>();
        handlers.put(IdentifierNode.class, this::collectReplacements);
        handlers.put(DefineNode.class, this::collectConstants);
        new TreeWalker(handlers).walk(root);
        
        // Second pass: apply the replacements
        return new TreeWalker(new HashMap<>()).transform(root, replacements);
    }

    private void collectReplacements(AstNode node) {
        if (!(node instanceof IdentifierNode idNode)) {
            return;
        }

        Optional<Symbol> symbolOpt = symbolTable.resolve(idNode.identifierToken());
        if (symbolOpt.isEmpty()) return;
        Symbol symbol = symbolOpt.get();

        if (symbol.type() == Symbol.Type.ALIAS) {
            // Check if this identifier is a register alias
            String identifierName = idNode.identifierToken().text();
            
            if (registerAliases.containsKey(identifierName)) {
                String resolvedName = registerAliases.get(identifierName);
                
                // Create a new token representing the resolved register
                // This token contains the resolved register text but keeps the original location
                Token resolvedRegisterToken = new Token(
                    TokenType.REGISTER,           // Now it's a register, not an identifier
                    resolvedName,                 // e.g., "%DR0" - the text
                    null,                        // No processed value for registers
                    idNode.identifierToken().line(),
                    idNode.identifierToken().column(),
                    idNode.identifierToken().fileName()
                );
                
                RegisterNode replacement = new RegisterNode(
                    resolvedName,                 // e.g., "%DR0"
                    identifierName,               // e.g., "COUNTER"
                    new SourceInfo(               // Create SourceInfo from the token
                        idNode.identifierToken().fileName(),
                        idNode.identifierToken().line(),
                        idNode.identifierToken().column()
                    ),
                    resolvedRegisterToken         // Token with resolved register text
                );
                replacements.put(idNode, replacement);
            }
        } else if (symbol.type() == Symbol.Type.CONSTANT) {
            // Check if this identifier is a constant
            String identifierName = idNode.identifierToken().text();
            
            if (constants.containsKey(identifierName)) {
                TypedLiteralNode constantValue = constants.get(identifierName);
                
                // Replace the identifier with the constant value
                replacements.put(idNode, constantValue);
            }
        }
    }
    
    private void collectConstants(AstNode node) {
        if (!(node instanceof DefineNode defineNode)) {
            return;
        }
        
        // Extract the constant name and value
        String constantName = defineNode.name().text();
        if (defineNode.value() instanceof TypedLiteralNode typedValue) {
            constants.put(constantName, typedValue);
        }
    }
}
