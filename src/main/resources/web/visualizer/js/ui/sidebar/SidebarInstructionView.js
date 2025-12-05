import { ValueFormatter } from '../../utils/ValueFormatter.js';
import { AnnotationUtils } from '../../annotator/AnnotationUtils.js';

/**
 * Renders the instruction execution view in the sidebar.
 * This view displays the last executed instruction and the next instruction to be executed,
 * including their arguments, values, and energy costs. It is styled similarly to the
 * state view for consistency.
 *
 * @class SidebarInstructionView
 */
export class SidebarInstructionView {
    /**
     * Initializes the view.
     * @param {HTMLElement} root - The root element of the sidebar.
     */
    constructor(root) {
        this.root = root;
    }
    
    /**
     * Updates the instruction view with the last and next executed instructions.
     *
     * @param {object|null} instructions - An object containing `last` and `next` instruction data, or null.
     * @param {number} tick - The current tick number.
     */
    update(instructions, tick) {
        const el = this.root.querySelector('[data-section="instructions"]');
        if (!el) return;
        
        try {
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
        } catch (error) {
            console.error("Failed to render SidebarInstructionView:", error);
            el.innerHTML = `<div class="code-view instruction-view" style="font-size:0.9em; color: #ffaa00;">Error rendering instructions.</div>`;
        }
    }
    
    /**
     * Formats an instruction's IP and tick into a standard position string (e.g., "123:45|67").
     *
     * @param {number[]} ipBeforeFetch - The IP coordinates array `[x, y]`.
     * @param {number} tick - The tick number.
     * @returns {string} The formatted position string.
     * @private
     */
    formatPosition(ipBeforeFetch, tick) {
        let pos = '?';
        if (Array.isArray(ipBeforeFetch) && ipBeforeFetch.length > 0) {
            pos = `${ipBeforeFetch[0]}|${ipBeforeFetch[1]}`;
        } else if (ipBeforeFetch && ipBeforeFetch.length === 1) {
            pos = `${ipBeforeFetch[0]}`;
        }
        return `${tick}:${pos}`;
    }
    
    /**
     * Formats a single instruction object into an HTML string for display.
     *
     * @param {object} instruction - The instruction data object.
     * @param {number} tick - The tick number associated with this instruction.
     * @param {boolean} isLast - True if this is the "last executed" instruction.
     * @param {number} maxPosWidth - The maximum width for the position string, for alignment.
     * @returns {string} The formatted HTML string for the instruction line.
     * @private
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
        
        // Build instruction line
        const prefix = isLast ? 'Last: ' : 'Next: ';
        let instructionPart = `${prefix}<span class="instruction-position">${posStrPadded}</span> ${instruction.opcodeName}`;
        if (argsStr) {
            instructionPart += ` ${argsStr}`;
        }
        
        const titleAttr = instruction.failed && instruction.failureReason ? ` title="${this.escapeHtml(instruction.failureReason)}"` : '';
        const failedClass = instruction.failed ? ' failed-instruction' : '';
        
        let html = `<div class="instruction-line${failedClass}"${titleAttr}><span class="instruction-content">${instructionPart}</span>`;
        if (energyStr) {
            html += `<span class="instruction-energy">${energyStr}</span>`;
        }
        html += `</div>`;
        return html;
    }
    
    /**
     * Formats an array of instruction arguments into a single string.
     *
     * @param {Array<object>} args - The array of argument data objects.
     * @returns {string} A space-separated string of formatted arguments.
     * @private
     */
    formatArguments(args) {
        if (!args || args.length === 0) {
            return '';
        }
        return args.map(arg => this.formatArgument(arg)).join(' ');
    }
    
    /**
     * Formats a single instruction argument based on its type.
     *
     * @param {object} arg - The argument data object.
     * @returns {string} An HTML string representing the formatted argument.
     * @private
     */
    formatArgument(arg) {
        if (!arg || !arg.type) {
            return '?';
        }
        
        switch (arg.type) {
            case 'REGISTER': {
                const regName = arg.registerType 
                    ? this.getRegisterNameFromType(arg.registerId, arg.registerType)
                    : this.getRegisterName(arg.registerId, arg.registerValue);
                if (arg.registerValue) {
                    const valueStr = ValueFormatter.format(arg.registerValue);
                    const annotation = `=${valueStr.replace(/^\[|\]$/g, '')}`; // Remove brackets for inline view
                    return `${regName}<span class="register-annotation">${annotation}</span>`;
                }
                return regName;
            }
            case 'IMMEDIATE': {
                if (arg.moleculeType && arg.value !== undefined) {
                    // Reconstruct a temporary molecule-like object for the formatter
                    const molecule = { kind: 'MOLECULE', type: arg.moleculeType, value: arg.value };
                    return ValueFormatter.format(molecule);
                }
                return `IMMEDIATE:${arg.rawValue || '?'}`;
            }
            case 'VECTOR':
            case 'LABEL':
                if (arg.components && arg.components.length > 0) {
                    return arg.components.join('|');
                }
                return '?';
            case 'STACK':
                return 'STACK';
            default:
                return `?(${arg.type})`;
        }
    }
    
    /**
     * Gets the register name from its ID and a known type (e.g., "DR", "PR").
     * This is the preferred and most reliable way to determine the register name.
     *
     * @param {number} registerId - The numeric ID of the register.
     * @param {string} registerType - The type of the register ("DR", "PR", "FPR", "LR").
     * @returns {string} The formatted register name (e.g., "%DR0").
     * @private
     */
    /**
     * Gets the register name from its ID and optional type using the central utility.
     * 
     * @param {number} registerId - The numeric ID of the register.
     * @param {string} registerType - The explicit register type ('FPR', 'PR', 'DR', 'LR').
     * @returns {string} The formatted register name.
     * @private
     */
    getRegisterNameFromType(registerId, registerType) {
        return AnnotationUtils.formatRegisterName(registerId, registerType);
    }
    
    /**
     * Gets the register name from its ID using a heuristic.
     * This is a fallback for older data where the explicit `registerType` is not available.
     * It is not 100% reliable for distinguishing LRs from DRs.
     *
     * @param {number} registerId - The numeric ID of the register.
     * @param {object} registerValue - The value of the register, used in the heuristic.
     * @returns {string} The formatted register name.
     * @private
     */
    getRegisterName(registerId, registerValue) {
        if (registerId === null || registerId === undefined) {
            return '?';
        }
        
        // For registerId >= 1000, use central utility (can distinguish FPR/PR/DR)
        if (registerId >= INSTRUCTION_CONSTANTS.PR_BASE) {
            return AnnotationUtils.formatRegisterName(registerId);
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
        
        // For registerId >= 4 and < PR_BASE, it's always DR - use central utility
        return AnnotationUtils.formatRegisterName(registerId);
    }

    /**
     * Escapes HTML special characters to prevent XSS.
     * @param {string} text The text to escape.
     * @returns {string} The escaped text.
     * @private
     */
    escapeHtml(text) {
        if (typeof text !== 'string') return '';
        return text.replace(/&/g, "&amp;")
                   .replace(/</g, "&lt;")
                   .replace(/>/g, "&gt;")
                   .replace(/"/g, "&quot;")
                   .replace(/'/g, "&#039;");
    }
}

