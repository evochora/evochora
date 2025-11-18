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
            el.innerHTML = '<div class="code-view" style="font-size:0.9em;"></div>';
            return;
        }
        
        let lines = [];
        
        // Last executed instruction
        if (instructions.last) {
            lines.push(this.formatInstruction(instructions.last, tick, true));
        }
        
        // Next instruction
        if (instructions.next) {
            lines.push(this.formatInstruction(instructions.next, tick + 1, false));
        }
        
        el.innerHTML = `<div class="code-view" style="font-size:0.9em;">${lines.join('\n')}</div>`;
    }
    
    /**
     * Formats an instruction for display.
     * 
     * @param {Object} instruction - InstructionView object
     * @param {number} tick - Tick number
     * @param {boolean} isLast - Whether this is the last executed instruction
     * @returns {string} Formatted instruction line
     */
    formatInstruction(instruction, tick, isLast) {
        if (!instruction) return '';
        
        // Format position from IP before fetch: tick:x|y
        let pos = '?';
        if (instruction.ipBeforeFetch && instruction.ipBeforeFetch.length >= 2) {
            pos = `${instruction.ipBeforeFetch[0]}|${instruction.ipBeforeFetch[1]}`;
        } else if (instruction.ipBeforeFetch && instruction.ipBeforeFetch.length === 1) {
            pos = `${instruction.ipBeforeFetch[0]}`;
        }
        
        // Format arguments
        const argsStr = this.formatArguments(instruction.arguments);
        
        // Format energy cost
        const energyStr = instruction.energyCost > 0 ? ` (-${instruction.energyCost})` : '';
        
        // Build instruction line with "Last: " or "Next: " prefix
        const prefix = isLast ? 'Last: ' : 'Next: ';
        let line = `${prefix}${tick}:${pos} ${instruction.opcodeName}`;
        if (argsStr) {
            line += ` ${argsStr}`;
        }
        line += energyStr;
        
        // Add failed styling if needed
        if (instruction.failed) {
            const titleAttr = instruction.failureReason
                ? ` title="${this.escapeHtml(instruction.failureReason)}"`
                : '';
            return `<span class="failed-instruction"${titleAttr}>${line}</span>`;
        }
        
        return line;
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
                // Format: %DR0[=D:123] or %PR1[=1|0] (vectors use | not ,)
                // Use registerType from backend if available, otherwise fall back to heuristic
                const regName = arg.registerType 
                    ? this.getRegisterNameFromType(arg.registerId, arg.registerType)
                    : this.getRegisterName(arg.registerId, arg.registerValue);
                if (arg.registerValue) {
                    if (arg.registerValue.kind === 'MOLECULE') {
                        // Abbreviate types: CODE: -> C:, DATA: -> D:, etc.
                        const type = (arg.registerValue.type || '').replace(/CODE:/g, 'C:').replace(/DATA:/g, 'D:').replace(/ENERGY:/g, 'E:').replace(/STRUCTURE:/g, 'S:');
                        return `${regName}[=${type}${arg.registerValue.value}]`;
                    } else if (arg.registerValue.kind === 'VECTOR') {
                        const vecStr = arg.registerValue.vector.join('|');
                        return `${regName}[=${vecStr}]`;
                    }
                }
                return regName;
                
            case 'IMMEDIATE':
                // Format: TYPE:VALUE (e.g., DATA:42)
                if (arg.moleculeType && arg.value !== undefined) {
                    return `${arg.moleculeType}:${arg.value}`;
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

