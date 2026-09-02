package org.evochora.compiler.model.ast;

import java.util.List;
import java.util.Objects;
import org.evochora.compiler.api.SourceInfo;

/**
 * An AST node that represents a register.
 */
public class RegisterNode implements AstNode, ISourceLocatable {
    private final String name;
    private final String originalAlias;
    private final SourceInfo sourceInfo;

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
     * Constructor for registers created by resolving an alias.
     *
     * @param physicalName the register the alias resolves to (e.g., "%PDR0")
     * @param originalAlias the alias as written in the source (e.g., "TMP"); passing
     *                      {@code null} makes the node indistinguishable from a
     *                      register written directly
     * @param sourceInfo the location of the alias identifier that was replaced
     */
    public RegisterNode(String physicalName, String originalAlias, SourceInfo sourceInfo) {
        this.name = physicalName;
        this.originalAlias = originalAlias;
        this.sourceInfo = sourceInfo;
    }

    /**
     * Returns the physical register this node refers to, with any alias already resolved.
     *
     * @return the register name including the sigil (e.g., "%DR0")
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the alias this register was written as before resolution, which the token
     * map reports in place of the physical name.
     *
     * @return the alias name, or {@code null} for a register written directly
     */
    public String getOriginalAlias() {
        return originalAlias;
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
    public SourceInfo sourceInfo() {
        return sourceInfo;
    }

    @Override
    public List<AstNode> getChildren() {
        return List.of();
    }

    @Override
    public String toString() {
        if (isAlias()) {
            return String.format("RegisterNode(name=%s, alias=%s)", name, originalAlias);
        }
        return String.format("RegisterNode(name=%s)", name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RegisterNode that = (RegisterNode) o;
        return Objects.equals(name, that.name) &&
               Objects.equals(originalAlias, that.originalAlias) &&
               Objects.equals(sourceInfo, that.sourceInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, originalAlias, sourceInfo);
    }
}
