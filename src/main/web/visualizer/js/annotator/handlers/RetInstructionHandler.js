import { ValueFormatter } from '../../utils/ValueFormatter.js';

/**
 * Handles the annotation of RET instruction tokens.
 * Shows the return address coordinates from the call stack.
 * 
 * When a RET instruction is executed, it returns to the calling procedure.
 * The return address is stored in the topmost call stack frame.
 */
export class RetInstructionHandler {
    /**
     * Determines if this handler can process the given token.
     * It specifically handles "RET" instruction tokens (case-insensitive).
     *
     * @param {string} tokenText The text of the token.
     * @param {object} tokenInfo Metadata about the token from the compiler.
     * @returns {boolean} True if the token is "RET" (case-insensitive), false otherwise.
     */
    canHandle(tokenText, tokenInfo) {
        return tokenText.toUpperCase() === 'RET';
    }

    /**
     * Analyzes the RET instruction token to create a return address annotation.
     * Extracts the return address from the topmost call stack frame.
     *
     * @param {string} tokenText The text of the token (should be "RET").
     * @param {object} tokenInfo Metadata about the token.
     * @param {object} organismState The current state of the organism, containing the call stack.
     * @param {object} artifact The program artifact (not used here).
     * @returns {object} An annotation object `{ annotationText, kind }`.
     * @throws {Error} If required data (call stack, return address) is missing or invalid.
     */
    analyze(tokenText, tokenInfo, organismState, artifact) {
        if (!organismState || !organismState.callStack || !Array.isArray(organismState.callStack)) {
            throw new Error(`Cannot annotate RET instruction: organismState.callStack is missing or invalid.`);
        }

        const callStack = organismState.callStack;

        if (callStack.length === 0) {
            throw new Error(`Cannot annotate RET instruction: call stack is empty (no return address available).`);
        }

        // The topmost frame (index 0) contains the return address for the current procedure
        const topFrame = callStack[0];
        
        if (!topFrame) {
            throw new Error(`Cannot annotate RET instruction: topmost call stack frame is null or undefined.`);
        }

        if (!topFrame.absoluteReturnIp || !Array.isArray(topFrame.absoluteReturnIp)) {
            throw new Error(`Cannot annotate RET instruction: topmost frame.absoluteReturnIp is missing or invalid (frame: ${JSON.stringify(topFrame)}).`);
        }

        // Format return address as coordinates using ValueFormatter (e.g., "15|3")
        const annotationText = ValueFormatter.format(topFrame.absoluteReturnIp);
        
        return {
            annotationText: `[${annotationText}]`,
            kind: 'ret'
        };
    }
}

