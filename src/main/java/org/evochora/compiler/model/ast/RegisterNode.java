package org.evochora.compiler.model.ast;

import org.evochora.compiler.api.SourceInfo;

/**
 * An AST node that represents a register.
 *
 * @param name          the physical register this node refers to, including the sigil
 *                      (e.g., "%DR0"), with any alias already resolved
 * @param originalAlias the alias this register was written as before resolution (e.g.,
 *                      "TMP"), which the token map reports in place of the physical name;
 *                      {@code null} for a register written directly
 * @param sourceInfo    the location of the register token or of the alias identifier that
 *                      was replaced
 */
public record RegisterNode(String name, String originalAlias, SourceInfo sourceInfo)
        implements OperandNode {

    /**
     * Constructor for registers written directly in code.
     *
     * @param name the register token as written, including the sigil (e.g., "%DR0")
     * @param sourceInfo the location of that token
     */
    public RegisterNode(String name, SourceInfo sourceInfo) {
        this(name, null, sourceInfo);
    }

    /**
     * Reports whether this node came from resolving a register alias rather than from a
     * register written directly in the source.
     *
     * @return {@code true} if an original alias name is present
     */
    public boolean isAlias() {
        return originalAlias != null;
    }

    @Override
    public String toString() {
        if (isAlias()) {
            return String.format("RegisterNode(name=%s, alias=%s)", name, originalAlias);
        }
        return String.format("RegisterNode(name=%s)", name);
    }
}
