package org.evochora.compiler.model.ast;

/**
 * Capability interface for AST nodes that represent register aliases.
 * Used by the framework to extract the target register during post-processing,
 * without depending on specific feature node types.
 */
public interface IRegisterAlias {

    /**
     * Returns the target register name (e.g., "%DR0", "%PDR2").
     */
    String register();
}
