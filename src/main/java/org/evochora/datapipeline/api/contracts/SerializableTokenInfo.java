package org.evochora.datapipeline.api.contracts;

/**
 * Serializable version of TokenInfo for datapipeline API.
 * Contains the same information as the compiler API TokenInfo but in a serializable format.
 *
 * @param tokenText The literal text of the token (e.g., "my_label")
 * @param tokenType The type of the symbol as string (e.g., "LABEL", "PARAMETER", "CONSTANT")
 * @param scope The scope in which this token is defined (e.g., a procedure name or "global")
 */
public record SerializableTokenInfo(
    String tokenText,
    String tokenType,
    String scope
) {}
