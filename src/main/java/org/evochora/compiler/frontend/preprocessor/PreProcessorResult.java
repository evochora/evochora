package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.model.token.Token;

import java.util.List;

/**
 * The output of Phase 2 (preprocessing). Contains the fully expanded token stream.
 *
 * @param tokens The fully expanded token stream ready for parsing.
 */
public record PreProcessorResult(
    List<Token> tokens
) {}
