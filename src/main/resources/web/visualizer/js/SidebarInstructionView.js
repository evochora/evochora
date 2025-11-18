/**
 * Renders instruction execution view in the sidebar.
 * Styled like State View (code-box, no heading).
 */
class SidebarInstructionView {
    constructor(root) {
        this.root = root;
    }
    
    /**
     * Updates the instruction view with last and next executed instructions.
     * 
     * @param {Object} instructions - Instructions object:
     *   { last: InstructionView, next: InstructionView }
     * @param {number} tick - Current tick number
     */
    update(instructions, tick) {
        const el = this.root.querySelector('[data-section="instructions"]');
        if (!el) return;
        
        if (!instructions || (!instructions.last && !instructions.next)) {
            el.innerHTML = '<div class="code-view instruction-view" style="font-size:0.9em; white-space: pre-wrap;"></div>';
            return;
        }
        
        // Calculate position strings for both instructions to determine max width
        let posStrings = [];
        if (instructions.last) {
            const pos = this.formatPosition(instructions.last.ipBeforeFetch, tick);
            posStrings.push(pos);
        }
        if (instructions.next) {
            const pos = this.formatPosition(instructions.next.ipBeforeFetch, tick + 1);
            posStrings.push(pos);
        }
        
        // Find maximum position width (default to 0 if no positions)
        const maxPosWidth = posStrings.length > 0 ? Math.max(...posStrings.map(p => p.length)) : 0;
        
        let lines = [];
        
        // Last executed instruction
        if (instructions.last) {
            lines.push(this.formatInstruction(instructions.last, tick, true, maxPosWidth));
        }
        
        // Next instruction
        if (instructions.next) {
            lines.push(this.formatInstruction(instructions.next, tick + 1, false, maxPosWidth));
        }
        
        // Join lines (no newlines needed, each line is a div)
        el.innerHTML = `<div class="code-view instruction-view" style="font-size:0.9em;">${lines.join('')}</div>`;
    }
    
    /**
     * Formats position from IP before fetch: tick:x|y
     * 
     * @param {Array} ipBeforeFetch - IP coordinates array
     * @param {number} tick - Tick number
     * @returns {string} Formatted position string
     */
    formatPosition(ipBeforeFetch, tick) {
        let pos = '?';
        if (ipBeforeFetch && ipBeforeFetch.length >= 2) {
            pos = `${ipBeforeFetch[0]}|${ipBeforeFetch[1]}`;
        } else if (ipBeforeFetch && ipBeforeFetch.length === 1) {
            pos = `${ipBeforeFetch[0]}`;
        }
        return `${tick}:${pos}`;
    }
    
    /**
     * Formats an instruction for display.
     * 
     * @param {Object} instruction - InstructionView object
     * @param {number} tick - Tick number
     * @param {boolean} isLast - Whether this is the last executed instruction
     * @returns {string} Formatted instruction line
     */
    formatInstruction(instruction, tick, isLast, maxPosWidth) {
        if (!instruction) return '';
        
        // Format position with dynamic width (based on max width of both lines)
        const posStr = this.formatPosition(instruction.ipBeforeFetch, tick);
        const posStrPadded = posStr.padEnd(maxPosWidth);
        
        // Format arguments
        const argsStr = this.formatArguments(instruction.arguments);
        
        // Format energy cost (right-aligned, no parentheses)
        const energyStr = instruction.energyCost > 0 ? `-${instruction.energyCost}` : '';
        
        // Build instruction line with "Last: " or "Next: " prefix
        const prefix = isLast ? 'Last: ' : 'Next: ';
        let instructionPart = `${prefix}<span class="instruction-position">${posStrPadded}</span> ${instruction.opcodeName}`;
        if (argsStr) {
            instructionPart += ` ${argsStr}`;
        }
        
        // Use CSS for right-aligned energy
        if (energyStr) {
            const titleAttr = instruction.failed && instruction.failureReason
                ? ` title="${this.escapeHtml(instruction.failureReason)}"`
                : '';
            const failedClass = instruction.failed ? ' failed-instruction' : '';
            return `<div class="instruction-line${failedClass}"${titleAttr}><span class="instruction-content">${instructionPart}</span><span class="instruction-energy">${energyStr}</span></div>`;
        } else {
            const titleAttr = instruction.failed && instruction.failureReason
                ? ` title="${this.escapeHtml(instruction.failureReason)}"`
                : '';
            const failedClass = instruction.failed ? ' failed-instruction' : '';
            return `<div class="instruction-line${failedClass}"${titleAttr}><span class="instruction-content">${instructionPart}</span></div>`;
        }
    }
    
    /**
     * Formats instruction arguments for display.
     * 
     * @param {Array} args - Array of InstructionArgumentView objects
     * @returns {string} Formatted arguments string
     */
    formatArguments(args) {
        if (!args || args.length === 0) {
            return '';
        }
        
        return args.map(arg => this.formatArgument(arg)).join(' ');
    }
    
