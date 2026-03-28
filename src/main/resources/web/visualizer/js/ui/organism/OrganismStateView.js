import { ValueFormatter } from '../../utils/ValueFormatter.js';
import { AnnotationUtils, REGISTER_BANKS, BANK_BY_NAME } from '../../annotator/AnnotationUtils.js';

/**
 * Renders the dynamic runtime state of an organism in the organism panel.
 * This includes all register banks (DR, LR, PDR, PLR, FDR, FLR, SDR, SLR) and stacks (Data, Location, Call).
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

                    // Bank info for parameter display
                    const fdrBank = BANK_BY_NAME.FDR;
                    const flrBank = BANK_BY_NAME.FLR;

                    // Format parameters using REF/VAL/LREF/LVAL syntax if parameter info available
                    if (paramInfo && paramInfo.length > 0) {
                        // Classify parameters with bank-specific indices:
                        // Data params (REF, VAL) share sequential FDR indices.
                        // Location params (LREF, LVAL) share sequential FLR indices.
                        const refParams = [];
                        const valParams = [];
                        const lrefParams = [];
                        const lvalParams = [];
                        let dataIndex = 0;
                        let locationIndex = 0;

                        for (let i = 0; i < paramInfo.length; i++) {
                            const param = paramInfo[i];
                            if (!param || !param.name) continue;

                            const paramType = param.type;
                            let classified = false;

                            if (typeof paramType === 'number') {
                                if (paramType === 0)      { refParams.push({ bankIndex: dataIndex++, name: param.name }); classified = true; }
                                else if (paramType === 1)  { valParams.push({ bankIndex: dataIndex++, name: param.name }); classified = true; }
                                else if (paramType === 2)  { lrefParams.push({ bankIndex: locationIndex++, name: param.name }); classified = true; }
                                else if (paramType === 3)  { lvalParams.push({ bankIndex: locationIndex++, name: param.name }); classified = true; }
                            } else if (typeof paramType === 'string') {
                                const typeUpper = paramType.toUpperCase();
                                if (typeUpper === 'REF' || typeUpper === 'PARAM_TYPE_REF')        { refParams.push({ bankIndex: dataIndex++, name: param.name }); classified = true; }
                                else if (typeUpper === 'VAL' || typeUpper === 'PARAM_TYPE_VAL')    { valParams.push({ bankIndex: dataIndex++, name: param.name }); classified = true; }
                                else if (typeUpper === 'LREF' || typeUpper === 'PARAM_TYPE_LREF')  { lrefParams.push({ bankIndex: locationIndex++, name: param.name }); classified = true; }
                                else if (typeUpper === 'LVAL' || typeUpper === 'PARAM_TYPE_LVAL')  { lvalParams.push({ bankIndex: locationIndex++, name: param.name }); classified = true; }
                            }

                            if (!classified && (paramType === undefined || paramType === null)) {
                                refParams.push({ bankIndex: dataIndex++, name: param.name });
                            }
                        }

                        const hasRegisters = currentState && currentState.registers && Array.isArray(currentState.registers);

                        // Helper to format a parameter group with its bank
                        const formatParamGroup = (label, params, bank) => {
                            if (params.length === 0 || !hasRegisters) return;
                            result += ` ${label} `;
                            const strings = [];
                            for (const p of params) {
                                const slot = bank.slotOffset + p.bankIndex;
                                if (slot < currentState.registers.length) {
                                    try {
                                        const regDisplay = AnnotationUtils.formatRegisterName(bank.base + p.bankIndex);
                                        const regValue = ValueFormatter.format(currentState.registers[slot]);
                                        strings.push(`${p.name}<span class="injected-value">[${regDisplay}=${regValue}]</span>`);
                                    } catch (error) {
                                        console.error(`ValueFormatter failed for ${label} parameter:`, error.message);
                                        strings.push(p.name);
                                    }
                                } else {
                                    strings.push(p.name);
                                }
                            }
                            result += strings.join(' ');
                        };

                        formatParamGroup('REF', refParams, fdrBank);
                        formatParamGroup('VAL', valParams, fdrBank);
                        formatParamGroup('LREF', lrefParams, flrBank);
                        formatParamGroup('LVAL', lvalParams, flrBank);
                    } else if (parameterBindings && Object.keys(parameterBindings).length > 0) {
                        // Fallback: no param type info available — display raw bindings without keyword
                        result += ' ';
                        const paramStrings = [];
                        for (const [formalIdStr, boundRegisterId] of Object.entries(parameterBindings)) {
                            const formalId = parseInt(formalIdStr);
                            const boundId = typeof boundRegisterId === 'number' ? boundRegisterId : parseInt(boundRegisterId);

                            const registerDisplay = AnnotationUtils.formatRegisterName(boundId);

                            let registerValue = 'N/A';
                            if (currentState) {
                                try {
                                    registerValue = ValueFormatter.format(AnnotationUtils.getRegisterValueById(boundId, currentState));
                                } catch (error) {
                                    console.error('ValueFormatter failed for bound register (fallback):', error.message);
                                    registerValue = 'ERR';
                                }
                            }

                            const formalDisplay = AnnotationUtils.formatRegisterName(formalId);
                            paramStrings.push(`${formalDisplay}<span class="injected-value">[${registerDisplay}=${registerValue}]</span>`);
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

            if (!this.artifact.relativeCoordToLinearAddress ||
                !(coordKey in this.artifact.relativeCoordToLinearAddress)) {
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
