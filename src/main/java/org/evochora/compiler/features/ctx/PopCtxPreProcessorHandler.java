package org.evochora.compiler.features.ctx;

import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.model.token.Token;

/**
 * Handles the internal {@code .POP_CTX} directive in the preprocessor phase.
 * Pops the .SOURCE or .IMPORT inclusion chain when leaving an included block.
 * The token remains in the stream for the parser to create a PopCtxNode.
 */
public class PopCtxPreProcessorHandler implements IPreProcessorHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        Token token = preProcessor.peek();
        preProcessor.advance();

        if ("SOURCE".equals(token.value())) {
            preProcessor.popSourceChain();
        } else if ("IMPORT".equals(token.value())) {
            preProcessor.popImportChain();
            preProcessorContext.popAliasChain();
        }
    }
}
