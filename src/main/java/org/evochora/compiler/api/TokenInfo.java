package org.evochora.compiler.api;

import org.evochora.compiler.frontend.semantics.Symbol;

/**
 * Represents detailed information about a single token for debugging purposes.
 * This information is generated after semantic analysis and provides deterministic
 * token classification without guessing.
 *
 * @param tokenText The literal text of the token (e.g., "my_label").
 * @param tokenType The type of the symbol (e.g., LABEL, PARAMETER, CONSTANT).
 * @param scope The scope in which this token is defined (e.g., a procedure name or "global").
 * @param isDefinition Whether this token represents a definition (true) or reference (false).
 */
public record TokenInfo(
    String tokenText,
    Symbol.Type tokenType,
    String scope,
    boolean isDefinition
) {}
