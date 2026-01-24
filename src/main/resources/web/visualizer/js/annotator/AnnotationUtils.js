export const INSTRUCTION_CONSTANTS = {
    PR_BASE: 1000,
    FPR_BASE: 2000
};

/**
 * A utility class providing static helper functions specifically for the annotation subsystem.
 * It encapsulates logic for resolving artifact data (e.g., registers, labels) that is
 * required by multiple annotation handlers.
 */
export class AnnotationUtils {
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
     * @returns {*} The value of the register.
     * @throws {Error} If canonicalName or state is invalid, or if the register is not found.
     */
    static getRegisterValue(canonicalName, state) {
        if (!canonicalName || typeof canonicalName !== 'string') {
            throw new Error(`getRegisterValue: canonicalName must be a non-empty string, got: ${canonicalName}`);
        }
        if (!state || typeof state !== 'object') {
            throw new Error(`getRegisterValue: state must be an object, got: ${state}`);
        }

        const upper = canonicalName.toUpperCase();

        if (upper.startsWith('%DR')) {
            const id = parseInt(upper.substring(3), 10);
            if (isNaN(id) || id < 0) {
                throw new Error(`getRegisterValue: invalid DR register ID in "${canonicalName}"`);
            }
            if (!state.dataRegisters || !Array.isArray(state.dataRegisters)) {
                throw new Error(`getRegisterValue: state.dataRegisters is missing or invalid`);
            }
            if (id >= state.dataRegisters.length) {
                throw new Error(`getRegisterValue: DR register ${id} not found (only ${state.dataRegisters.length} available)`);
            }
            return state.dataRegisters[id];
        }
        if (upper.startsWith('%PR')) {
            const id = parseInt(upper.substring(3), 10);
            if (isNaN(id) || id < 0) {
                throw new Error(`getRegisterValue: invalid PR register ID in "${canonicalName}"`);
            }
            if (!state.procedureRegisters || !Array.isArray(state.procedureRegisters)) {
                throw new Error(`getRegisterValue: state.procedureRegisters is missing or invalid`);
            }
            if (id >= state.procedureRegisters.length) {
                throw new Error(`getRegisterValue: PR register ${id} not found (only ${state.procedureRegisters.length} available)`);
            }
            return state.procedureRegisters[id];
        }
        if (upper.startsWith('%FPR')) {
            const id = parseInt(upper.substring(4), 10);
            if (isNaN(id) || id < 0) {
                throw new Error(`getRegisterValue: invalid FPR register ID in "${canonicalName}"`);
            }
            if (!state.formalParamRegisters || !Array.isArray(state.formalParamRegisters)) {
                throw new Error(`getRegisterValue: state.formalParamRegisters is missing or invalid`);
            }
            if (id >= state.formalParamRegisters.length) {
                throw new Error(`getRegisterValue: FPR register ${id} not found (only ${state.formalParamRegisters.length} available)`);
            }
            return state.formalParamRegisters[id];
        }
        if (upper.startsWith('%LR')) {
            const id = parseInt(upper.substring(3), 10);
            if (isNaN(id) || id < 0) {
                throw new Error(`getRegisterValue: invalid LR register ID in "${canonicalName}"`);
            }
            if (!state.locationRegisters || !Array.isArray(state.locationRegisters)) {
                throw new Error(`getRegisterValue: state.locationRegisters is missing or invalid`);
            }
            if (id >= state.locationRegisters.length) {
                throw new Error(`getRegisterValue: LR register ${id} not found (only ${state.locationRegisters.length} available)`);
            }
            return state.locationRegisters[id];
        }
        throw new Error(`getRegisterValue: invalid register format "${canonicalName}" (must start with %DR, %PR, %FPR, or %LR)`);
    }

