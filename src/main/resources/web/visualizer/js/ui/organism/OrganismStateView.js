import { ValueFormatter } from '../../utils/ValueFormatter.js';
import { AnnotationUtils, REGISTER_BANKS, BANK_BY_NAME } from '../../annotator/AnnotationUtils.js';

/**
 * Renders the dynamic runtime state of an organism in the organism panel.
 * This includes registers (DR, PDR, FDR, LR) and stacks (Data, Location, Call).
 * It highlights changes between ticks to make debugging easier.
 *
 * @class OrganismStateView
 */
export class OrganismStateView {
    /**
     * Initializes the view.
     * @param {HTMLElement} root - The root element of the organism panel.
     */
    constructor(root) {
        this.root = root;
        this.previousState = null;
        this.artifact = null;
    }

    /**
     * Sets the static program context (the ProgramArtifact).
     * This allows the view to resolve parameterBindings from the artifact for debugging purposes.
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

                const paddedValue = String(finalValue).padEnd(10);
                return isChanged ? `<span class="changed-field">${paddedValue}</span>` : paddedValue;
            }).join('');
        };

        /**
         * @private
         * Helper to extract a bank's registers from the flat registers array.
         */
        const getBankRegisters = (registers, bank) => {
            if (!registers || !Array.isArray(registers)) return [];
            return registers.slice(bank.slotOffset, bank.slotOffset + bank.count);
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
                return String(value).padEnd(10);
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
                    let paramInfo = null;
                    if (this.artifact && this.artifact.procNameToParamNames) {
                        const procNameUpper = (entry.procName || '').toUpperCase();
                        const paramEntry = this.artifact.procNameToParamNames[procNameUpper];
                        if (paramEntry && paramEntry.params && Array.isArray(paramEntry.params)) {
                            paramInfo = paramEntry.params;
                        }
                    }

                    // Resolve parameter bindings
                    let parameterBindings = entry.parameterBindings;
                    if ((!parameterBindings || Object.keys(parameterBindings).length === 0) && this.artifact && staticInfo && entry.absoluteCallIp) {
                        parameterBindings = resolveBindingsFromArtifact(entry.absoluteCallIp, staticInfo);
                    }

                    // FDR bank info for parameter display
                    const fdrBank = BANK_BY_NAME.FDR;

                    // Format parameters using REF/VAL syntax if parameter info available
                    if (paramInfo && paramInfo.length > 0) {
                        const refParams = [];
                        const valParams = [];
                        const withParams = [];

                        for (let i = 0; i < paramInfo.length; i++) {
                            const param = paramInfo[i];
                            if (!param || !param.name) continue;

                            const paramType = param.type;
                            let isRef = false;
                            let isVal = false;
                            let isWith = false;

                            if (typeof paramType === 'number') {
                                isRef = paramType === 0;
                                isVal = paramType === 1;
                                isWith = paramType === 2;
                            } else if (typeof paramType === 'string') {
                                const typeUpper = paramType.toUpperCase();
                                isRef = typeUpper === 'REF' || typeUpper === 'PARAM_TYPE_REF';
                                isVal = typeUpper === 'VAL' || typeUpper === 'PARAM_TYPE_VAL';
                                isWith = typeUpper === 'WITH' || typeUpper === 'PARAM_TYPE_WITH';
                            } else if (paramType === undefined || paramType === null) {
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

                        // Format REF parameters
                        if (refParams.length > 0 && currentState && currentState.registers && Array.isArray(currentState.registers)) {
                            result += ' REF ';
                            const refStrings = [];
                            for (const refParam of refParams) {
                                const fdrSlot = fdrBank.slotOffset + refParam.index;
                                if (fdrSlot < currentState.registers.length) {
                                    try {
                                        const fdrDisplay = AnnotationUtils.formatRegisterName(fdrBank.base + refParam.index);
                                        const refValue = ValueFormatter.format(currentState.registers[fdrSlot]);
                                        refStrings.push(`${refParam.name}<span class="injected-value">[${fdrDisplay}=${refValue}]</span>`);
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

                        // Format VAL parameters
                        if (valParams.length > 0 && currentState && currentState.registers && Array.isArray(currentState.registers)) {
                            result += ' VAL ';
                            const valStrings = [];
                            for (const valParam of valParams) {
                                const fdrSlot = fdrBank.slotOffset + valParam.index;
                                if (fdrSlot < currentState.registers.length) {
                                    try {
                                        const fdrDisplay = AnnotationUtils.formatRegisterName(fdrBank.base + valParam.index);
                                        const valValue = ValueFormatter.format(currentState.registers[fdrSlot]);
                                        valStrings.push(`${valParam.name}<span class="injected-value">[${fdrDisplay}=${valValue}]</span>`);
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

                        // Format WITH parameters (legacy)
                        if (withParams.length > 0 && currentState && currentState.registers && Array.isArray(currentState.registers)) {
                            result += ' WITH ';
                            const withStrings = [];
                            for (const withParam of withParams) {
                                const fdrSlot = fdrBank.slotOffset + withParam.index;
                                if (fdrSlot < currentState.registers.length) {
                                    try {
                                        const fdrDisplay = AnnotationUtils.formatRegisterName(fdrBank.base + withParam.index);
                                        const withValue = ValueFormatter.format(currentState.registers[fdrSlot]);
                                        withStrings.push(`${withParam.name}<span class="injected-value">[${fdrDisplay}=${withValue}]</span>`);
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
                    } else if (parameterBindings && Object.keys(parameterBindings).length > 0) {
                        // Fallback: Legacy WITH syntax
                        result += ' WITH ';
                        const paramStrings = [];
                        for (const [fdrIdStr, boundRegisterId] of Object.entries(parameterBindings)) {
                            const fdrId = parseInt(fdrIdStr);
                            const boundId = typeof boundRegisterId === 'number' ? boundRegisterId : parseInt(boundRegisterId);

                            const registerDisplay = AnnotationUtils.formatRegisterName(boundId);

                            let registerValue = 'N/A';
                            if (currentState) {
                                try {
                                    registerValue = ValueFormatter.format(AnnotationUtils.getRegisterValueById(boundId, currentState));
                                } catch (error) {
                                    console.error('ValueFormatter failed for WITH parameter bound register (fallback):', error.message);
                                    registerValue = 'ERR';
                                }
                            }

                            const fdrDisplay = AnnotationUtils.formatRegisterName(fdrId);
                            paramStrings.push(`${fdrDisplay}<span class="injected-value">[${registerDisplay}=${registerValue}]</span>`);
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

                const maxColumns = 8;
                let displayEntries = formattedEntries;
                if (formattedEntries.length > maxColumns) {
                    displayEntries = formattedEntries.slice(0, maxColumns - 1);
                    const remainingCount = formattedEntries.length - (maxColumns - 1);
                    displayEntries.push(`(+${remainingCount})`);
                }

                return displayEntries.map(entry => String(entry).padEnd(10)).join('');
            }
        };

        /**
         * Resolves parameterBindings from artifact for a given absolute call IP.
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

            const envProps = this.artifact?.envProps;
            const worldShape = envProps?.worldShape;
            const toroidal = envProps?.toroidal;
            const relativeCoord = [];
            for (let i = 0; i < absoluteCallIp.length && i < initialPosition.length; i++) {
                let rel = absoluteCallIp[i] - initialPosition[i];
                if (worldShape && toroidal && toroidal[i] && worldShape[i] > 0) {
                    rel = ((rel % worldShape[i]) + worldShape[i]) % worldShape[i];
                }
                relativeCoord.push(rel);
            }

            const coordKey = relativeCoord.join('|');

            if (!this.artifact.relativeCoordToLinearAddress || !this.artifact.relativeCoordToLinearAddress[coordKey]) {
                return null;
            }

            const linearAddress = this.artifact.relativeCoordToLinearAddress[coordKey];

            if (!this.artifact.callSiteBindings || !Array.isArray(this.artifact.callSiteBindings)) {
                return null;
            }

            const binding = this.artifact.callSiteBindings.find(csb => csb.linearAddress === linearAddress);
            if (!binding || !binding.bindings || typeof binding.bindings !== 'object') {
                return null;
            }

            // binding.bindings is already a map from formal register ID to source register ID
            const parameterBindings = {};
            for (const [formalId, sourceId] of Object.entries(binding.bindings)) {
                parameterBindings[parseInt(formalId)] = sourceId;
            }

            return parameterBindings;
        };

        // Check for stack changes (only on forward step)
        const dataStackChanged = isForwardStep && previousState &&
            (JSON.stringify(state.dataStack) !== JSON.stringify(previousState.dataStack));
        const locationStackChanged = isForwardStep && previousState &&
            (JSON.stringify(state.locationStack) !== JSON.stringify(previousState.locationStack));
        const callStackChanged = isForwardStep && previousState &&
            (JSON.stringify(state.callStack) !== JSON.stringify(previousState.callStack));

        // Build register display lines from flat registers array via REGISTER_BANKS iteration
        const registerLines = REGISTER_BANKS.map(bank => {
            const label = `${bank.name}:`.padEnd(5);
            const currentRegs = getBankRegisters(state.registers, bank);
            const prevRegs = previousState ? getBankRegisters(previousState.registers, bank) : null;
            return `${label}${formatRegisters(currentRegs, true, prevRegs)}`;
        }).join('\n');

        const drCount = BANK_BY_NAME.DR.count;
        const stateLines = `${registerLines}\n${dataStackChanged ? '<div class="changed-line">DS:  ' : 'DS:  '}${formatStack(state.dataStack, drCount, true)}${dataStackChanged ? '</div>' : ''}\n${locationStackChanged ? '<div class="changed-line">LS:  ' : 'LS:  '}${formatStack(state.locationStack, drCount, true)}${locationStackChanged ? '</div>' : ''}\n${callStackChanged ? '<div class="changed-line">CS:  ' : 'CS:  '}${formatCallStack(state.callStack, state)}${callStackChanged ? '</div>' : ''}`;

        el.innerHTML = `<div class="code-view" style="font-size:0.9em;">${stateLines}</div>`;

        // Save current state for next comparison
        this.previousState = { ...state };
    }
}
