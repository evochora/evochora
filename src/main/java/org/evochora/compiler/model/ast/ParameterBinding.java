package org.evochora.compiler.model.ast;

import java.util.List;
import java.util.Objects;

/**
 * Synthetic AST node carrying a parameter's compile-time register binding.
 * Created by ProcedureSymbolCollector and stored on the Symbol's node field.
 * Not part of the parsed AST — exists solely as a data carrier for Phase 6 resolution.
 *
 * @param targetRegister The target formal register (e.g., "%FDR0", "%FLR1").
 */
public record ParameterBinding(String targetRegister) implements AstNode, IParameterBinding {

    public ParameterBinding {
        Objects.requireNonNull(targetRegister, "targetRegister must not be null");
    }

    @Override
    public List<AstNode> getChildren() {
        return List.of();
    }
}
