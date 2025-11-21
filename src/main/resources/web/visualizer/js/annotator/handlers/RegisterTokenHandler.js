/**
 * Handles the annotation of register tokens and their aliases.
 * It identifies tokens that represent registers and annotates them with their
 * current runtime value from the organism's state.
 */
class RegisterTokenHandler {
    /**
     * Determines if this handler can process the given token.
     * It handles tokens identified as 'ALIAS' or 'VARIABLE' that start with '%'.
     *
     * @param {string} tokenText The text of the token.
     * @param {object} tokenInfo Metadata about the token from the compiler.
     * @returns {boolean} True if this handler can process the token, false otherwise.
     */
    canHandle(token, tokenInfo) {
        const type = tokenInfo.tokenType;
        return type === 'ALIAS' || (type === 'VARIABLE' && token.startsWith('%'));
    }

    /**
     * Analyzes the register token to create an annotation with its current value.
     * It resolves aliases to canonical names and fetches the value from the organism state.
     *
     * @param {string} tokenText The text of the token (e.g., "%DR0", "%COUNTER").
     * @param {object} tokenInfo Metadata about the token.
     * @param {object} state The current state of the organism.
     * @param {object} artifact The program artifact.
     * @returns {object} An annotation object `{ annotationText, kind }`.
     * @throws {Error} If the token is not a register token, or if the register value cannot be found.
     */
    analyze(token, tokenInfo, state, artifact) {
        if (!token || !token.startsWith('%')) {
            throw new Error(`Cannot annotate register token "${token}": token does not start with '%' (expected register token).`);
        }

        const canonicalReg = AnnotationUtils.resolveToCanonicalRegister(token, artifact);
        const lookupName = canonicalReg || token;
        
        // getRegisterValue now throws Error directly if register not found or invalid input
        const value = AnnotationUtils.getRegisterValue(lookupName, state);
        const formattedValue = ValueFormatter.format(value);

        if (canonicalReg) {
            return {
                annotationText: `[${canonicalReg}=${formattedValue}]`,
                kind: 'reg'
            };
        } else {
            return {
                annotationText: `[=${formattedValue}]`,
                kind: 'reg'
            };
        }
    }
}
