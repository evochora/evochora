package org.evochora.compiler.frontend.directive;

import org.evochora.compiler.frontend.CompilerPhase;
import org.evochora.compiler.frontend.parser.ParsingContext;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

/**
 * The base interface for all directive handlers.
 * Each handler is responsible for processing a specific directive (e.g., ".DEFINE").
 */
public interface IDirectiveHandler {

    /**
     * Specifies the compiler phase in which this handler should be active.
     * @return The phase in which the handler is executed.
     */
    CompilerPhase getPhase();

    /**
     * Parses the directive and its arguments.
     *
     * @param context The context that provides access to the token stream and other
     *                information relevant to the current phase.
     * @return A corresponding AST node for this directive, or {@code null}
     *         if the directive does not produce a direct node in the AST (e.g., .DEFINE).
     */
    AstNode parse(ParsingContext context);

    /**
     * Parses a preprocessor directive.
     * This method is only called for handlers that run in the PREPROCESSING phase.
     *
     * @param parsingContext The context for the token stream.
     * @param preProcessorContext The context for preprocessor definitions (macros, etc.).
     */
    default void parse(ParsingContext parsingContext, PreProcessorContext preProcessorContext) {
        // Default implementation so that not every handler has to implement this method.
        parse(parsingContext);
    }
}