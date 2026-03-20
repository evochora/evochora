package org.evochora.compiler.model.ast;

import org.evochora.compiler.api.SourceInfo;

/**
 * Capability interface for AST nodes that originate from a specific source file.
 * Used by the semantic analyzer and module context tracker to determine the source
 * file of a node for module context switching during traversal.
 *
 * <p>Not all AST nodes carry source location information (e.g., synthetic nodes
 * like PushCtxNode/PopCtxNode). This interface follows the Interface Segregation
 * Principle by separating source location from the general {@link AstNode} contract.
 */
public interface ISourceLocatable {

    /**
     * Returns the source location this node originated from.
     *
     * @return The source location, never null for nodes implementing this interface.
     */
    SourceInfo sourceInfo();
}
