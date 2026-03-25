/**
 * Single source of truth for all register bank metadata on the JS side.
 * Architecturally identical to Java's RegisterBank enum.
 * Adding a new bank requires only a new entry here.
 */
export const REGISTER_BANKS = [
    { name: "DR",  prefix: "%DR",  base: 0,    slotOffset: 0,  count: 8, isLocation: false },
    { name: "LR",  prefix: "%LR",  base: 256,  slotOffset: 8,  count: 4, isLocation: true  },
    { name: "PDR", prefix: "%PDR", base: 512,  slotOffset: 12, count: 8, isLocation: false },
    { name: "PLR", prefix: "%PLR", base: 768,  slotOffset: 20, count: 4, isLocation: true  },
    { name: "FDR", prefix: "%FDR", base: 1024, slotOffset: 24, count: 8, isLocation: false },
    { name: "FLR", prefix: "%FLR", base: 1280, slotOffset: 32, count: 4, isLocation: true  },
    { name: "SDR", prefix: "%SDR", base: 1536, slotOffset: 36, count: 8, isLocation: false },
    { name: "SLR", prefix: "%SLR", base: 1792, slotOffset: 44, count: 4, isLocation: true  },
];

/** O(1) name-based lookup into REGISTER_BANKS. */
export const BANK_BY_NAME = Object.fromEntries(REGISTER_BANKS.map(b => [b.name, b]));

/**
 * A utility class providing static helper functions specifically for the annotation subsystem.
 * It encapsulates logic for resolving artifact data (e.g., registers, labels) that is
 * required by multiple annotation handlers.
 */
export class AnnotationUtils {
    /**
     * Resolves a register alias or name (e.g., "%COUNTER", "%PDR0") to its canonical form
     * (e.g., "%PDR0"). When a qualifiedName is provided, it takes precedence for the lookup
     * since backend keys are module-qualified (e.g., "ENERGY.COUNTER").
     *
     * @param {string} token - The token to resolve.
     * @param {object} artifact - The program artifact containing the register alias map.
     * @param {string} [qualifiedName] - The canonical module-qualified alias name for lookup.
     * @returns {string|null} The canonical register name (e.g., "%PDR0") or null if not a valid alias.
     */
    static resolveToCanonicalRegister(token, artifact, qualifiedName) {
        const lookupKey = (qualifiedName || token || '').toUpperCase();
        if (artifact.registerAliasMap && artifact.registerAliasMap[lookupKey] !== undefined) {
            const regId = artifact.registerAliasMap[lookupKey];
            return AnnotationUtils.formatRegisterName(regId);
        }
        return null;
    }

    /**
     * Retrieves the value of a register from the organism's state.
     * @param {string} canonicalName - The canonical name of the register (e.g., "%DR0").
     * @param {object} state - The organism's state object containing a flat registers array.
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
        if (!state.registers || !Array.isArray(state.registers)) {
            throw new Error(`getRegisterValue: state.registers is missing or invalid`);
        }

        const upper = canonicalName.toUpperCase();

        for (const bank of REGISTER_BANKS) {
            if (upper.startsWith(bank.prefix)) {
                const id = parseInt(upper.substring(bank.prefix.length), 10);
                if (isNaN(id) || id < 0) {
                    throw new Error(`getRegisterValue: invalid ${bank.name} register index in "${canonicalName}"`);
                }
                if (id >= bank.count) {
                    throw new Error(`getRegisterValue: ${bank.name} register ${id} not found (only ${bank.count} available)`);
                }
                const slot = bank.slotOffset + id;
                if (slot >= state.registers.length) {
                    throw new Error(`getRegisterValue: slot ${slot} exceeds registers array length (${state.registers.length})`);
                }
                return state.registers[slot];
            }
        }
        const validPrefixes = REGISTER_BANKS.map(b => b.prefix).join(', ');
        throw new Error(`getRegisterValue: invalid register format "${canonicalName}" (must start with ${validPrefixes})`);
    }

    /**
     * Formats a register ID to its canonical display name.
     * When an explicit registerType is provided, it takes precedence.
     * Without registerType, the method uses ID-based heuristics via REGISTER_BANKS.
     *
     * @param {number} registerId - The numeric register ID.
     * @param {string} [registerType] - Optional explicit register type ('FDR', 'PDR', 'DR', 'LR').
     * @returns {string} The canonical register name (e.g., "%FDR0", "%PDR5", "%DR3", "%LR2").
     * @throws {Error} If registerId is null, undefined, or not a number.
     */
    static formatRegisterName(registerId, registerType = null) {
        if (registerId === null || registerId === undefined) {
            throw new Error(`formatRegisterName: registerId must be a number, got: ${registerId}`);
        }
        if (typeof registerId !== 'number' || isNaN(registerId)) {
            throw new Error(`formatRegisterName: registerId must be a valid number, got: ${registerId}`);
        }

        // If explicit type is provided, use BANK_BY_NAME lookup
        if (registerType) {
            const bank = BANK_BY_NAME[registerType.toUpperCase()];
            if (bank) {
                return `${bank.prefix}${registerId - bank.base}`;
            }
            return `%DR${registerId}`;
        }

        // ID-based heuristics: descending by base
        for (let i = REGISTER_BANKS.length - 1; i >= 0; i--) {
            const bank = REGISTER_BANKS[i];
            if (registerId >= bank.base) {
                return `${bank.prefix}${registerId - bank.base}`;
            }
        }
        return `%DR${registerId}`;
    }

