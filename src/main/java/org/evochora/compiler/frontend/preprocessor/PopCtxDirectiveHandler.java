package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.model.Token;

/**
 * Handles the internal {@code .POP_CTX} directive in the preprocessor phase.
 * Pops the .SOURCE or .IMPORT inclusion chain when leaving an included block.
 * The token remains in the stream for the parser to create a PopCtxNode.
 */
class PopCtxDirectiveHandler implements IPreProcessorDirectiveHandler {

    @Override
    public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
        Token token = preProcessor.peek();
        preProcessor.advance();

        if ("SOURCE".equals(token.value())) {
            preProcessor.popSourceChain();
        } else if ("IMPORT".equals(token.value())) {
            preProcessor.popImportChain();
        }
    }
}
