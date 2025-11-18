/**
 * Renders instruction execution view in the sidebar.
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
            el.innerHTML = '<div class="instruction-section"><h3>Instructions</h3><p>No instruction data available</p></div>';
            return;
        }
        
        let html = '<div class="instruction-section"><h3>Instructions</h3>';
        
        // Last executed instruction
        if (instructions.last) {
            html += '<div class="instruction-group">';
            html += '<h4>Last executed</h4>';
            html += this.formatInstruction(instructions.last, tick, true);
            html += '</div>';
        }
        
        // Next instruction
        if (instructions.next) {
            html += '<div class="instruction-group">';
            html += '<h4>Next</h4>';
            html += this.formatInstruction(instructions.next, tick + 1, false);
            html += '</div>';
        }
        
        html += '</div>';
        el.innerHTML = html;
    }
    
    /**
     * Formats an instruction for display.
     * 
     * @param {Object} instruction - InstructionView object
     * @param {number} tick - Tick number
     * @param {boolean} isLast - Whether this is the last executed instruction
     * @returns {string} HTML string
     */
    formatInstruction(instruction, tick, isLast) {
        if (!instruction) return '';
        
        // Format position from IP before fetch
        const pos = instruction.ipBeforeFetch && instruction.ipBeforeFetch.length > 0
            ? instruction.ipBeforeFetch.join(':')
            : '?';
        
        // Format arguments
        const argsStr = this.formatArguments(instruction.arguments);
        
        // Format energy cost
        const energyStr = instruction.energyCost > 0 ? ` (-${instruction.energyCost})` : '';
        
        // Build instruction line
        const failedClass = instruction.failed ? ' failed' : '';
        const titleAttr = instruction.failed && instruction.failureReason
            ? ` title="${this.escapeHtml(instruction.failureReason)}"`
            : '';
        
        return `<div class="instruction-line${failedClass}"${titleAttr}>` +
               `[${tick}:${pos}] ${instruction.opcodeName} ${argsStr}${energyStr}` +
               `</div>`;
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
                // Format: %DR0[=D:123] or %PR1[=V:[1,0]]
                const regName = this.getRegisterName(arg.registerId);
                if (arg.registerValue) {
                    if (arg.registerValue.kind === 'MOLECULE') {
                        return `${regName}[=${arg.registerValue.type}:${arg.registerValue.value}]`;
                    } else if (arg.registerValue.kind === 'VECTOR') {
                        const vecStr = arg.registerValue.vector.join(',');
                        return `${regName}[=V:[${vecStr}]]`;
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
                // Format: [1,0]
                if (arg.components && arg.components.length > 0) {
                    return `[${arg.components.join(',')}]`;
                }
                return 'VECTOR:?';
                
            case 'LABEL':
                // Format: [1,0]
                if (arg.components && arg.components.length > 0) {
                    return `[${arg.components.join(',')}]`;
                }
                return 'LABEL:?';
                
            case 'STACK':
                // Format: STACK (no value)
                return 'STACK';
                
            default:
                return `?(${arg.type})`;
        }
    }
    
    /**
     * Gets register name from register ID.
     * 
     * @param {number} registerId - Register ID
     * @returns {string} Register name (e.g., "%DR0", "%PR1")
     */
    getRegisterName(registerId) {
        if (registerId === null || registerId === undefined) {
            return '?';
        }
        
        // FPR base: 2000, PR base: 1000
        if (registerId >= 2000) {
            return `%FPR${registerId - 2000}`;
        } else if (registerId >= 1000) {
            return `%PR${registerId - 1000}`;
        } else if (registerId >= 0 && registerId < 4) {
            // Assuming LR registers are 0-3
            return `%LR${registerId}`;
        } else {
            return `%DR${registerId}`;
        }
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

