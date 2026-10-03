package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.model.token.Token;

import java.util.List;
import java.util.Map;

/**
 * The output of Phase 2 (preprocessing): the fully expanded token stream, the entries of the
 * inclusions with what the handlers recorded about their text for a source view, and where the
 * instances of injected tokens that are no inclusion stand.
 *
 * @param tokens         The fully expanded token stream ready for parsing.
 * @param sources        One entry per inclusion: the main file first, then every module placement
 *                       and every text inclusion in the order the preprocessor met it, each with
 *                       the regions of lines and the notes recorded at its positions.
 * @param expansionHomes For every instance of injected tokens that is no inclusion of its own, the
 *                       instance of the entry whose lines its tokens stand on.
 */
public record PreProcessorResult(
    List<Token> tokens,
    List<SourceFile> sources,
    Map<Integer, Integer> expansionHomes
) {
    /**
     * Makes the map of homes immutable by copying it.
     */
    public PreProcessorResult {
        expansionHomes = Map.copyOf(expansionHomes);
    }
}
