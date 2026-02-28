package org.evochora.compiler.frontend.parser.ast;

import java.util.List;
import java.util.Objects;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.Token;

/**
 * An AST node that represents a register.
 */
public class RegisterNode implements AstNode {
    private final String name;
    private final String originalAlias; // <-- NEW FIELD
    private final SourceInfo sourceInfo;
    private final Token registerToken; // <-- ORIGINAL TOKEN FIELD

    /**
     * Constructor for registers written directly in code.
     */
    public RegisterNode(String name, SourceInfo sourceInfo, Token registerToken) {
        this(name, null, sourceInfo, registerToken); // No original alias
    }

    /**
     * Constructor for registers created by resolving an alias.
     */
    public RegisterNode(String physicalName, String originalAlias, SourceInfo sourceInfo, Token registerToken) {
        this.name = physicalName;
        this.originalAlias = originalAlias;
        this.sourceInfo = sourceInfo;
        this.registerToken = registerToken;
    }

    public String getName() {
        return name;
    }

    public String getOriginalAlias() {
        return originalAlias; // <-- NEW GETTER
    }

    public boolean isAlias() {
        return originalAlias != null; // <-- NEW HELPER
    }
    
    public SourceInfo getSourceInfo() {
        return sourceInfo;
    }

    public Token registerToken() {
        return registerToken;
    }
    
    @Override
    public List<AstNode> getChildren() {
        return List.of(); // RegisterNode has no children
    }
    
    @Override
    public String toString() {
        if (isAlias()) {
            return String.format("RegisterNode(name=%s, alias=%s)", name, originalAlias);
        }
        return String.format("RegisterNode(name=%s)", name);
    }
    
    // equals() and hashCode() updated to include all fields
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RegisterNode that = (RegisterNode) o;
        return Objects.equals(name, that.name) && 
               Objects.equals(originalAlias, that.originalAlias) &&
               Objects.equals(registerToken, that.registerToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, originalAlias, registerToken);
    }
}