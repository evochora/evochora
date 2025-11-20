const INSTRUCTION_CONSTANTS = {
    PR_BASE: 1000,
    FPR_BASE: 2000
};

/**
 * A utility class providing helper functions for resolving and reading organism registers.
 */
class RegisterUtils {
    /**
     * Resolves a register alias or name (e.g., "%COUNTER", "%PR0") to its canonical form.
     * @param {string} token - The token to resolve.
     * @param {object} artifact - The program artifact containing the register alias map.
     * @returns {string|null} The canonical register name (e.g., "%PR0") or null if not found.
     */
    static resolveToCanonicalRegister(token, artifact) {
        const upper = token.toUpperCase();
        if (artifact.registerAliasMap && artifact.registerAliasMap[upper] !== undefined) {
            const regId = artifact.registerAliasMap[upper];
            if (regId >= INSTRUCTION_CONSTANTS.FPR_BASE) return `%FPR${regId - INSTRUCTION_CONSTANTS.FPR_BASE}`;
            if (regId >= INSTRUCTION_CONSTANTS.PR_BASE) return `%PR${regId - INSTRUCTION_CONSTANTS.PR_BASE}`;
            return `%DR${regId}`;
        }
        return null;
    }

    /**
     * Retrieves the value of a register from the organism's state.
     * @param {string} canonicalName - The canonical name of the register (e.g., "%DR0").
     * @param {object} state - The organism's state object.
     * @returns {*} The value of the register, or null if not found.
     */
    static getRegisterValue(canonicalName, state) {
        if (!canonicalName || !state) return null;
        const upper = canonicalName.toUpperCase();

        if (upper.startsWith('%DR')) {
            const id = parseInt(upper.substring(3), 10);
            return (state.dataRegisters && id < state.dataRegisters.length) ? state.dataRegisters[id] : null;
        }
        if (upper.startsWith('%PR')) {
            const id = parseInt(upper.substring(3), 10);
            return (state.procedureRegisters && id < state.procedureRegisters.length) ? state.procedureRegisters[id] : null;
        }
        if (upper.startsWith('%FPR')) {
            const id = parseInt(upper.substring(4), 10);
            return (state.formalParamRegisters && id < state.formalParamRegisters.length) ? state.formalParamRegisters[id] : null;
        }
        if (upper.startsWith('%LR')) {
            const id = parseInt(upper.substring(3), 10);
            return (state.locationRegisters && id < state.locationRegisters.length) ? state.locationRegisters[id] : null;
        }
        return null;
    }
}
