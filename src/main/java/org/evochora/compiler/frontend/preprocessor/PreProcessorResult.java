package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.api.Expansion;
import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.model.token.Token;

import java.util.List;
import java.util.Map;

/**
 * The output of Phase 2 (preprocessing): the fully expanded token stream, and the entries of the
 * inclusions and the instances of injected tokens that are no inclusion, each with what the
 * handlers recorded in it for a source view.
 *
 * @param tokens         The fully expanded token stream ready for parsing.
 * @param sources        One entry per inclusion: the main file first, then every module placement
 *                       and every text inclusion in the order the preprocessor met it, each with
 *                       the regions of lines and the notes recorded at its positions.
 * @param expansions     Every instance of injected tokens that is no inclusion, by its number,
 *                       with where it came from and the regions and notes recorded in it.
 */
public record PreProcessorResult(
    List<Token> tokens,
    List<SourceFile> sources,
    Map<Integer, Expansion> expansions
) {
    /**
     * Makes the map of expansions immutable by copying it.
     */
    public PreProcessorResult {
        expansions = Map.copyOf(expansions);
    }
}