    /**
     * Formats a register ID to its canonical display name (e.g., 2000 -> "%FPR0", 1005 -> "%PR5", 3 -> "%DR3").
     * This is a central utility for converting register IDs to their display format.
     * 
     * When an explicit registerType is provided, it takes precedence and can distinguish LR from DR.
     * Without registerType, the method uses ID-based heuristics (FPR >= 2000, PR >= 1000, DR < 1000).
     *
     * @param {number} registerId - The numeric register ID.
     * @param {string} [registerType] - Optional explicit register type ('FPR', 'PR', 'DR', 'LR'). If provided, takes precedence over ID-based heuristics.
     * @returns {string} The canonical register name (e.g., "%FPR0", "%PR5", "%DR3", "%LR2").
     * @throws {Error} If registerId is null, undefined, or not a number.
     */
    static formatRegisterName(registerId, registerType = null) {
        if (registerId === null || registerId === undefined) {
            throw new Error(`formatRegisterName: registerId must be a number, got: ${registerId}`);
        }
        if (typeof registerId !== 'number' || isNaN(registerId)) {
            throw new Error(`formatRegisterName: registerId must be a valid number, got: ${registerId}`);
        }
        
        // If explicit type is provided, use it (needed for LR which can't be distinguished from DR by ID alone)
        if (registerType) {
            switch (registerType.toUpperCase()) {
                case 'FPR':
                    return `%FPR${registerId - INSTRUCTION_CONSTANTS.FPR_BASE}`;
                case 'PR':
                    return `%PR${registerId - INSTRUCTION_CONSTANTS.PR_BASE}`;
                case 'LR':
                    return `%LR${registerId}`;
                case 'DR':
                default:
                    return `%DR${registerId}`;
            }
        }
        
        // ID-based heuristics (can't distinguish LR from DR)
        if (registerId >= INSTRUCTION_CONSTANTS.FPR_BASE) {
            return `%FPR${registerId - INSTRUCTION_CONSTANTS.FPR_BASE}`;
        }
        if (registerId >= INSTRUCTION_CONSTANTS.PR_BASE) {
            return `%PR${registerId - INSTRUCTION_CONSTANTS.PR_BASE}`;
        }
        return `%DR${registerId}`;
    }

    /**
     * Resolves a label hash value to its name using the program artifact.
     * Used for displaying human-readable label names in the instruction view.
     *
     * @param {number} hashValue - The 20-bit hash value of the label.
     * @param {object} artifact - The program artifact containing `labelValueToName` map.
     * @returns {string|null} The label name, or null if not found.
     */
    static resolveLabelHashToName(hashValue, artifact) {
        if (hashValue === null || hashValue === undefined) {
            return null;
        }
        if (!artifact || !artifact.labelValueToName) {
            return null;
        }
        // labelValueToName is a map from int32 hash to string name
        return artifact.labelValueToName[hashValue] || null;
    }