    /**
     * Resolves a label hash value to its name using the program artifact.
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
        return artifact.labelValueToName[hashValue] || null;
    }

    /**
     * Resolves a label name to its hash value using the program artifact.
     *
     * @param {string} name - The label or procedure name as it appears in source.
     * @param {object} artifact - The program artifact containing `labelNameToValue` map.
     * @param {string} [qualifiedName] - The canonical module-qualified name for lookup.
     * @returns {number|null} The 20-bit hash value, or null if not found.
     */
    static resolveLabelNameToHash(name, artifact, qualifiedName) {
        if (!artifact || !artifact.labelNameToValue) {
            return null;
        }
        const lookupKey = (qualifiedName || name || '').toUpperCase();
        if (!lookupKey) return null;
        return artifact.labelNameToValue[lookupKey] ?? null;
    }

    /**
     * Resolves the binding chain through the call stack to find the actual register for a procedure parameter.
     *
     * @param {number} paramIndex - The parameter index (0-based) in the procedure's parameter list.
     * @param {Array} callStack - The call stack frames (array of ProcFrame objects).
     * @param {object} artifact - The ProgramArtifact containing callSiteBindings.
     * @param {object} organismState - The organism state containing initialPosition.
     * @returns {number} The final register ID (DR/PDR/FDR).
     * @throws {Error} If paramIndex, callStack, artifact, or organismState is invalid, or if callStack is empty.
     */
    static resolveBindingChain(paramIndex, callStack, artifact, organismState, isLocation = false) {
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

        const paramBank = isLocation ? BANK_BY_NAME.FLR : BANK_BY_NAME.FDR;
        let currentRegId = paramBank.base + paramIndex;

        let initialPosition = null;
        if (organismState.initialPosition && organismState.initialPosition.components && Array.isArray(organismState.initialPosition.components)) {
            initialPosition = organismState.initialPosition.components;
        }

        for (const frame of callStack) {
            if (!frame) continue;

            let frameBindings = _resolveFrameBindings(frame, initialPosition, artifact);

            // If no bindings from artifact, check runtime parameterBindings (fallback)
            if (!frameBindings && frame.parameterBindings && typeof frame.parameterBindings === 'object') {
                frameBindings = frame.parameterBindings;
            }

            if (frameBindings) {
                const mappedId = frameBindings[currentRegId];
                if (mappedId !== null && mappedId !== undefined) {
                    const parsedId = typeof mappedId === 'number' ? mappedId : parseInt(mappedId);
                    if (isNaN(parsedId)) {
                        throw new Error(`resolveBindingChain: invalid mapped register ID in bindings: ${mappedId}`);
                    }
                    currentRegId = parsedId;
                    if (currentRegId < paramBank.base) {
                        return currentRegId;
                    }
                } else {
                    break;
                }
            } else {
                break;
            }
        }

        return currentRegId;
    }

