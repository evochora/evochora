import { ValueFormatter } from '../../utils/ValueFormatter.js';
import { AnnotationUtils } from '../../annotator/AnnotationUtils.js';

/**
 * Renders the instruction execution view in the organism panel.
 * This view displays the last executed instruction and the next instruction to be executed,
 * including their arguments, values, and energy costs. It is styled similarly to the
 * state view for consistency.
 *
 * @class OrganismInstructionView
 */
export class OrganismInstructionView {
    /**
     * Initializes the view.
     * @param {HTMLElement} root - The root element of the organism panel.
     */
    constructor(root) {
        this.root = root;
        this.artifact = null;
    }

    /**
     * Sets the program artifact for label name resolution.
     * This is static metadata that changes only when the program changes.
     * @param {object} artifact - The ProgramArtifact containing labelValueToName map.
     */
    setProgram(artifact) {
        this.artifact = artifact;
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
            console.error("Failed to render OrganismInstructionView:", error);
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
        
        // Format thermodynamics: only for last (executed) instruction — next instruction has no cost data
        let thermoHtml = '';
        if (isLast) {
            const energyCost = instruction.energyCost || 0;
            const entropyDelta = instruction.entropyDelta || 0;
            const erStr = energyCost > 0 ? `-${energyCost}` : energyCost < 0 ? `+${Math.abs(energyCost)}` : '0';
            const srStr = entropyDelta > 0 ? `+${entropyDelta}` : entropyDelta < 0 ? `${entropyDelta}` : '0';
            thermoHtml = `<span class="instruction-energy">ER:${erStr}/SR:${srStr}</span>`;
        }

        // Build instruction line
        const prefix = isLast ? 'Last: ' : 'Next: ';
        let instructionPart = `${prefix}<span class="instruction-position">${posStrPadded}</span> ${instruction.opcodeName}`;
        if (argsStr) {
            instructionPart += ` ${argsStr}`;
        }

        const titleAttr = instruction.failed && instruction.failureReason ? ` title="${this.escapeHtml(instruction.failureReason)}"` : '';
        const failedClass = instruction.failed ? ' failed-instruction' : '';

        let html = `<div class="instruction-line${failedClass}"${titleAttr}><span class="instruction-content">${instructionPart}</span>`;
        html += thermoHtml;
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
                if (!arg.registerType) {
                    throw new Error(`REGISTER argument missing registerType for registerId ${arg.registerId}`);
                }
                const regName = this.getRegisterNameFromType(arg.registerId, arg.registerType);
                if (arg.registerValue) {
                    const valueStr = ValueFormatter.format(arg.registerValue);
                    const annotation = `=${valueStr.replace(/^\[|\]$/g, '')}`; // Remove brackets for inline view
                    return `${regName}<span class="annotation">${annotation}</span>`;
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
                if (arg.components && arg.components.length > 0) {
                    return arg.components.join('|');
                }
                return '?';
            case 'LABEL': {
                // LABEL is a scalar hash value since fuzzy jumps refactoring
                // Format like IMMEDIATE (e.g., "DATA:123456") with label name annotation
                let formatted = `LABEL:${arg.value || '?'}`;
                if (arg.moleculeType && arg.value !== undefined) {
                    const molecule = { kind: 'MOLECULE', type: arg.moleculeType, value: arg.value };
                    formatted = ValueFormatter.format(molecule);
                }
                // Annotate with label name if artifact is available
                const labelName = AnnotationUtils.resolveLabelHashToName(arg.value, this.artifact);
                if (labelName) {
                    return `${formatted}<span class="annotation">=${labelName}</span>`;
                }
                return formatted;
            }
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

