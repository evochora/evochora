package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.model.token.Token;

import java.util.List;
import java.util.Map;

/**
 * The output of Phase 2 (preprocessing): the fully expanded token stream, and what the handlers
 * recorded about the source text for a source view.
 *
 * @param tokens  The fully expanded token stream ready for parsing.
 * @param leftOut The regions of lines the handlers left out, by placement, then by resolved file
 *                path, in the order they were recorded.
 * @param notes   The notes the handlers attached to positions, by placement, then by resolved file
 *                path, in the order they were recorded.
 */
public record PreProcessorResult(
    List<Token> tokens,
    Map<String, Map<String, List<SourceFile.LeftOut>>> leftOut,
    Map<String, Map<String, List<SourceFile.Note>>> notes
) {}