    /**
     * Resolves the binding chain through the call stack and returns the complete path.
     *
     * @param {number} paramIndex - The parameter index (0-based).
     * @param {Array} callStack - The call stack frames.
     * @param {object} artifact - The ProgramArtifact containing callSiteBindings.
     * @param {object} organismState - The organism state containing initialPosition.
     * @returns {Array<number>} Register IDs in display order (source to target).
     */
    static resolveBindingChainWithPath(paramIndex, callStack, artifact, organismState, isLocation = false) {
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

        const paramBank = isLocation ? BANK_BY_NAME.FLR : BANK_BY_NAME.FDR;
        let currentRegId = paramBank.base + paramIndex;
        const path = [currentRegId];

        let initialPosition = null;
        if (organismState.initialPosition && organismState.initialPosition.components && Array.isArray(organismState.initialPosition.components)) {
            initialPosition = organismState.initialPosition.components;
        }

        for (const frame of callStack) {
            if (!frame) continue;

            let frameBindings = _resolveFrameBindings(frame, initialPosition, artifact);

            if (!frameBindings && frame.parameterBindings && typeof frame.parameterBindings === 'object') {
                frameBindings = frame.parameterBindings;
            }

            if (frameBindings) {
                const mappedId = frameBindings[currentRegId];
                if (mappedId !== null && mappedId !== undefined) {
                    const parsedId = typeof mappedId === 'number' ? mappedId : parseInt(mappedId);
                    if (isNaN(parsedId)) {
                        throw new Error(`resolveBindingChainWithPath: invalid mapped register ID in bindings: ${mappedId}`);
                    }
                    currentRegId = parsedId;
                    path.push(currentRegId);
                    if (currentRegId < paramBank.base) {
                        return path.reverse();
                    }
                } else {
                    break;
                }
            } else {
                break;
            }
        }

        return path.reverse();
    }

    /**
     * Gets the register value by numeric register ID from the flat registers array.
     *
     * @param {number} registerId - The numeric register ID.
     * @param {object} state - The organism's state object containing a flat registers array.
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
        if (!state.registers || !Array.isArray(state.registers)) {
            throw new Error(`getRegisterValueById: state.registers is missing or invalid`);
        }

        // Find bank by descending base
        for (let i = REGISTER_BANKS.length - 1; i >= 0; i--) {
            const bank = REGISTER_BANKS[i];
            if (registerId >= bank.base) {
                const index = registerId - bank.base;
                if (index < 0 || index >= bank.count) {
                    throw new Error(`getRegisterValueById: ${bank.name} register ${index} not found (registerId: ${registerId}, only ${bank.count} available)`);
                }
                const slot = bank.slotOffset + index;
                if (slot >= state.registers.length) {
                    throw new Error(`getRegisterValueById: slot ${slot} exceeds registers array length (${state.registers.length})`);
                }
                return state.registers[slot];
            }
        }

        throw new Error(`getRegisterValueById: invalid register ID ${registerId} (must be non-negative)`);
    }
}

/**
 * Resolves frame bindings from artifact for a given call stack frame.
 * @private
 */
function _resolveFrameBindings(frame, initialPosition, artifact) {
    if (!frame.absoluteCallIp || !Array.isArray(frame.absoluteCallIp) || !initialPosition ||
        !artifact.relativeCoordToLinearAddress || !artifact.callSiteBindings) {
        return null;
    }

    const relativeCoord = [];
    for (let i = 0; i < frame.absoluteCallIp.length && i < initialPosition.length; i++) {
        relativeCoord.push(frame.absoluteCallIp[i] - initialPosition[i]);
    }

    const coordKey = relativeCoord.join('|');
    const linearAddress = artifact.relativeCoordToLinearAddress[coordKey];
    if (linearAddress === null || linearAddress === undefined) {
        return null;
    }

    if (!Array.isArray(artifact.callSiteBindings)) {
        return null;
    }

    const binding = artifact.callSiteBindings.find(csb => csb.linearAddress === linearAddress);
    if (!binding || !binding.registerIds || !Array.isArray(binding.registerIds)) {
        return null;
    }

    const bindings = {};
    for (let i = 0; i < binding.registerIds.length; i++) {
        const fdrId = BANK_BY_NAME.FDR.base + i;
        bindings[fdrId] = binding.registerIds[i];
    }
    return bindings;
}
