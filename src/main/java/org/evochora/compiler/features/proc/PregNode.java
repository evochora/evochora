package org.evochora.compiler.features.proc;

import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;
import org.evochora.compiler.api.SourceInfo;

/**
 * AST node representing a .PREG directive within a procedure.
 * This directive creates a procedure-local register alias.
 *
 * @param sourceInfo the source location information
 * @param alias the alias name (e.g., %TMP)
 * @param targetRegister the target procedure register (e.g., %PR0)
 */
public record PregNode(
        SourceInfo sourceInfo,
        String alias,
        String targetRegister
) implements AstNode, ISourceLocatable {

    /**
     * Gets the alias name without the % prefix.
     */
    public String aliasName() {
        return alias.substring(1);
    }

    /**
     * Gets the procedure register index as an integer.
     */
    public int registerIndexValue() {
        return Integer.parseInt(targetRegister.substring(3));
    }

    /**
     * Gets the full alias text including the % prefix.
     */
    public String fullAliasText() {
        return alias;
    }
}
