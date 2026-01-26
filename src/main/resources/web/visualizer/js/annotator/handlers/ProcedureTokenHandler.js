import { AnnotationUtils } from '../AnnotationUtils.js';

/**
 * Handles the annotation of tokens that are procedure names.
 * It identifies tokens classified as 'PROCEDURE' and annotates them with the
 * hash value used for fuzzy jump matching at runtime.
 */
export class ProcedureTokenHandler {
    /**
     * Determines if this handler can process the given token.
     * It specifically handles tokens identified by the compiler as 'PROCEDURE' type.
     *
     * @param {string} tokenText The text of the token.
     * @param {object} tokenInfo Metadata about the token from the compiler.
     * @returns {boolean} True if the token is a 'PROCEDURE' type, false otherwise.
     */
    canHandle(tokenText, tokenInfo) {
        return tokenInfo.tokenType === 'PROCEDURE';
    }

    /**
     * Analyzes the procedure token to create a jump-target annotation.
     * It resolves the procedure name to its hash value using the artifact's
     * labelNameToValue map. The hash is the 20-bit value used for fuzzy matching.
     *
     * @param {string} tokenText The text of the token (the procedure name).
     * @param {object} tokenInfo Metadata about the token.
     * @param {object} organismState The current state of the organism (not used for hash lookup).
     * @param {object} artifact The program artifact containing `labelNameToValue` map.
     * @returns {object} An annotation object `{ annotationText, kind }`.
     * @throws {Error} If the procedure hash cannot be resolved.
     */
    analyze(tokenText, tokenInfo, organismState, artifact) {
        // Resolve procedure name to hash value for display
        const hashValue = AnnotationUtils.resolveLabelNameToHash(tokenText, artifact);

        if (hashValue === null || hashValue === undefined) {
            // Fallback: show "?" if hash not found
            return {
                annotationText: `[#?]`,
                kind: 'proc'
            };
        }

        // Format hash value as decimal with # prefix (e.g., "[#12345]")
        return {
            annotationText: `[#${hashValue}]`,
            kind: 'proc'
        };
    }
}

window.ProcedureTokenHandler = ProcedureTokenHandler;

