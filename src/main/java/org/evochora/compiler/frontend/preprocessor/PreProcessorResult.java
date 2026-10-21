package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.api.SourceView;
import org.evochora.compiler.model.token.Token;

import java.util.List;

/**
 * The output of Phase 2 (preprocessing): the fully expanded token stream, and the entries of the
 * inclusions and the instances of injected tokens that are no inclusion, each with what the
 * handlers recorded in it for a source view.
 *
 * @param tokens         The fully expanded token stream ready for parsing.
 * @param view           One entry per inclusion: the main file first, then every module placement
 *                       and every text inclusion in the order the preprocessor met it; and every
 *                       instance of injected tokens that is no inclusion, by its number; each with
 *                       the regions of lines and the notes recorded in it.
 */
public record PreProcessorResult(
    List<Token> tokens,
    SourceView view
) {
}
