package org.evochora.compiler.frontend.parser.ast;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.Token;

/**
 * AST node representing a .PREG directive within a procedure.
 * This directive creates a procedure-local register alias.
 */
public record PregNode(
        SourceInfo sourceInfo,
        Token alias,
        Token targetRegister
) implements AstNode {
    
    /**
     * Creates a new PregNode with the specified components.
     *
     * @param sourceInfo the source location information
     * @param alias the alias token (e.g., %TMP)
     * @param registerIndex the procedure register index token (0 or 1)
     */
    public PregNode {
        // No validation needed - the compiler architecture handles this through diagnostics
    }
    
    @Override
    public SourceInfo sourceInfo() {
        return sourceInfo;
    }
    
    /**
     * Gets the alias name as a string.
     *
     * @return the alias name without the % prefix
     */
    public String aliasName() {
        return alias.text().substring(1); // Remove % prefix
    }
    
    /**
     * Gets the procedure register index as an integer.
     *
     * @return the procedure register index (0 or 1)
     */
    public int registerIndexValue() {
        return Integer.parseInt(targetRegister.text().substring(3)); // Extract index from %PR0 or %PR1
    }
    
    /**
     * Gets the full alias text including the % prefix.
     *
     * @return the full alias text (e.g., %TMP)
     */
    public String fullAliasText() {
        return alias.text();
    }
}
