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
     */
    public RegisterNode(String name, SourceInfo sourceInfo) {
        this(name, null, sourceInfo);
    }

    /**
     * Constructor for registers created by resolving an alias.
     */
    public RegisterNode(String physicalName, String originalAlias, SourceInfo sourceInfo) {
        this.name = physicalName;
        this.originalAlias = originalAlias;
        this.sourceInfo = sourceInfo;
    }

    public String getName() {
        return name;
    }

    public String getOriginalAlias() {
        return originalAlias;
    }

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
