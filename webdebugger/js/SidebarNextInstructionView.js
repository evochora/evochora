class SidebarNextInstructionView {
    constructor(root) { 
        this.root = root; 
        this.previousState = null;
    }
    
    /**
     * Formatiert einen numerischen Register-Wert in einen lesbaren Namen.
     */
    formatRegisterName(registerValue) {
        const value = parseInt(registerValue);
        if (isNaN(value)) return registerValue;
        
        // FPR-Base ist 2000, PR-Base ist 1000
        if (value >= 2000) {
            // Floating Point Register
            return `%FPR${value - 2000}`;
        } else if (value >= 1000) {
            // Procedure Register
            return `%PR${value - 1000}`;
        } else {
            // Data Register
            return `%DR${value}`;
        }
    }

    /**
     * Extrahiert den Wert aus einem Molekül-String (z.B. "DATA:0" -> 0).
     */
    extractValueFromMolecule(moleculeText) {
        const colonIndex = moleculeText.indexOf(':');
        if (colonIndex === -1) return moleculeText;
        
        const valuePart = moleculeText.substring(colonIndex + 1);
        const value = parseInt(valuePart);
        return isNaN(value) ? valuePart : value;
    }

    /**
     * Formatiert Argumente basierend auf ihren ISA-Typen.
     * VECTOR/LABEL werden als [x|y] gruppiert, andere einzeln.
     */
    formatArgumentsByType(args, argTypes, internalState) {
        if (!args || !argTypes) return [];
        
        const formattedArgs = [];
        let i = 0;
        
        // Hole die Welt-Dimensionen aus dem Renderer
        const worldDimensions = window.EvoDebugger.renderer?.config?.WORLD_SHAPE?.length || 2;
        
        while (i < argTypes.length) {
            const argType = argTypes[i];
            
            if (argType === 'VECTOR' || argType === 'LABEL') {
                // VECTOR/LABEL: Gruppiere n-Dimensionen als [x|y]
                const vectorArgs = [];
                
                for (let dim = 0; dim < worldDimensions && i < args.length; dim++) {
                    const argText = String(args[i]);
                    const value = this.extractValueFromMolecule(argText);
                    vectorArgs.push(value);
                    i++;
                }
                
                if (vectorArgs.length > 0) {
                    formattedArgs.push(`[${vectorArgs.join('|')}]`);
                }
            } else if (argType === 'REGISTER') {
                // Register: %DR0[CODE:0] for direct registers, %TMP[%DR0=CODE:0] for aliases
                const argText = String(args[i]);
                const value = this.extractValueFromMolecule(argText);
                const registerName = this.formatRegisterName(value);
                const currentValue = this.getCurrentRegisterValue(value, internalState);
                
                // Only use = for FPR registers (aliases), not for direct DR/PR/LR registers
                const isDirectRegister = registerName.match(/^%[DL]?R\d+$/);
                const separator = isDirectRegister ? '' : '=';
                formattedArgs.push(`${registerName}<span class="injected-value">[${separator}${currentValue}]</span>`);
                i++;
            } else {
                // LITERAL/UNKNOWN: Zeige einzeln
                const argText = String(args[i]);
                formattedArgs.push(argText);
                i++;
            }
        }
        
        return formattedArgs;
    }

    /**
     * Holt den aktuellen Wert eines Registers aus dem Internal State.
     */
    getCurrentRegisterValue(registerId, internalState) {
        if (!internalState) return 'N/A';
        
        // Bestimme den Register-Typ basierend auf der ID
        let registerList;
        if (registerId >= 2000) {
            // FPR (Floating Point Register)
            registerList = internalState.fpRegisters;
            registerId = registerId - 2000;
        } else if (registerId >= 1000) {
            // PR (Procedure Register)
            registerList = internalState.procRegisters;
            registerId = registerId - 1000;
        } else {
            // DR (Data Register)
            registerList = internalState.dataRegisters;
        }
        
        // Suche nach dem Register mit der passenden ID
        if (registerList && registerList[registerId]) {
            return registerList[registerId].value;
        }
        
        return 'N/A';
    }

    /**
     * Holt den Internal State aus dem AppController.
     */
    getInternalStateFromAppController() {
        // Versuche den Internal State aus dem aktuell ausgewählten Organismus zu holen
        if (window.EvoDebugger.controller && 
            window.EvoDebugger.controller.state && 
            window.EvoDebugger.controller.state.lastTickData) {
            
            const selectedOrganismId = window.EvoDebugger.controller.state.selectedOrganismId;
            
            if (selectedOrganismId) {
                const organismDetails = window.EvoDebugger.controller.state.lastTickData.organismDetails[selectedOrganismId];
                
                if (organismDetails && organismDetails.internalState) {
                    return organismDetails.internalState;
                }
            }
        }
        
        return null;
    }

    update(next, navigationDirection) {
        const el = this.root.querySelector('[data-section="nextInstruction"]');
        if (!el) return;

        if (!next) {
            el.innerHTML = '';
            return;
        }
            
        // Formatiere die nächste Instruktion
        let instructionText = '';
        
        if (next.opcodeName && next.opcodeName !== 'UNKNOWN') {
            instructionText = next.opcodeName;
            
            // Formatiere Argumente basierend auf ISA-Signatur und Molekül-Typen
            if (next.arguments && next.arguments.length > 0) {
                // Der Internal State ist nicht direkt verfügbar, aber wir können ihn aus dem AppController holen
                const internalState = this.getInternalStateFromAppController();
                const formattedArgs = this.formatArgumentsByType(next.arguments, next.argumentTypes, internalState);
                instructionText += ' ' + formattedArgs.join(' ');
            }
            
            // Füge Execution Status hinzu
            if (next.lastExecutionStatus) {
                const status = next.lastExecutionStatus.status;
                const reason = next.lastExecutionStatus.failureReason;
                
                if (status === 'FAILED') {
                    instructionText += ` <span class="injected-value error">[FAILED: ${reason || 'Unknown error'}]</span>`;
                } else if (status === 'CONFLICT_LOST') {
                    instructionText += ` <span class="injected-value warning">[CONFLICT_LOST]</span>`;
                }
            }
        } else {
            instructionText = 'No instruction available';
        }
        
        // Erstelle die Box im gleichen Layout wie Internal State
        el.innerHTML = `<div class="code-view" style="font-size:0.9em;">Next: ${instructionText}</div>`;
        
        // Speichere den aktuellen Zustand für den nächsten Vergleich
        this.previousState = { ...next };
    }
}

// Export für globale Verfügbarkeit
window.SidebarNextInstructionView = SidebarNextInstructionView;
