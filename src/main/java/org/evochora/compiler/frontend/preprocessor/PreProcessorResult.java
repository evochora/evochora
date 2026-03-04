package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.model.token.Token;

import java.util.List;
import java.util.Map;

/**
 * The output of Phase 2 (preprocessing). Contains the expanded token stream
 * and all source file contents encountered during preprocessing.
 *
 * @param tokens           The fully expanded token stream ready for parsing.
 * @param includedSources  Map of file paths to their source content, for all files
 *                         included via {@code .SOURCE} directives during preprocessing.
 */
public record PreProcessorResult(
    List<Token> tokens,
    Map<String, String> includedSources
) {}