    /**
     * Resolves a label name to its hash value using the program artifact.
     * Used for displaying the hash value in the source view annotations.
     *
     * @param {string} name - The label or procedure name.
     * @param {object} artifact - The program artifact containing `labelNameToValue` map.
     * @returns {number|null} The 20-bit hash value, or null if not found.
     */
    static resolveLabelNameToHash(name, artifact) {
        if (!name || !artifact || !artifact.labelNameToValue) {
            return null;
        }
        const upperName = name.toUpperCase();
        // labelNameToValue is a map from string name to int32 hash
        return artifact.labelNameToValue[upperName] ?? null;
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

    /**
     * Resolves the binding chain through the call stack to find the actual register for a procedure parameter.
     * Starts with an FPR index (parameter index) and walks through FPR bindings in the call stack to find the final DR/PR register.
     * <p>
     * This method resolves bindings from the ProgramArtifact's callSiteBindings,
     * as fprBindings in the call stack frames are not populated at runtime (copy-in/copy-out is used instead).
     *
     * @param {number} paramIndex - The parameter index (0-based) in the procedure's parameter list.
     * @param {Array} callStack - The call stack frames (array of ProcFrame objects).
     * @param {object} artifact - The ProgramArtifact containing callSiteBindings.
     * @param {object} organismState - The organism state containing initialPosition.
     * @returns {number} The final register ID (DR/PR/FPR).
     * @throws {Error} If paramIndex, callStack, artifact, or organismState is invalid, or if callStack is empty.
     */
    static resolveBindingChain(paramIndex, callStack, artifact, organismState) {
        if (paramIndex === null || paramIndex === undefined || typeof paramIndex !== 'number' || isNaN(paramIndex)) {
            throw new Error(`resolveBindingChain: paramIndex must be a valid number, got: ${paramIndex}`);
        }
        if (paramIndex < 0) {
            throw new Error(`resolveBindingChain: paramIndex must be non-negative, got: ${paramIndex}`);
        }
        if (!callStack || !Array.isArray(callStack)) {
            throw new Error(`resolveBindingChain: callStack must be an array, got: ${callStack}`);
        }
        if (callStack.length === 0) {
            throw new Error(`resolveBindingChain: callStack is empty (cannot resolve binding chain without call stack frames)`);
        }
        if (!artifact || typeof artifact !== 'object') {
            throw new Error(`resolveBindingChain: artifact must be an object, got: ${artifact}`);
        }
        if (!organismState || typeof organismState !== 'object') {
            throw new Error(`resolveBindingChain: organismState must be an object, got: ${organismState}`);
        }

        // Start with FPR index (parameter maps to FPR at FPR_BASE + paramIndex)
        let currentRegId = INSTRUCTION_CONSTANTS.FPR_BASE + paramIndex;

        // Get initialPosition for coordinate conversion
        let initialPosition = null;
        if (organismState.initialPosition && organismState.initialPosition.components && Array.isArray(organismState.initialPosition.components)) {
            initialPosition = organismState.initialPosition.components;
        }

        // Iterate through call stack frames to resolve the binding chain
        for (const frame of callStack) {
            if (!frame) {
                continue;
            }

            // Get bindings from artifact for this frame's CALL instruction
            let frameBindings = null;
            if (frame.absoluteCallIp && Array.isArray(frame.absoluteCallIp) && initialPosition && artifact.relativeCoordToLinearAddress && artifact.callSiteBindings) {
                // Calculate relative coordinates from absolute coordinates
                const relativeCoord = [];
                for (let i = 0; i < frame.absoluteCallIp.length && i < initialPosition.length; i++) {
                    relativeCoord.push(frame.absoluteCallIp[i] - initialPosition[i]);
                }

                // Convert relative coord to string key (e.g., "10|20")
                const coordKey = relativeCoord.join('|');

                // Look up linear address for this coordinate
                const linearAddress = artifact.relativeCoordToLinearAddress[coordKey];
                if (linearAddress !== null && linearAddress !== undefined) {
                    // Look up bindings from callSiteBindings
                    if (Array.isArray(artifact.callSiteBindings)) {
                        const binding = artifact.callSiteBindings.find(csb => csb.linearAddress === linearAddress);
                        if (binding && binding.registerIds && Array.isArray(binding.registerIds)) {
                            // Build fprBindings map: FPR index -> register ID
                            frameBindings = {};
                            for (let i = 0; i < binding.registerIds.length; i++) {
                                const registerId = binding.registerIds[i];
                                const fprId = INSTRUCTION_CONSTANTS.FPR_BASE + i;
                                frameBindings[fprId] = registerId;
                            }
                        }
                    }
                }
            }

            // If no bindings from artifact, check runtime fprBindings (fallback, usually empty)
            if (!frameBindings && frame.fprBindings && typeof frame.fprBindings === 'object') {
                frameBindings = frame.fprBindings;
            }

            // Check if current FPR is bound to another register in this frame
            if (frameBindings) {
                const mappedId = frameBindings[currentRegId];
                if (mappedId !== null && mappedId !== undefined) {
                    const parsedId = typeof mappedId === 'number' ? mappedId : parseInt(mappedId);
                    if (isNaN(parsedId)) {
                        throw new Error(`resolveBindingChain: invalid mapped register ID in bindings: ${mappedId}`);
                    }
                    currentRegId = parsedId;
                    
                    // If we've reached a DR or PR register (below FPR_BASE), we're done
                    if (currentRegId < INSTRUCTION_CONSTANTS.FPR_BASE) {
                        return currentRegId;
                    }
                    // Otherwise, continue with the new FPR ID
                } else {
                    // End of chain - no more bindings for this register
                    break;
                }
            } else {
                // No bindings available for this frame - cannot continue resolving
                break;
            }
        }

        // Return the final register ID (could be FPR if chain didn't resolve completely)
        return currentRegId;
    }

    /**
     * Resolves the binding chain through the call stack and returns the complete path.
     * Similar to resolveBindingChain, but returns an array of register IDs representing
     * the complete chain from the source DR/PR register to the parameter's FPR.
     * <p>
     * The path is returned in display order: from source to target.
     * For example, if parameter 0 is bound to FPR0, which is bound to FPR1 in the first frame,
     * which is bound to DR0 in the second frame, this returns [0, 2001, 2000] (DR0 -> FPR1 -> FPR0).
     * The last element is the current FPR that holds the parameter value.
     *
     * @param {number} paramIndex - The parameter index (0-based) in the procedure's parameter list.
     * @param {Array} callStack - The call stack frames (array of ProcFrame objects).
     * @param {object} artifact - The ProgramArtifact containing callSiteBindings.
     * @param {object} organismState - The organism state containing initialPosition.
     * @returns {Array<number>} An array of register IDs representing the binding chain path in display order (source to target).
     * @throws {Error} If paramIndex, callStack, artifact, or organismState is invalid, or if callStack is empty.
     */
    static resolveBindingChainWithPath(paramIndex, callStack, artifact, organismState) {
        if (paramIndex === null || paramIndex === undefined || typeof paramIndex !== 'number' || isNaN(paramIndex)) {
            throw new Error(`resolveBindingChainWithPath: paramIndex must be a valid number, got: ${paramIndex}`);
        }
        if (paramIndex < 0) {
            throw new Error(`resolveBindingChainWithPath: paramIndex must be non-negative, got: ${paramIndex}`);
        }
        if (!callStack || !Array.isArray(callStack)) {
            throw new Error(`resolveBindingChainWithPath: callStack must be an array, got: ${callStack}`);
        }
        if (callStack.length === 0) {
            throw new Error(`resolveBindingChainWithPath: callStack is empty (cannot resolve binding chain without call stack frames)`);
        }
        if (!artifact || typeof artifact !== 'object') {
            throw new Error(`resolveBindingChainWithPath: artifact must be an object, got: ${artifact}`);
        }
        if (!organismState || typeof organismState !== 'object') {
            throw new Error(`resolveBindingChainWithPath: organismState must be an object, got: ${organismState}`);
        }

        // Start with FPR index (parameter maps to FPR at FPR_BASE + paramIndex)
        let currentRegId = INSTRUCTION_CONSTANTS.FPR_BASE + paramIndex;
        const path = [currentRegId]; // Start with the parameter's FPR

        // Get initialPosition for coordinate conversion
        let initialPosition = null;
        if (organismState.initialPosition && organismState.initialPosition.components && Array.isArray(organismState.initialPosition.components)) {
            initialPosition = organismState.initialPosition.components;
        }

        // Iterate through call stack frames to resolve the binding chain
        for (const frame of callStack) {
            if (!frame) {
                continue;
            }

            // Get bindings from artifact for this frame's CALL instruction
            let frameBindings = null;
            if (frame.absoluteCallIp && Array.isArray(frame.absoluteCallIp) && initialPosition && artifact.relativeCoordToLinearAddress && artifact.callSiteBindings) {
                // Calculate relative coordinates from absolute coordinates
                const relativeCoord = [];
                for (let i = 0; i < frame.absoluteCallIp.length && i < initialPosition.length; i++) {
                    relativeCoord.push(frame.absoluteCallIp[i] - initialPosition[i]);
                }

                // Convert relative coord to string key (e.g., "10|20")
                const coordKey = relativeCoord.join('|');

                // Look up linear address for this coordinate
                const linearAddress = artifact.relativeCoordToLinearAddress[coordKey];
                if (linearAddress !== null && linearAddress !== undefined) {
                    // Look up bindings from callSiteBindings
                    if (Array.isArray(artifact.callSiteBindings)) {
                        const binding = artifact.callSiteBindings.find(csb => csb.linearAddress === linearAddress);
                        if (binding && binding.registerIds && Array.isArray(binding.registerIds)) {
                            // Build fprBindings map: FPR index -> register ID
                            frameBindings = {};
                            for (let i = 0; i < binding.registerIds.length; i++) {
                                const registerId = binding.registerIds[i];
                                const fprId = INSTRUCTION_CONSTANTS.FPR_BASE + i;
                                frameBindings[fprId] = registerId;
                            }
                        }
                    }
                }
            }

            // If no bindings from artifact, check runtime fprBindings (fallback, usually empty)
            if (!frameBindings && frame.fprBindings && typeof frame.fprBindings === 'object') {
                frameBindings = frame.fprBindings;
            }

            // Check if current FPR is bound to another register in this frame
            if (frameBindings) {
                const mappedId = frameBindings[currentRegId];
                if (mappedId !== null && mappedId !== undefined) {
                    const parsedId = typeof mappedId === 'number' ? mappedId : parseInt(mappedId);
                    if (isNaN(parsedId)) {
                        throw new Error(`resolveBindingChainWithPath: invalid mapped register ID in bindings: ${mappedId}`);
                    }
                    currentRegId = parsedId;
                    path.push(currentRegId); // Add to path
                    
                    // If we've reached a DR or PR register (below FPR_BASE), we're done
                    if (currentRegId < INSTRUCTION_CONSTANTS.FPR_BASE) {
                        // Reverse the path for display: show from source (DR/PR) to current (FPR)
                        return path.reverse();
                    }
                    // Otherwise, continue with the new FPR ID
                } else {
                    // End of chain - no more bindings for this register
                    break;
                }
            } else {
                // No bindings available for this frame - cannot continue resolving
                break;
            }
        }

        // Reverse the path for display: show from source (DR/PR) to current (FPR)
        // The path was built as [FPR0, FPR1, DR0] (parameter to source)
        // Return it as [DR0, FPR1, FPR0] (source to parameter) for display
        return path.reverse();
    }

    /**
     * Gets the register value by numeric register ID (instead of canonical name).
     * This is useful when working with register IDs directly from binding chains.
     *
     * @param {number} registerId - The numeric register ID.
     * @param {object} state - The organism's state object containing register arrays.
     * @returns {*} The value of the register.
     * @throws {Error} If registerId or state is invalid, or if the register is not found.
     */
    static getRegisterValueById(registerId, state) {
        if (registerId === null || registerId === undefined || typeof registerId !== 'number' || isNaN(registerId)) {
            throw new Error(`getRegisterValueById: registerId must be a valid number, got: ${registerId}`);
        }
        if (!state || typeof state !== 'object') {
            throw new Error(`getRegisterValueById: state must be an object, got: ${state}`);
        }

        if (registerId >= INSTRUCTION_CONSTANTS.FPR_BASE) {
            const index = registerId - INSTRUCTION_CONSTANTS.FPR_BASE;
            if (!state.formalParamRegisters || !Array.isArray(state.formalParamRegisters)) {
                throw new Error(`getRegisterValueById: state.formalParamRegisters is missing or invalid`);
            }
            if (index < 0 || index >= state.formalParamRegisters.length) {
                throw new Error(`getRegisterValueById: FPR register ${index} not found (registerId: ${registerId}, only ${state.formalParamRegisters.length} available)`);
            }
            return state.formalParamRegisters[index];
        }
        
        if (registerId >= INSTRUCTION_CONSTANTS.PR_BASE) {
            const index = registerId - INSTRUCTION_CONSTANTS.PR_BASE;
            if (!state.procedureRegisters || !Array.isArray(state.procedureRegisters)) {
                throw new Error(`getRegisterValueById: state.procedureRegisters is missing or invalid`);
            }
            if (index < 0 || index >= state.procedureRegisters.length) {
                throw new Error(`getRegisterValueById: PR register ${index} not found (registerId: ${registerId}, only ${state.procedureRegisters.length} available)`);
            }
            return state.procedureRegisters[index];
        }
        
        if (registerId >= 0) {
            if (!state.dataRegisters || !Array.isArray(state.dataRegisters)) {
                throw new Error(`getRegisterValueById: state.dataRegisters is missing or invalid`);
            }
            if (registerId >= state.dataRegisters.length) {
                throw new Error(`getRegisterValueById: DR register ${registerId} not found (only ${state.dataRegisters.length} available)`);
            }
            return state.dataRegisters[registerId];
        }

        throw new Error(`getRegisterValueById: invalid register ID ${registerId} (must be non-negative)`);
    }
}
