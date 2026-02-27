package org.evochora.compiler.frontend.parser.ast;

/**
 * Capability interface for AST nodes that originate from a specific source file.
 * Used by the semantic analyzer to determine the source file of a node
 * for module context switching during traversal.
 *
 * <p>Not all AST nodes carry source location information (e.g., synthetic nodes
 * like PushCtxNode/PopCtxNode). This interface follows the Interface Segregation
 * Principle by separating source location from the general AstNode contract.
 */
public interface SourceLocatable {

    /**
     * Returns the file path of the source file this node originated from.
     *
     * @return The source file path, never null for nodes implementing this interface.
     */
    String getSourceFileName();
}
