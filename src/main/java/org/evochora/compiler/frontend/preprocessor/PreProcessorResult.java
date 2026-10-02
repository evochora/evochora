package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.model.token.Token;

import java.util.List;

/**
 * The output of Phase 2 (preprocessing): the fully expanded token stream, and the source files
 * with what the handlers recorded about their text for a source view.
 *
 * @param tokens  The fully expanded token stream ready for parsing.
 * @param sources The source files of the compilation, once per placement, each with the regions
 *                of lines and the notes recorded at positions of its placement and resolved path.
 */
public record PreProcessorResult(
    List<Token> tokens,
    List<SourceFile> sources
) {}
