package org.evochora.server.indexer.annotation.enums;

/**
 * Represents the classification of a token for annotation purposes.
 * Each type corresponds to a specific handler that processes the token.
 */
public enum TokenType {
    LABEL_REFERENCE,      // Token that resolves to a label (including procedure names)
    REGISTER_REFERENCE,   // Token that resolves to a register or alias
    PARAMETER_NAME,       // Token that's a procedure parameter
    CALL_INSTRUCTION,     // Special: CALL instruction (future: compiler-generated instructions)
    RET_INSTRUCTION       // Special: RET instruction (future: compiler-generated instructions)
}
