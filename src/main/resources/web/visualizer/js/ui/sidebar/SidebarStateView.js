/**
 * Renders the dynamic runtime state of an organism in the sidebar.
 * This includes registers (DR, PR, FPR, LR) and stacks (Data, Location, Call).
 * It highlights changes between ticks to make debugging easier.
 *
 * @class SidebarStateView
 */
class SidebarStateView {
    /**
     * Initializes the view.
     * @param {HTMLElement} root - The root element of the sidebar.
     */
    constructor(root) {
        this.root = root;
        this.previousState = null;
        this.artifact = null;
    }

    /**
     * Sets the static program context (the ProgramArtifact).
     * This allows the view to resolve fprBindings from the artifact for debugging purposes.
     *
     * @param {object} artifact - The ProgramArtifact containing call site bindings and coordinate mappings.
     */
    setProgram(artifact) {
        this.artifact = artifact;
    }

    /**
     * Updates the state view with the latest organism runtime data.
     * It performs a diff against the previous state (if provided) to highlight
     * changed registers and stacks.
     *
     * @param {object} state - The full dynamic state of the organism.
     * @param {boolean} [isForwardStep=false] - True if navigating forward (e.g., tick N to N+1), enables change highlighting.
     * @param {object|null} [previousState=null] - The state from the previous tick, used for comparison.
     * @param {object|null} [staticInfo=null] - The static organism info containing initialPosition, needed for resolving bindings from artifact.
     */
    update(state, isForwardStep = false, previousState = null, staticInfo = null) {
        const el = this.root.querySelector('[data-section="state"]');
        if (!state || !el) return;

        /**
         * @private
         * Helper to format a list of registers using ValueFormatter with granular error handling.
         */
        const formatRegisters = (registers, removeBrackets = false, previousRegisters = null) => {
            if (!registers || registers.length === 0) return '';

            return registers.map((reg, i) => {
                const errorHtml = '<span class="formatting-error">ERR&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;</span>';
                let currentValue;

                try {
                    currentValue = ValueFormatter.format(reg);
                } catch (error) {
                    console.error(`ValueFormatter failed for register at index ${i}:`, error.message, 'Value:', reg);
                    return errorHtml;
                }
                
                let finalValue = removeBrackets ? currentValue.replace(/^\[|\]$/g, '') : currentValue;
                
                let isChanged = false;
                if (isForwardStep && previousRegisters && previousRegisters[i]) {
                    let previousValue;
                    try {
                        previousValue = ValueFormatter.format(previousRegisters[i]);
                    } catch (e) {
                        isChanged = true; // If previous was invalid, consider it changed.
                    }

                    if (!isChanged) {
                        const finalPreviousValue = removeBrackets ? previousValue.replace(/^\[|\]$/g, '') : previousValue;
                        if (finalValue !== finalPreviousValue) {
                            isChanged = true;
                        }
                    }
                }
                
                const paddedValue = String(finalValue).padEnd(8);
                return isChanged ? `<span class="changed-field">${paddedValue}</span>` : paddedValue;
            }).join('');
        };

        /**
         * @private
         * Helper to format a stack display using ValueFormatter with granular error handling.
         */
        const formatStack = (stack, maxColumns = 8, removeBrackets = false) => {
            if (!stack || stack.length === 0) return '';

            const formattedStack = stack.map((item, i) => {
                try {
                    const formatted = ValueFormatter.format(item);
                    return removeBrackets ? formatted.replace(/^\[|\]$/g, '') : formatted;
                } catch (error) {
                    console.error(`ValueFormatter failed for stack item at index ${i}:`, error.message, 'Value:', item);
                    return '<span class="formatting-error">ERR</span>';
                }
            });

            // Limit to maxColumns values
            let displayStack = formattedStack;
            if (formattedStack.length > maxColumns) {
                displayStack = formattedStack.slice(0, maxColumns - 1);
                const remainingCount = formattedStack.length - (maxColumns - 1);
                displayStack.push(`(+${remainingCount})`);
            }

            // Format each value with fixed width (8 characters)
            const formattedValues = displayStack.map(value => {
                return String(value).padEnd(8);
            });

            return formattedValues.join('');
        };

        /**
         * @private
         * Helper for complex call stack formatting.
         * @param {Array} callStack - The call stack array.
         * @param {Object} currentState - The current organism state (for REF parameter values).
         */
        const formatCallStack = (callStack, currentState) => {
            if (!callStack || callStack.length === 0) return '';

            // Check if we have procedure names
            const hasProcNames = callStack.some(entry => entry.procName && entry.procName.trim() !== '');

            if (hasProcNames) {
                // With procedure names: one line per entry
                const formattedCallStack = callStack.map((entry, index) => {
                    let result = entry.procName || 'UNKNOWN';

                    // Add return coordinates: [x|y] with injected-value styling
                    if (entry.absoluteReturnIp && Array.isArray(entry.absoluteReturnIp)) {
                        try {
                            const formattedIp = ValueFormatter.format(entry.absoluteReturnIp);
                            result += ` <span class="injected-value">[${formattedIp}]</span>`;
                        } catch (error) {
                            console.error('ValueFormatter failed for absoluteReturnIp:', error.message, 'Value:', entry.absoluteReturnIp);
                            result += ' <span class="formatting-error">ERR</span>';
                        }
                    }

                    // Add parameters using REF/VAL syntax
                    // Get parameter info from artifact (contains type information)
                    let paramInfo = null;
                    if (this.artifact && this.artifact.procNameToParamNames) {
                        const procNameUpper = (entry.procName || '').toUpperCase();
                        const paramEntry = this.artifact.procNameToParamNames[procNameUpper];
                        if (paramEntry && paramEntry.params && Array.isArray(paramEntry.params)) {
                            paramInfo = paramEntry.params;
                        }
                    }
                    
                    // Fallback: Get fprBindings (for legacy WITH syntax or when artifact unavailable)
                    let fprBindings = entry.fprBindings;
                    if ((!fprBindings || Object.keys(fprBindings).length === 0) && this.artifact && staticInfo && entry.absoluteCallIp) {
                        // Try to resolve bindings from artifact at debug time using the CALL instruction address
                        fprBindings = resolveBindingsFromArtifact(entry.absoluteCallIp, staticInfo);
                    }
                    
                    // Format parameters using new REF/VAL syntax if parameter info available
                    if (paramInfo && paramInfo.length > 0) {
                        const refParams = [];
                        const valParams = [];
                        const withParams = [];
                        
                        // Separate REF, VAL, and WITH parameters
                        // Handle both numeric enum values (0, 1, 2) and string enum names
                        for (let i = 0; i < paramInfo.length; i++) {
                            const param = paramInfo[i];
                            if (!param || !param.name) continue;
                            
                            const paramType = param.type;
                            let isRef = false;
                            let isVal = false;
                            let isWith = false;
                            
                            if (typeof paramType === 'number') {
                                // Numeric enum: 0=REF, 1=VAL, 2=WITH
                                isRef = paramType === 0;
                                isVal = paramType === 1;
                                isWith = paramType === 2;
                            } else if (typeof paramType === 'string') {
                                // String enum: "PARAM_TYPE_REF", "PARAM_TYPE_VAL", etc.
                                const typeUpper = paramType.toUpperCase();
                                isRef = typeUpper === 'REF' || typeUpper === 'PARAM_TYPE_REF';
                                isVal = typeUpper === 'VAL' || typeUpper === 'PARAM_TYPE_VAL';
                                isWith = typeUpper === 'WITH' || typeUpper === 'PARAM_TYPE_WITH';
                            }
                            // If type is missing, treat as REF (should not happen if Java code sets it explicitly)
                            else if (paramType === undefined || paramType === null) {
                                isRef = true;
                            }
                            
                            if (isRef) {
                                refParams.push({ index: i, name: param.name });
                            } else if (isVal) {
                                valParams.push({ index: i, name: param.name });
                            } else if (isWith) {
                                withParams.push({ index: i, name: param.name });
                            }
                        }
                        
                        // Format REF parameters (from current FPRs - consistent with VAL)
                        // REF parameters are call-by-reference, but we show the FPR register and its current value
                        // Note: Values are only available after PUSI/POP instructions have been executed
                        if (refParams.length > 0 && currentState && currentState.formalParamRegisters && Array.isArray(currentState.formalParamRegisters)) {
                            result += ' REF ';
                            const refStrings = [];
                            for (const refParam of refParams) {
                                // REF parameters are stored in FPRs after the call
                                // The index in paramInfo corresponds to the FPR index
                                if (refParam.index < currentState.formalParamRegisters.length) {
                                    try {
                                        const fprDisplay = AnnotationUtils.formatRegisterName(INSTRUCTION_CONSTANTS.FPR_BASE + refParam.index);
                                        const refValue = ValueFormatter.format(currentState.formalParamRegisters[refParam.index]);
                                        refStrings.push(`${refParam.name}<span class="injected-value">[${fprDisplay}=${refValue}]</span>`);
                                    } catch (error) {
                                        console.error('ValueFormatter failed for REF parameter:', error.message);
                                        refStrings.push(refParam.name);
                                    }
                                } else {
                                    refStrings.push(refParam.name);
                                }
                            }
                            result += refStrings.join(' ');
                        }
                        
                        // Format VAL parameters (from current FPRs - includes literals)
                        // VAL parameters are call-by-value, but we show the FPR register and its current value (consistent with REF/WITH)
                        // Note: Values are only available after PUSI/POP instructions have been executed
                        if (valParams.length > 0 && currentState && currentState.formalParamRegisters && Array.isArray(currentState.formalParamRegisters)) {
                            result += ' VAL ';
                            const valStrings = [];
                            for (const valParam of valParams) {
                                // VAL parameters are stored in FPRs after the call
                                // The index in paramInfo corresponds to the FPR index
                                if (valParam.index < currentState.formalParamRegisters.length) {
                                    try {
                                        const fprDisplay = AnnotationUtils.formatRegisterName(INSTRUCTION_CONSTANTS.FPR_BASE + valParam.index);
                                        const valValue = ValueFormatter.format(currentState.formalParamRegisters[valParam.index]);
                                        valStrings.push(`${valParam.name}<span class="injected-value">[${fprDisplay}=${valValue}]</span>`);
                                    } catch (error) {
                                        console.error('ValueFormatter failed for VAL parameter:', error.message);
                                        valStrings.push(valParam.name);
                                    }
                                } else {
                                    valStrings.push(valParam.name);
                                }
                            }
                            result += valStrings.join(' ');
                    }

                        // Format WITH parameters (legacy syntax - same as REF, from current FPRs)
                        // WITH parameters are call-by-reference (legacy), but we show the FPR register and its current value
                        // Note: Values are only available after PUSI/POP instructions have been executed
                        if (withParams.length > 0 && currentState && currentState.formalParamRegisters && Array.isArray(currentState.formalParamRegisters)) {
                            result += ' WITH ';
                            const withStrings = [];
                            for (const withParam of withParams) {
                                // WITH parameters are stored in FPRs after the call
                                // The index in paramInfo corresponds to the FPR index
                                if (withParam.index < currentState.formalParamRegisters.length) {
                                    try {
                                        const fprDisplay = AnnotationUtils.formatRegisterName(INSTRUCTION_CONSTANTS.FPR_BASE + withParam.index);
                                        const withValue = ValueFormatter.format(currentState.formalParamRegisters[withParam.index]);
                                        withStrings.push(`${withParam.name}<span class="injected-value">[${fprDisplay}=${withValue}]</span>`);
                                    } catch (error) {
                                        console.error('ValueFormatter failed for WITH parameter:', error.message);
                                        withStrings.push(withParam.name);
                                    }
                                } else {
                                    withStrings.push(withParam.name);
                                }
                            }
                            result += withStrings.join(' ');
                        }
                    } else if (fprBindings && Object.keys(fprBindings).length > 0) {
                        // Fallback: Legacy WITH syntax (when parameter info not available)
                        result += ' WITH ';
                        const paramStrings = [];
                        for (const [fprIdStr, boundRegisterId] of Object.entries(fprBindings)) {
                            const fprId = parseInt(fprIdStr);
                            const boundId = typeof boundRegisterId === 'number' ? boundRegisterId : parseInt(boundRegisterId);
                            
                            const registerDisplay = AnnotationUtils.formatRegisterName(boundId);
                            
                            // Get value from current organism state (WITH parameters point to registers)
                            let registerValue = 'N/A';
                            if (currentState) {
                                try {
                                    registerValue = ValueFormatter.format(AnnotationUtils.getRegisterValueById(boundId, currentState));
                                } catch (error) {
                                    console.error('ValueFormatter failed for WITH parameter bound register (fallback):', error.message);
                                    registerValue = 'ERR';
                                }
                            }
                            
                            const fprDisplay = AnnotationUtils.formatRegisterName(fprId);
                            paramStrings.push(`${fprDisplay}<span class="injected-value">[${registerDisplay}=${registerValue}]</span>`);
                        }
                        result += paramStrings.join(' ');
                    }

                    // Indentation: first line none, further lines: 5 spaces
                    if (index === 0) {
                        return result;
                    } else {
                        return '     ' + result;
                    }
                });

                return formattedCallStack.join('\n');
            } else {
                // Without procedure names: all entries in one line like other stacks
                const formattedEntries = callStack.map(entry => {
                    if (entry.absoluteReturnIp && Array.isArray(entry.absoluteReturnIp)) {
                        try {
                            return ValueFormatter.format(entry.absoluteReturnIp);
                        } catch (error) {
                            console.error('ValueFormatter failed for absoluteReturnIp:', error.message, 'Value:', entry.absoluteReturnIp);
                            return 'ERR';
                        }
                    }
                    return '';
                }).filter(entry => entry !== '');

                // Dynamic abbreviation like other stacks
                const maxColumns = 8;
                let displayEntries = formattedEntries;
                if (formattedEntries.length > maxColumns) {
                    displayEntries = formattedEntries.slice(0, maxColumns - 1);
                    const remainingCount = formattedEntries.length - (maxColumns - 1);
                    displayEntries.push(`(+${remainingCount})`);
                }

                // Distribute across columns
                return displayEntries.map(entry => String(entry).padEnd(8)).join('');
            }
        };

        /**
         * Resolves fprBindings from artifact for a given absolute call IP.
         * This is used at debug time to reconstruct parameter bindings that weren't
         * available at runtime due to self-modifying code.
         *
         * @param {number[]} absoluteCallIp - The absolute coordinates of the CALL instruction.
         * @param {object} staticInfo - The static organism info containing initialPosition.
         * @returns {object|null} A map of FPR IDs to bound register IDs, or null if not resolvable.
         * @private
         */
        const resolveBindingsFromArtifact = (absoluteCallIp, staticInfo) => {
            if (!this.artifact || !staticInfo || !absoluteCallIp || !Array.isArray(absoluteCallIp)) {
                return null;
            }

            const initialPosition = staticInfo.initialPosition;
            if (!initialPosition || !Array.isArray(initialPosition)) {
                return null;
            }

            // Calculate relative coordinates from absolute coordinates
            // absoluteCallIp is the absolute coord of the CALL instruction
            const relativeCoord = [];
            for (let i = 0; i < absoluteCallIp.length && i < initialPosition.length; i++) {
                relativeCoord.push(absoluteCallIp[i] - initialPosition[i]);
            }

            // Convert relative coord to string key (e.g., "10|20")
            const coordKey = relativeCoord.join('|');

            // Look up linear address for this coordinate
            if (!this.artifact.relativeCoordToLinearAddress || !this.artifact.relativeCoordToLinearAddress[coordKey]) {
                return null;
            }

            const linearAddress = this.artifact.relativeCoordToLinearAddress[coordKey];

            // Look up bindings from callSiteBindings
            if (!this.artifact.callSiteBindings || !Array.isArray(this.artifact.callSiteBindings)) {
                return null;
            }

            // Find the callSiteBinding with matching linearAddress
            const binding = this.artifact.callSiteBindings.find(csb => csb.linearAddress === linearAddress);
            if (!binding || !binding.registerIds || !Array.isArray(binding.registerIds)) {
                return null;
            }

            // Build fprBindings map: FPR index -> register ID
            // registerIds array: [drId0, drId1, ...] maps to FPR0, FPR1, ...
            const fprBindings = {};
            for (let i = 0; i < binding.registerIds.length; i++) {
                const registerId = binding.registerIds[i];
                const fprId = INSTRUCTION_CONSTANTS.FPR_BASE + i;
                fprBindings[fprId] = registerId;
            }

            return fprBindings;
        };

        // Check for stack changes (only on forward step)
        const dataStackChanged = isForwardStep && previousState &&
            (JSON.stringify(state.dataStack) !== JSON.stringify(previousState.dataStack));
        const locationStackChanged = isForwardStep && previousState &&
            (JSON.stringify(state.locationStack) !== JSON.stringify(previousState.locationStack));
        const callStackChanged = isForwardStep && previousState &&
            (JSON.stringify(state.callStack) !== JSON.stringify(previousState.callStack));
        
        // Format state view (IP, DV, ER, DPs are shown in BasicInfoView changeable-box, not here)
        const stateLines = `DR:  ${formatRegisters(state.dataRegisters, true, previousState?.dataRegisters)}\nPR:  ${formatRegisters(state.procedureRegisters, true, previousState?.procedureRegisters)}\nFPR: ${formatRegisters(state.formalParamRegisters, true, previousState?.formalParamRegisters)}\nLR:  ${formatRegisters(state.locationRegisters, true, previousState?.locationRegisters)}\n${dataStackChanged ? '<div class="changed-line">DS:  ' : 'DS:  '}${formatStack(state.dataStack, state.dataRegisters?.length || 8, true)}${dataStackChanged ? '</div>' : ''}\n${locationStackChanged ? '<div class="changed-line">LS:  ' : 'LS:  '}${formatStack(state.locationStack, state.dataRegisters?.length || 8, true)}${locationStackChanged ? '</div>' : ''}\n${callStackChanged ? '<div class="changed-line">CS:  ' : 'CS:  '}${formatCallStack(state.callStack, state)}${callStackChanged ? '</div>' : ''}`;

        el.innerHTML = `<div class="code-view" style="font-size:0.9em;">${stateLines}</div>`;

        // Save current state for next comparison
        this.previousState = { ...state };
    }
}

// Export for global availability
window.SidebarStateView = SidebarStateView;

