package org.evochora.compiler.features.proc;

import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IIdentifierBinding;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.RegisterNode;

import java.util.List;
import java.util.Objects;

/**
 * Synthetic AST node carrying a procedure parameter's compile-time register binding.
 * Created by {@link ProcedureSymbolCollector} and stored on the parameter's symbol.
 * Not part of the parsed AST: it exists so that an identifier naming the parameter can be
 * replaced by the formal register the parameter is passed in.
 *
 * @param targetRegister The target formal register (e.g., "%FDR0", "%FLR1").
 */
public record ParameterBinding(String targetRegister) implements AstNode, IIdentifierBinding {

    /**
     * Rejects a missing target register, since a binding without one cannot be resolved.
     *
     * @throws NullPointerException if {@code targetRegister} is null
     */
    public ParameterBinding {
        Objects.requireNonNull(targetRegister, "targetRegister must not be null");
    }

    /**
     * Returns an empty list — this synthetic node has no children.
     */
    @Override
    public List<AstNode> getChildren() {
        return List.of();
    }

    /**
     * Replaces a reference to the parameter by its formal register, keeping the parameter
     * name as the alias the token map reports.
     */
    @Override
    public AstNode bind(IdentifierNode reference) {
        return new RegisterNode(targetRegister, reference.text().toUpperCase(), reference.sourceInfo());
    }
}
