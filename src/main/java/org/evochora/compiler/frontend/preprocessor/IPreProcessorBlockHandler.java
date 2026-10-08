package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.frontend.BlockReader;

/**
 * Handler of a directive that opens a block during preprocessing. The preprocessor reads the
 * extent of the block before it calls the handler and calls it only for a whole block, with the
 * walk standing on the opener; the handler reads the opener's line, takes the body from the
 * block and rewrites the stream, closer included. It never looks for the closer itself.
 * <p>
 * A handler that gives up with an {@link org.evochora.compiler.diagnostics.ErrorRecoveryException}
 * has not changed the stream: the preprocessor then continues after the block, so that nothing
 * of a failed block reaches another handler.
 */
public interface IPreProcessorBlockHandler {

    /**
     * Processes the block whose opener the walk stands on.
     *
     * @param preProcessor        The preprocessor, providing direct access to the token stream.
     * @param preProcessorContext The shared preprocessor state.
     * @param block               The extent of the block, read on the stream as it stands.
     */
    void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext, BlockReader.Block block);
}
