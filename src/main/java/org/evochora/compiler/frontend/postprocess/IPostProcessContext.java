package org.evochora.compiler.frontend.postprocess;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.ExpressionOutcome;

import java.util.Optional;

/**
 * Context provided to {@link IPostProcessHandler} implementations during Phase 6 (AST Post-Processing).
 *
 * <p>The phase replaces identifiers by asking the nodes that define them, see
 * {@link org.evochora.compiler.model.ast.IIdentifierBinding}, so a handler has nothing to
 * record for that. A handler whose node carries a definition reads the value phase 4 recorded for
 * it, and puts a node of its own in the node's place.</p>
 */
public interface IPostProcessContext {

    /**
     * Returns the value phase 4 recorded for the definition a name leads to, looked up where the
     * name is written, in the scope the phase stands in.
     *
     * @param name The name as written.
     * @param at   The position the name is written at.
     * @return The recorded value: a value, or the tree a later phase computes with its kind;
     *         empty if the name leads to no definition or none was recorded for it.
     */
    Optional<ExpressionOutcome> definitionValue(String name, SourceInfo at);

    /**
     * Replaces a node of the tree. A replacement of a node takes the place of every replacement
     * inside it, so that the node is taken as given.
     *
     * @param node        The node as the phase walks it, matched by identity.
     * @param replacement The node to put in its place.
     */
    void replace(AstNode node, AstNode replacement);
}
