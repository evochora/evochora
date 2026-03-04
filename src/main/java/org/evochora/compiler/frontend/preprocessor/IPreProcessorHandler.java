package org.evochora.compiler.frontend.preprocessor;

/**
 * Handler interface for operations processed during the preprocessing phase.
 * Preprocessing handlers operate directly on the token stream (expanding macros,
 * inlining source files, repeating blocks) before the parser builds the AST.
 */
public interface IPreProcessorHandler {

    /**
     * Processes the matched token by modifying the token stream.
     *
     * @param preProcessor     The preprocessor, providing direct access to the token stream.
     * @param preProcessorContext The shared preprocessor state (alias chains, etc.).
     */
    void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext);
}
