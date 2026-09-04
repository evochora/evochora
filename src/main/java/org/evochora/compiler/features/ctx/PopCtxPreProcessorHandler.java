package org.evochora.compiler.features.ctx;

import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

/**
 * Handles the internal {@code .POP_CTX} directive in the preprocessor phase.
 * Leaves the inclusion that the matching {@code .PUSH_CTX} opened.
 * The token remains in the stream for the parser to create a PopCtxNode.
 */
public class PopCtxPreProcessorHandler implements IPreProcessorHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        preProcessor.advance();
        preProcessorContext.leaveInclusion();
    }
}
