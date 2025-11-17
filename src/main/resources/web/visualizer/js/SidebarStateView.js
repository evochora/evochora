/**
 * Renders organism runtime state in the sidebar.
 */
class SidebarStateView {
    constructor(root) {
        this.root = root;
        this.previousState = null;
    }

    /**
     * Updates the state section with organism runtime data.
     * 
     * @param {Object} state - Runtime state:
     *   { energy, ip, dv, dataPointers, activeDpIndex, dataRegisters, procedureRegisters,
     *     formalParamRegisters, locationRegisters, dataStack, locationStack, callStack }
     * @param {boolean} isForwardStep - Whether this is a forward step (x -> x+1)
     * @param {Object} previousState - Previous state for change detection (optional)
     */
    update(state, isForwardStep = false, previousState = null) {
        const el = this.root.querySelector('[data-section="state"]');
        if (!state || !el) return;

        // Helper function for register formatting
        const formatRegisters = (registers, removeBrackets = false, previousRegisters = null) => {
            if (!registers || registers.length === 0) return '';

            const formatted = [];
            for (let i = 0; i < registers.length; i++) {
                let value = '';
                if (registers[i]) {
                    if (registers[i].kind === 'VECTOR' && registers[i].vector) {
                        // Vector: [x, y]
                        value = `[${registers[i].vector.join('|')}]`;
                    } else if (registers[i].kind === 'MOLECULE') {
                        // Molecule: TYPE:value
                        const type = registers[i].type || 'UNKNOWN';
                        const val = registers[i].value !== undefined ? registers[i].value : '';
                        value = `${type}:${val}`;
                    } else if (Array.isArray(registers[i])) {
                        // Location register: [x, y] (fallback for locationRegisters)
                        value = `[${registers[i].join('|')}]`;
                    } else if (typeof registers[i] === 'string') {
                        value = registers[i];
                    }
                    
                    // Abbreviate types: CODE: -> C:, DATA: -> D:, ENERGY: -> E:, STRUCTURE: -> S:
                    value = value.replace(/CODE:/g, 'C:').replace(/DATA:/g, 'D:').replace(/ENERGY:/g, 'E:').replace(/STRUCTURE:/g, 'S:');

                    // Remove brackets if requested
                    if (removeBrackets) {
                        value = value.replace(/^\[|\]$/g, '');
                    }
                    
                    // Check for changes (only on forward step)
                    let isChanged = false;
                    if (isForwardStep && previousRegisters && previousRegisters[i]) {
                        let prevValue = '';
                        if (previousRegisters[i].kind === 'VECTOR' && previousRegisters[i].vector) {
                            prevValue = `[${previousRegisters[i].vector.join('|')}]`;
                        } else if (previousRegisters[i].kind === 'MOLECULE') {
                            const prevType = previousRegisters[i].type || 'UNKNOWN';
                            const prevVal = previousRegisters[i].value !== undefined ? previousRegisters[i].value : '';
                            prevValue = `${prevType}:${prevVal}`;
                        } else if (Array.isArray(previousRegisters[i])) {
                            prevValue = `[${previousRegisters[i].join('|')}]`;
                        } else if (typeof previousRegisters[i] === 'string') {
                            prevValue = previousRegisters[i];
                        }
                        prevValue = prevValue.replace(/CODE:/g, 'C:').replace(/DATA:/g, 'D:').replace(/ENERGY:/g, 'E:').replace(/STRUCTURE:/g, 'S:');
                        if (removeBrackets) {
                            prevValue = prevValue.replace(/^\[|\]$/g, '');
                        }
                        
                        if (value !== prevValue) {
                            isChanged = true;
                        }
                    }
                    
                    // Pad to 8 characters before adding HTML (to maintain alignment)
                    const paddedValue = String(value).padEnd(8);
                    
                    // Add HTML for changed values
                    if (isChanged) {
                        value = `<span class="changed-field">${paddedValue}</span>`;
                    } else {
                        value = paddedValue;
                    }
                }
                // Add to formatted array
                formatted.push(value);
            }

            return formatted.join('');
        };

        // Helper function for stack formatting
        const formatStack = (stack, maxColumns = 8, removeBrackets = false) => {
            if (!stack || stack.length === 0) return '';

            const formattedStack = stack.map(item => {
                let value = '';
                if (item && item.kind === 'VECTOR' && item.vector) {
                    // Vector: [x, y]
                    value = `[${item.vector.join('|')}]`;
                } else if (item && item.kind === 'MOLECULE') {
                    // Molecule: TYPE:value
                    const type = item.type || 'UNKNOWN';
                    const val = item.value !== undefined ? item.value : '';
                    value = `${type}:${val}`;
                } else if (Array.isArray(item)) {
                    // Location stack: [x, y]
                    value = `[${item.join('|')}]`;
                } else if (typeof item === 'string') {
                    value = item;
                }
                
                // Abbreviate types
                value = value.replace(/CODE:/g, 'C:').replace(/DATA:/g, 'D:').replace(/ENERGY:/g, 'E:').replace(/STRUCTURE:/g, 'S:');

                // Remove brackets if requested
                if (removeBrackets) {
                    value = value.replace(/^\[|\]$/g, '');
                }

                return value;
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

        // Helper function for call stack formatting
        const formatCallStack = (callStack) => {
            if (!callStack || callStack.length === 0) return '';

            // Check if we have procedure names
            const hasProcNames = callStack.some(entry => entry.procName && entry.procName.trim() !== '');

            if (hasProcNames) {
                // With procedure names: one line per entry
                const formattedCallStack = callStack.map((entry, index) => {
                    let result = entry.procName || 'UNKNOWN';

                    // Add return coordinates: [x|y] with injected-value styling
                    if (entry.absoluteReturnIp && Array.isArray(entry.absoluteReturnIp) && entry.absoluteReturnIp.length >= 2) {
                        result += ` <span class="injected-value">[${entry.absoluteReturnIp[0]}|${entry.absoluteReturnIp[1]}]</span>`;
                    }

                    // Add parameters from fprBindings
                    if (entry.fprBindings && Object.keys(entry.fprBindings).length > 0) {
                        result += ' WITH ';
                        const paramStrings = [];
                        for (const [fprIdStr, boundRegisterId] of Object.entries(entry.fprBindings)) {
                            const fprId = parseInt(fprIdStr);
                            const boundId = typeof boundRegisterId === 'number' ? boundRegisterId : parseInt(boundRegisterId);
                            
                            // Determine register display name
                            let registerDisplay = '';
                            if (boundId >= 2000) {
                                registerDisplay = `%FPR${boundId - 2000}`;
                            } else if (boundId >= 1000) {
                                registerDisplay = `%PR${boundId - 1000}`;
                            } else {
                                registerDisplay = `%DR${boundId}`;
                            }
                            
                            // Get value from savedFprs if available
                            let registerValue = '';
                            if (entry.savedFprs && Array.isArray(entry.savedFprs)) {
                                // Find the FPR in savedFprs by matching the bound register
                                const fprEntry = entry.savedFprs.find((fpr, idx) => {
                                    // Check if this FPR corresponds to the bound register
                                    // For now, try to find by index (fprId - 2000)
                                    return idx === (fprId >= 2000 ? fprId - 2000 : fprId);
                                });
                                
                                if (fprEntry) {
                                    if (fprEntry.kind === 'VECTOR' && fprEntry.vector) {
                                        registerValue = `[${fprEntry.vector.join('|')}]`;
                                    } else if (fprEntry.kind === 'MOLECULE') {
                                        const type = fprEntry.type || 'UNKNOWN';
                                        const val = fprEntry.value !== undefined ? fprEntry.value : '';
                                        registerValue = `${type}:${val}`;
                                    }
                                    registerValue = registerValue.replace(/CODE:/g, 'C:').replace(/DATA:/g, 'D:').replace(/ENERGY:/g, 'E:').replace(/STRUCTURE:/g, 'S:');
                                }
                            }
                            
                            const fprDisplay = fprId >= 2000 ? `%FPR${fprId - 2000}` : `%FPR${fprId}`;
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
                    if (entry.absoluteReturnIp && Array.isArray(entry.absoluteReturnIp) && entry.absoluteReturnIp.length >= 2) {
                        return `${entry.absoluteReturnIp[0]}|${entry.absoluteReturnIp[1]}`;
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

        // Check for stack changes (only on forward step)
        const dataStackChanged = isForwardStep && previousState &&
            (JSON.stringify(state.dataStack) !== JSON.stringify(previousState.dataStack));
        const locationStackChanged = isForwardStep && previousState &&
            (JSON.stringify(state.locationStack) !== JSON.stringify(previousState.locationStack));
        const callStackChanged = isForwardStep && previousState &&
            (JSON.stringify(state.callStack) !== JSON.stringify(previousState.callStack));
        
        // Format state view (IP, DV, ER, DPs are shown in BasicInfoView changeable-box, not here)
        const stateLines = `DR:  ${formatRegisters(state.dataRegisters, true, previousState?.dataRegisters)}\nPR:  ${formatRegisters(state.procedureRegisters, true, previousState?.procedureRegisters)}\nFPR: ${formatRegisters(state.formalParamRegisters, true, previousState?.formalParamRegisters)}\nLR:  ${formatRegisters(state.locationRegisters, true, previousState?.locationRegisters)}\n${dataStackChanged ? '<div class="changed-line">DS:  ' : 'DS:  '}${formatStack(state.dataStack, state.dataRegisters?.length || 8, true)}${dataStackChanged ? '</div>' : ''}\n${locationStackChanged ? '<div class="changed-line">LS:  ' : 'LS:  '}${formatStack(state.locationStack, state.dataRegisters?.length || 8, true)}${locationStackChanged ? '</div>' : ''}\n${callStackChanged ? '<div class="changed-line">CS:  ' : 'CS:  '}${formatCallStack(state.callStack)}${callStackChanged ? '</div>' : ''}`;

        el.innerHTML = `<div class="code-view" style="font-size:0.9em;">${stateLines}</div>`;

        // Save current state for next comparison
        this.previousState = { ...state };
    }
}

// Export for global availability
window.SidebarStateView = SidebarStateView;

