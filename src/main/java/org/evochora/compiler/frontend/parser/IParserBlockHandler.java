package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.frontend.BlockReader;
import org.evochora.compiler.model.ast.AstNode;

/**
 * Handler of a directive that opens a block during parsing. The parser reads the extent of the
 * block before it calls the handler and calls it only for a whole block, standing on the
 * opener; the handler reads the opener's line, has the parser parse the statements of the
 * block's parts through {@link IParsingContext#statements}, and builds the node. It never
 * looks for the closer: when it returns, the parser continues after the block.
 * <p>
 * A handler that gives up with an {@link org.evochora.compiler.diagnostics.ErrorRecoveryException}
 * leaves the whole block behind: the parser reports nothing more for it and continues after it.
 */
public interface IParserBlockHandler {

    /**
     * Parses the block whose opener the parser stands on.
     *
     * @param context The parsing context providing access to the token stream.
     * @param block   The extent of the block: its body, its dividers at its own level, its closer,
     *                and which of those words carry {@code EXPORT} before them.
     * @return The parsed AST node, or {@code null} if the block produces no node.
     */
    AstNode parse(IParsingContext context, BlockReader.Block block);

    /**
     * Returns whether {@code EXPORT} may stand before a word of the block: its opener, one of
     * its dividers or its closer. The parser reports an {@code EXPORT} before a word that does
     * not take it.
     *
     * @param word The block word, as the kind registered it.
     * @return {@code true} if {@code EXPORT} is valid before that word.
     */
    default boolean supportsExport(String word) {
        return false;
    }
}
