/**
 * Handles the annotation of tokens that are references to labels or procedures.
 * It identifies tokens classified as 'LABEL' and annotates them with the
 * absolute world coordinates of their target location.
 */
class LabelReferenceTokenHandler {
    /**
     * Determines if this handler can process the given token.
     * It specifically handles tokens identified by the compiler as 'LABEL' type.
     *
     * @param {string} tokenText The text of the token.
     * @param {object} tokenInfo Metadata about the token from the compiler.
     * @returns {boolean} True if the token is a 'LABEL' type, false otherwise.
     */
    canHandle(tokenText, tokenInfo) {
        return tokenInfo.tokenType === 'LABEL';
    }

    /**
     * Analyzes the label token to create a jump-target annotation.
     * It resolves the label name to its relative coordinates using the artifact,
     * then calculates the absolute world coordinates using the organism's initial position.
     *
     * @param {string} tokenText The text of the token (the label name).
     * @param {object} tokenInfo Metadata about the token.
     * @param {object} organismState The current state of the organism, containing the `initialPosition`.
     * @param {object} artifact The program artifact containing lookup maps.
     * @returns {object} An annotation object `{ annotationText, kind }`.
     * @throws {Error} If data required for calculation (e.g., `initialPosition`) is missing or invalid.
     */
    analyze(tokenText, tokenInfo, organismState, artifact) {
        const relativeCoords = AnnotationUtils.resolveNameToCoords(tokenText, artifact);

        if (!organismState || !organismState.initialPosition || !Array.isArray(organismState.initialPosition.components)) {
            throw new Error(`Cannot calculate absolute coordinates for "${tokenText}": organismState.initialPosition is missing or invalid.`);
        }

        const initialPos = organismState.initialPosition.components;

        if (relativeCoords.length !== initialPos.length) {
            throw new Error(`Coordinate dimension mismatch for "${tokenText}": relative [${relativeCoords}] vs initial [${initialPos}].`);
        }
        
        const absoluteCoords = relativeCoords.map((val, index) => val + initialPos[index]);
        
        return {
            annotationText: `[${absoluteCoords.join('|')}]`,
            kind: 'label-ref'
        };
    }
}
