const INSTRUCTION_CONSTANTS = {
    PR_BASE: 1000,
    FPR_BASE: 2000
};

/**
 * A utility class providing static helper functions specifically for the annotation subsystem.
 * It encapsulates logic for resolving artifact data (e.g., registers, labels) that is
 * required by multiple annotation handlers.
 */
class AnnotationUtils {
    /**
     * Resolves a register alias or name (e.g., "%COUNTER", "%PR0") to its canonical form
     * (e.g., "%PR0").
     *
     * @param {string} token - The token to resolve.
     * @param {object} artifact - The program artifact containing the register alias map.
     * @returns {string|null} The canonical register name (e.g., "%PR0") or null if not a valid alias.
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
     * @param {object} state - The organism's state object containing register arrays.
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

    /**
     * Resolves a label or procedure name to its world coordinates by searching the program artifact.
     *
     * @param {string} name - The name of the label or procedure to resolve.
     * @param {object} artifact - The program artifact containing the necessary lookup maps (`labelAddressToName`, `linearAddressToCoord`).
     * @returns {number[]} An array of coordinates (e.g., [x, y]).
     * @throws {Error} If the name cannot be resolved or if the artifact is invalid.
     */
    static resolveNameToCoords(name, artifact) {
        if (!name || !artifact || !Array.isArray(artifact.labelAddressToName) || !Array.isArray(artifact.linearAddressToCoord)) {
            throw new Error(`Invalid artifact provided for resolving name: "${name}"`);
        }
        
        const actualName = name.includes('.') ? name.substring(name.lastIndexOf('.') + 1) : name;

        const labelMapping = artifact.labelAddressToName.find(mapping => 
            mapping.labelName && mapping.labelName.toUpperCase() === actualName.toUpperCase()
        );

        if (labelMapping) {
            const numericAddress = labelMapping.linearAddress;
            
            const coordMapping = artifact.linearAddressToCoord.find(mapping => 
                mapping.linearAddress === numericAddress
            );

            if (coordMapping && coordMapping.coord && Array.isArray(coordMapping.coord.components)) {
                return coordMapping.coord.components;
            }
        }

        throw new Error(`Could not resolve coordinates for name: "${actualName}"`);
    }
}
