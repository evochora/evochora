package org.evochora.compiler.model.ast;

/**
 * Capability interface for AST nodes that carry a parameter's target register binding.
 * Used by the AstPostProcessor to resolve parameter identifiers to RegisterNodes
 * without depending on specific feature node types.
 */
public interface IParameterBinding {

    /**
     * Returns the target formal register (e.g., "%FDR0", "%FLR1").
     *
     * @return the target register name, never null
     */
    String targetRegister();
}