    /**
     * Formats a single argument for display.
     * 
     * @param {Object} arg - InstructionArgumentView object
     * @returns {string} Formatted argument string
     */
    formatArgument(arg) {
        if (!arg || !arg.type) {
            return '?';
        }
        
        switch (arg.type) {
            case 'REGISTER':
                // Format: %DR0=D:123 or %PR1=1|0 (no brackets, annotations in green)
                // Use registerType from backend if available, otherwise fall back to heuristic
                const regName = arg.registerType 
                    ? this.getRegisterNameFromType(arg.registerId, arg.registerType)
                    : this.getRegisterName(arg.registerId, arg.registerValue);
                if (arg.registerValue) {
                    if (arg.registerValue.kind === 'MOLECULE') {
                        // Abbreviate types: CODE: -> C:, DATA: -> D:, etc.
                        const typeAbbr = this.abbreviateType(arg.registerValue.type || '');
                        const annotation = `=${typeAbbr}${arg.registerValue.value}`;
                        return `${regName}<span class="register-annotation">${annotation}</span>`;
                    } else if (arg.registerValue.kind === 'VECTOR') {
                        const vecStr = arg.registerValue.vector.join('|');
                        const annotation = `=${vecStr}`;
                        return `${regName}<span class="register-annotation">${annotation}</span>`;
                    }
                }
                return regName;
                
            case 'IMMEDIATE':
                // Format: D:0 (abbreviated type)
                if (arg.moleculeType && arg.value !== undefined) {
                    const typeAbbr = this.abbreviateType(arg.moleculeType);
                    return `${typeAbbr}${arg.value}`;
                }
                return `IMMEDIATE:${arg.rawValue || '?'}`;
                
            case 'VECTOR':
                // Format: x|y (no brackets, no V: prefix)
                if (arg.components && arg.components.length > 0) {
                    return arg.components.join('|');
                }
                return '?';
                
            case 'LABEL':
                // Format: x|y (no brackets)
                if (arg.components && arg.components.length > 0) {
                    return arg.components.join('|');
                }
                return '?';
                
            case 'STACK':
                // Format: STACK (no value)
                return 'STACK';
                
            default:
                return `?(${arg.type})`;
        }
    }
    
    /**
     * Abbreviates molecule type names: DATA -> D, CODE -> C, STRUCTURE -> S, ENERGY -> E
     * 
     * @param {string} type - Full type name (e.g., "DATA", "CODE", "STRUCTURE", "ENERGY")
     * @returns {string} Abbreviated type with colon (e.g., "D:", "C:", "S:", "E:")
     */
    abbreviateType(type) {
        if (!type) return '';
        const upper = type.toUpperCase();
        if (upper.startsWith('DATA')) return 'D:';
        if (upper.startsWith('CODE')) return 'C:';
        if (upper.startsWith('STRUCTURE')) return 'S:';
        if (upper.startsWith('ENERGY')) return 'E:';
        // Fallback: return first letter if type is single word
        return upper.charAt(0) + ':';
    }
    
    /**
     * Gets register name from register ID and register type (preferred method).
     * 
     * @param {number} registerId - Register ID
     * @param {string} registerType - Register type: "DR", "PR", "FPR", or "LR"
     * @returns {string} Register name (e.g., "%DR0", "%PR1", "%LR0")
     */
    getRegisterNameFromType(registerId, registerType) {
        if (registerId === null || registerId === undefined) {
            return '?';
        }
        
        switch (registerType) {
            case 'FPR':
                return `%FPR${registerId - 2000}`;
            case 'PR':
                return `%PR${registerId - 1000}`;
            case 'LR':
                return `%LR${registerId}`;
            case 'DR':
            default:
                return `%DR${registerId}`;
        }
    }
    
    /**
     * Gets register name from register ID (fallback method using heuristic).
     * 
     * This method is used when registerType is not available (backward compatibility).
     * It uses a heuristic to distinguish between LR and DR for registerId < 1000.
     * 
     * Register ID interpretation (matching backend resolveRegisterValue logic):
     * - registerId >= 2000: FPR (Formal Parameter Register)
     * - registerId >= 1000: PR (Procedure Register)
     * - registerId >= 0 && registerId < locationRegisters.size(): LR (Location Register)
     * - registerId >= 0 && registerId < dataRegisters.size(): DR (Data Register)
     * 
     * Since we don't have locationRegisters.size() in the frontend, we use a heuristic:
     * - If registerId < 1000 and registerValue.kind === 'VECTOR' and registerId < 4: likely LR
     * - Otherwise: DR
     * 
     * @param {number} registerId - Register ID
     * @param {Object} registerValue - RegisterValueView (to determine if it's LR)
     * @returns {string} Register name (e.g., "%DR0", "%PR1", "%LR0")
     */
    getRegisterName(registerId, registerValue) {
        if (registerId === null || registerId === undefined) {
            return '?';
        }
        
        // FPR base: 2000
        if (registerId >= 2000) {
            return `%FPR${registerId - 2000}`;
        }
        
        // PR base: 1000
        if (registerId >= 1000) {
            return `%PR${registerId - 1000}`;
        }
        
        // For registerId < 1000, we need to distinguish between LR and DR
        // Backend logic: if registerId < locationRegisters.size() then LR, else DR
        // Since we don't have locationRegisters.size(), we use a heuristic:
        // - If registerValue is VECTOR and registerId < 4, it's likely LR
        // - But DR can also have VECTOR values, so this is not 100% reliable
        // - However, LR always have VECTOR values, so if we see VECTOR with registerId < 4, it's most likely LR
        if (registerId >= 0 && registerId < 4) {
            // Heuristic: if value is VECTOR, it's likely LR (LR always have VECTOR values)
            // But we need to be careful: DR can also have VECTOR values
            // The backend checks LR first if registerId < locationRegisters.size()
            // Since we don't have that info, we use the heuristic that VECTOR + registerId < 4 = LR
            if (registerValue && registerValue.kind === 'VECTOR') {
                return `%LR${registerId}`;
            }
            // If value is MOLECULE or null, it's DR
            return `%DR${registerId}`;
        }
        
        // For registerId >= 4 and < 1000, it's always DR
        return `%DR${registerId}`;
    }
    
    /**
     * Escapes HTML special characters.
     * 
     * @param {string} text - Text to escape
     * @returns {string} Escaped text
     */
    escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}

