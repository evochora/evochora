document.addEventListener('DOMContentLoaded', () => {
    window.EvoDebugger = window.EvoDebugger || {};

    class StatusManager {
        constructor() {
            this.statusBar = document.getElementById('status-bar');
            this.statusMessage = document.getElementById('status-message');
            this.statusClose = document.getElementById('status-close');
            
            this.statusClose.addEventListener('click', () => this.hideStatus());
        }
        
        showError(message, duration = 0) {
            this.showStatus(message, 'error', duration);
        }
        
        showInfo(message, duration = 5000) {
            this.showStatus(message, 'info', duration);
        }
        
        showStatus(message, type = 'info', duration = 0) {
            this.statusMessage.textContent = message;
            this.statusBar.className = `status-bar ${type}`;
            this.statusBar.style.display = 'flex';
            
            if (duration > 0) {
                setTimeout(() => this.hideStatus(), duration);
            }
        }
        
        hideStatus() {
            this.statusBar.style.display = 'none';
        }
    }

    class ApiService {
        constructor(statusManager) {
            this.statusManager = statusManager;
        }
        
        async fetchTickData(tick) {
            try {
                const res = await fetch(`/api/tick/${tick}`);
                
                if (!res.ok) {
                    if (res.status === 404) {
                        const errorData = await res.json().catch(() => ({}));
                        if (errorData.error) {
                            this.statusManager.showError(errorData.error);
                        } else {
                            this.statusManager.showError(`Tick ${tick} nicht gefunden`);
                        }
                    } else {
                        this.statusManager.showError(`HTTP Fehler: ${res.status}`);
                    }
                    throw new Error(`HTTP ${res.status}`);
                }
                
                const data = await res.json();
                
                // Validiere die Antwort-Struktur
                if (!this.validateTickData(data)) {
                    this.statusManager.showError('Unerwartetes Datenformat vom Server');
                    throw new Error('Invalid data format');
                }
                
                return data;
                
            } catch (error) {
                // Spezifische Fehlerbehandlung
                if (error.name === 'TypeError' && error.message.includes('fetch')) {
                    // Netzwerkfehler - Server nicht erreichbar
                    this.statusManager.showError('Debug Server nicht erreichbar - ist er gestartet?');
                } else if (error.message === 'Invalid data format') {
                    // Bereits behandelt
                } else if (error.message.includes('HTTP')) {
                    // HTTP-Fehler bereits behandelt
                } else {
                    // Unerwarteter Fehler
                    this.statusManager.showError(`Unerwarteter Fehler: ${error.message}`);
                }
                throw error;
            }
        }
        
        /**
         * Validiert die Struktur der Tick-Daten vom Server
         */
        validateTickData(data) {
            try {
                // Grundlegende Struktur prüfen
                if (!data || typeof data !== 'object') {
                    return false;
                }
                
                // Erwartete Felder prüfen
                if (typeof data.tickNumber !== 'number') {
                    return false;
                }
                
                if (!data.worldMeta || !Array.isArray(data.worldMeta.shape)) {
                    return false;
                }
                
                if (!data.worldState || !Array.isArray(data.worldState.cells) || !Array.isArray(data.worldState.organisms)) {
                    return false;
                }
                
                if (!data.organismDetails || typeof data.organismDetails !== 'object') {
                    return false;
                }
                
                return true;
                
            } catch (error) {
                console.error('Error validating tick data:', error);
                return false;
            }
        }
    }

    class SidebarBasicInfoView {
        constructor(root) { 
            this.root = root;
            this.previousState = null;
        }
        
        update(info, navigationDirection) {
            const el = this.root.querySelector('[data-section="basic"]');
            if (!info || !el) return;
            
            // Berechne Änderungen nur bei "forward" Navigation
            const changeFlags = this.calculateChanges(info, navigationDirection);
            
            // Unveränderliche Infos über der Box
            const unchangeableInfo = [
                `<div class="unchangeable-info-item"><span class="unchangeable-info-label">ID:</span><span class="unchangeable-info-value">${info.id}</span></div>`,
                `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Parent:</span><span class="unchangeable-info-value">${info.parentId && info.parentId !== 'null' && info.parentId !== 'undefined' ? `<span class="clickable-parent" data-parent-id="${info.parentId}">${info.parentId}</span>` : 'N/A'}</span></div>`,
                `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Birth:</span><span class="unchangeable-info-value">${info.birthTick}</span></div>`,
                `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Program:</span><span class="unchangeable-info-value">${info.programId || 'N/A'}</span></div>`
            ].join('');
            
            // Veränderliche Werte in der Box
            const changeableValues = [
                `IP=[${info.ip.join('|')}]`,
                `DV=[${info.dv.join('|')}]`,
                `ER=${info.energy}`
            ].map((value, index) => {
                const key = ['ip', 'dv', 'energy'][index];
                const isChanged = changeFlags && changeFlags[key];
                return isChanged ? `<span class="changed">${value}</span>` : value;
            }).join(' ');
            
            el.innerHTML = `
                <div class="unchangeable-info">${unchangeableInfo}</div>
                <div class="code-view changeable-box">${changeableValues}</div>
            `;
            
            // Event Listener für klickbare Parent-ID
            const parentSpan = el.querySelector('.clickable-parent');
            if (parentSpan) {
                parentSpan.addEventListener('click', () => {
                    const parentId = parentSpan.dataset.parentId;
                    // Navigiere zum Birth-Tick des Parents
                    this.navigateToParent(parentId);
                });
            }
            
            // Speichere aktuellen Zustand für nächsten Vergleich
            this.previousState = { ...info };
        }
        
        calculateChanges(currentInfo, navigationDirection) {
            // Nur bei "forward" Navigation Änderungen hervorheben
            if (navigationDirection !== 'forward' || !this.previousState) {
                return null;
            }
            
            const changeFlags = {};
            changeFlags.energy = currentInfo.energy !== this.previousState.energy;
            changeFlags.ip = JSON.stringify(currentInfo.ip) !== JSON.stringify(this.previousState.ip);
            changeFlags.dv = JSON.stringify(currentInfo.dv) !== JSON.stringify(this.previousState.dv);
            
            return changeFlags;
        }
        
        navigateToParent(parentId) {
            // Finde den Birth-Tick des Parents
            if (this.appController && this.appController.state.lastTickData) {
                const organisms = this.appController.state.lastTickData.worldState?.organisms || [];
                const parent = organisms.find(o => String(o.id) === parentId);
                if (parent && parent.birthTick !== undefined) {
                    this.appController.navigateToTick(parent.birthTick);
                }
            }
        }
    }

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
                    const formattedArgs = next.arguments.map((arg, index) => {
                        let argText = String(arg);
                        let argType = next.argumentTypes && next.argumentTypes[index] ? next.argumentTypes[index] : 'UNKNOWN';
                        
                        // Formatiere basierend auf ISA-Typ
                        if (argType === 'REGISTER') {
                            // Register: Extrahiere den Wert aus "DATA:0" und formatiere als %DR0
                            let value = this.extractValueFromMolecule(argText);
                            let registerName = this.formatRegisterName(value);
                            return `<span class="injected-value register">${registerName}</span>`;
                        } else if (argType === 'LITERAL') {
                            // Literal: Zeige den Molekül-Typ direkt (z.B. "DATA:1")
                            return `<span class="injected-value literal">${argText}</span>`;
                        } else if (argType === 'VECTOR') {
                            // Vector: Zeige den Molekül-Typ direkt
                            return `<span class="injected-value vector">${argText}</span>`;
                        } else if (argType === 'LABEL') {
                            // Label: Zeige den Molekül-Typ direkt
                            return `<span class="injected-value label">${argText}</span>`;
                        } else {
                            // Unbekannter Typ: Zeige den Molekül-Typ direkt
                            return `<span class="injected-value">${argText}</span>`;
                        }
                    });
                    
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

    class SidebarStateView {
        constructor(root) {
            this.root = root;
            this.previousState = null;
        }

        update(state, navigationDirection) {
            const el = this.root.querySelector('[data-section="state"]');
            if (!state || !el) return;

            // Hilfsfunktion für Register-Formatierung mit dynamischer Anzahl (schmalere Breite)
            const formatRegisters = (registers, previousRegisters, removeBrackets = false) => {
                if (!registers || registers.length === 0) return '';

                const formatted = [];

                for (let i = 0; i < registers.length; i++) {
                    let value = '';
                    if (registers[i] && registers[i].value !== undefined) {
                        // RegisterValue Objekt hat eine 'value' Property
                        value = registers[i].value || '';
                        // Typen abkürzen: CODE: -> C:, DATA: -> D:, ENERGY: -> E:, STRUCTURE: -> S:
                        value = value.replace(/CODE:/g, 'C:').replace(/DATA:/g, 'D:').replace(/ENERGY:/g, 'E:').replace(/STRUCTURE:/g, 'S:');

                        // Entferne eckige Klammern falls gewünscht (für LR)
                        if (removeBrackets) {
                            value = value.replace(/^\[|\]$/g, '');
                        }

                        // Prüfe, ob sich der Wert geändert hat (nur bei "weiter" Navigation)
                        if (navigationDirection === 'forward' && previousRegisters && previousRegisters[i] && previousRegisters[i].value !== undefined) {
                            let previousValue = previousRegisters[i].value || '';
                            previousValue = previousValue.replace(/CODE:/g, 'C:').replace(/DATA:/g, 'D:').replace(/ENERGY:/g, 'E:').replace(/STRUCTURE:/g, 'S:');
                            if (removeBrackets) {
                                previousValue = previousValue.replace(/^\[|\]$/g, '');
                            }
                            if (value !== previousValue) {
                                // Markiere nur den einzelnen Wert mit .changed
                                value = `<div class="changed-field">${value}</div>`;
                            }
                        }
                    }
                    // Breite: 7 Zeichen für perfekte Ausrichtung ohne Scroll
                    formatted.push(String(value).padEnd(7));
                }

                return formatted.join('');
            };

            // Hilfsfunktion für DP-Formatierung mit dynamischer Anzahl (schmalere Breite)
            const formatDPs = (dps, previousDps) => {
                if (!dps || dps.length === 0) return '';

                const formatted = [];

                for (let i = 0; i < dps.length; i++) {
                    let value = '';
                    if (dps[i] && dps[i].length > 0) {
                        value = `${dps[i].join('|')}`;

                        // Prüfe, ob sich der DP-Wert geändert hat (nur bei "weiter" Navigation)
                        if (navigationDirection === 'forward' && previousDps && previousDps[i] && previousDps[i].length > 0) {
                            const previousValue = `${previousDps[i].join('|')}`;
                            if (value !== previousValue) {
                                // Markiere nur den einzelnen Wert mit .changed
                                value = `<div class="changed-field">${value}</div>`;
                            }
                        }
                    }
                    // Breite: 7 Zeichen für perfekte Ausrichtung ohne Scroll
                    formatted.push(value.padEnd(7));
                }

                return formatted.join('');
            };

            // Hilfsfunktion für Stack-Formatierung
            const formatStack = (stack, previousStack, maxColumns = 8, removeBrackets = false) => {
                if (!stack || stack.length === 0) return '';

                // Typen in allen Stack-Werten abkürzen
                const formattedStack = stack.map(value => {
                    let formattedValue = value.replace(/CODE:/g, 'C:').replace(/DATA:/g, 'D:').replace(/ENERGY:/g, 'E:').replace(/STRUCTURE:/g, 'S:');

                    // Entferne eckige Klammern falls gewünscht (für LS und LR)
                    if (removeBrackets) {
                        formattedValue = formattedValue.replace(/^\[|\]$/g, '');
                    }

                    return formattedValue;
                });

                // Begrenze auf maxColumns Werte
                let displayStack = formattedStack;
                if (formattedStack.length > maxColumns) {
                    displayStack = formattedStack.slice(0, maxColumns - 1); // -1 für "..."
                    displayStack.push('...'); // Zeige an, dass es mehr gibt
                }

                // Formatiere jeden Wert mit fester Breite (7 Zeichen wie bei Registern)
                const formattedValues = displayStack.map(value => {
                    return String(value).padEnd(7);
                });

                return formattedValues.join('');
            };

            /**
             * Sucht den gesamten Call-Stack nach einem Parameter mit der angegebenen DR-ID.
             */
            const findDrParameter = (drId, callStack) => {
                for (const frame of callStack) {
                    const foundParam = frame.parameters.find(p => p.drId === drId);
                    if (foundParam) {
                        return foundParam;
                    }
                }
                return null; // Parameter nicht gefunden
            };

            // Rekursive FPR-Auflösung durch den Call Stack
            const resolveFprBinding = (fprId, callStack, startIndex) => {
                let currentId = fprId;
                // Iteriere durch den Call-Stack, beginnend beim aktuellen Frame.
                // WICHTIG: Wir müssen von UNTEN nach OBEN durchsuchen (ältere Frames zuerst)
                for (let i = callStack.length - 1; i >= startIndex; i--) {
                    const frame = callStack[i];
                    if (frame.fprBindings && frame.fprBindings.hasOwnProperty(currentId)) {
                        const boundRegister = frame.fprBindings[currentId];
                        if (boundRegister < 2000) {
                            // Aufgelöst zu einem DR/PR (Register < 2000), wir sind fertig.
                            return boundRegister;
                        }
                        // Es ist ein anderes FPR (>= 2000), also aktualisieren wir die ID,
                        // nach der wir im nächsten Frame suchen.
                        currentId = boundRegister;
                    } else {
                        // Wenn in einem Frame keine Bindung gefunden wird, ist die Kette hier zu Ende.
                        break;
                    }
                }
                // Wenn wir am Ende des Stacks ankommen und immer noch ein FPR haben, geben wir es zurück.
                return currentId;
            };

            // Call Stack spezielle Behandlung - jetzt mit strukturierten Daten
            const formatCallStack = (callStack, previousCallStack) => {
                if (!callStack || callStack.length === 0) return '';

                // Prüfe, ob wir Prozedurnamen haben (ProgramArtifact verfügbar)
                const hasProcNames = callStack.some(entry => entry.procName && entry.procName.trim() !== '');

                if (hasProcNames) {
                    // Mit ProgramArtifact: Eine Zeile pro Eintrag mit Prozedurnamen
                    const formattedCallStack = callStack.map((entry, index) => {
                        let result = entry.procName || 'UNKNOWN';

                        // Return-Koordinaten hinzufügen: [x|y] mit .injected-value Styling
                        if (entry.returnCoordinates && entry.returnCoordinates.length >= 2) {
                            result += ` <span class="injected-value">[${entry.returnCoordinates[0]}|${entry.returnCoordinates[1]}]</span>`;
                        }

                        // Parameter hinzufügen
                        if (entry.parameters && entry.parameters.length > 0) {
                            result += ' WITH ';

                            const paramStrings = entry.parameters.map(param => {
                                console.log(`DEBUG: Processing parameter:`, param);

                                // Rekursive FPR-Auflösung für diesen Parameter
                                // WICHTIG: param.drId ist eigentlich die FPR-ID (2000, 2001, etc.)
                                const finalRegisterId = resolveFprBinding(param.drId, callStack, index);
                                console.log(`DEBUG: Final register ID for ${param.drId}: ${finalRegisterId}`);
                                console.log(`DEBUG: finalRegisterId type: ${typeof finalRegisterId}, value: ${finalRegisterId}`);

                                // Bestimme Register-Typ und Index
                                let registerDisplay;
                                if (finalRegisterId >= 2000) {
                                    registerDisplay = `%FPR${finalRegisterId - 2000}`;
                                    console.log(`DEBUG: FPR branch: ${finalRegisterId} >= 2000`);
                                } else if (finalRegisterId >= 1000) {
                                    registerDisplay = `%PR${finalRegisterId - 1000}`;
                                    console.log(`DEBUG: PR branch: ${finalRegisterId} >= 1000`);
                                } else {
                                    registerDisplay = `%DR${finalRegisterId}`;
                                    console.log(`DEBUG: DR branch: ${finalRegisterId} < 1000`);
                                }
                                console.log(`DEBUG: Register display: ${registerDisplay}`);

                                // Hole den aktuellen Register-Wert aus dem Call Stack
                                let registerValue = param.value; // Fallback
                                if (finalRegisterId < 2000) { // Nur für DRs und PRs
                                    const foundParam = findDrParameter(finalRegisterId, callStack);
                                    if (foundParam) {
                                        registerValue = foundParam.value;
                                        console.log(`DEBUG: Found parameter value for DR${finalRegisterId}: ${registerValue}`);
                                    }
                                }
                                
                                // Typen in Kurzform anzeigen (wie bei anderen Stacks)
                                if (registerValue) {
                                    registerValue = registerValue.replace(/CODE:/g, 'C:').replace(/DATA:/g, 'D:').replace(/ENERGY:/g, 'E:').replace(/STRUCTURE:/g, 'S:');
                                }

                                if (param.paramName) {
                                    // Mit ProgramArtifact: PARAM1<span class="injected-value">[%DR1=D:3]</span>
                                    return `${param.paramName}<span class="injected-value">[${registerDisplay}=${registerValue}]</span>`;
                                } else {
                                    // Ohne ProgramArtifact: %DR1<span class="injected-value">[=D:3]</span>
                                    return `${registerDisplay}<span class="injected-value">[=${registerValue}]</span>`;
                                }
                            });
                            result += paramStrings.join(' ');
                        }

                        // Erste Zeile: keine Einrückung, weitere Zeilen: Einrückung
                        if (index === 0) {
                            return result;
                        } else {
                            // Einrückung: 5 Leerzeichen (entspricht "CS:  ")
                            return '     ' + result;
                        }
                    });

                    return formattedCallStack.join('\n');
                } else {
                    // Ohne ProgramArtifact: Alle Einträge in einer Zeile wie andere Stacks
                    const formattedEntries = callStack.map(entry => {
                        if (entry.returnCoordinates && entry.returnCoordinates.length >= 2) {
                            // Ohne ProgramArtifact: normale Darstellung, keine grünen Klammern
                            return `${entry.returnCoordinates[0]}|${entry.returnCoordinates[1]}`;
                        }
                        return '';
                    }).filter(entry => entry !== '');

                    // Dynamische Abkürzung wie bei anderen Stacks (basierend auf DR-Anzahl)
                    const maxColumns = 8; // Standard, könnte dynamisch sein
                    let displayEntries = formattedEntries;
                    if (formattedEntries.length > maxColumns) {
                        displayEntries = formattedEntries.slice(0, maxColumns - 1); // -1 für "..."
                        displayEntries.push('...'); // Zeige an, dass es mehr gibt
                    }

                    // Verteile auf Spalten statt mit -> verketten
                    return displayEntries.map(entry => String(entry).padEnd(7)).join('');
                }
            };

            // Verwende die ursprüngliche .code-view Struktur für perfekte Zeilenhöhe
            // Alle Labels haben die gleiche Breite für perfekte Spaltenausrichtung

            // Prüfe, ob sich die Stacks geändert haben
            const dataStackChanged = navigationDirection === 'forward' &&
                (state.dataStack?.join(' ') !== (this.previousState?.dataStack?.join(' ') || ''));
            const locationStackChanged = navigationDirection === 'forward' &&
                (state.locationStack?.join(' ') !== (this.previousState?.locationStack?.join(' ') || ''));
            const callStackChanged = navigationDirection === 'forward' &&
                (JSON.stringify(state.callStack) !== JSON.stringify(this.previousState?.callStack));

            el.innerHTML = `<div class="code-view" style="font-size:0.9em;">DP:  ${formatDPs(state.dps, this.previousState?.dps)}\nDR:  ${formatRegisters(state.dataRegisters, this.previousState?.dataRegisters)}\nPR:  ${formatRegisters(state.procRegisters, this.previousState?.procRegisters)}\nFPR: ${formatRegisters(state.fpRegisters, this.previousState?.fpRegisters)}\nLR:  ${formatRegisters(state.locationRegisters, this.previousState?.locationRegisters, true)}\n${dataStackChanged ? '<div class="changed-line">DS:  ' : 'DS:  '}${formatStack(state.dataStack, this.previousState?.dataStack, state.dataRegisters?.length || 8)}${dataStackChanged ? '</div>' : ''}\n${locationStackChanged ? '<div class="changed-line">LS:  ' : 'LS:  '}${formatStack(state.locationStack, this.previousState?.locationStack, state.dataRegisters?.length || 8, true)}${locationStackChanged ? '</div>' : ''}\n${callStackChanged ? '<div class="changed-line">CS:  ' : 'CS:  '}${formatCallStack(state.callStack, this.previousState?.callStack)}${callStackChanged ? '</div>' : ''}</div>`;

            // Speichere den aktuellen Zustand für den nächsten Vergleich
            this.previousState = { ...state };
        }
    }

    class SidebarSourceView {
        constructor(root) { this.root = root; }
        update(src) {
            const el = this.root.querySelector('[data-section="source"]');
            if (!el) return;
            if (!src) { el.innerHTML = ''; return; }
            const header = `//${src.fileName}`;
            const linesHtml = (src.lines||[]).map(l=>`<div class="source-line ${l.isCurrent? 'highlight':''}"><span class="line-number">${l.number}</span><pre data-line="${l.number}">${String(l.content||'').replace(/</g,'&lt;')}</pre></div>`).join('');
            el.innerHTML = `<div class="code-view source-code-view" id="source-code-view" style="font-size:0.9em;"><div class="source-line"><span class="line-number"></span><pre>${header}</pre></div>${linesHtml}</div>`;
            if (Array.isArray(src.inlineValues)) {
                const grouped = new Map();
                for (const s of src.inlineValues) {
                    if (!grouped.has(s.lineNumber)) grouped.set(s.lineNumber, []);
                    grouped.get(s.lineNumber).push(s);
                }
                for (const [ln, spans] of grouped.entries()) {
                    const pre = el.querySelector(`pre[data-line="${ln}"]`);
                    if (!pre) continue;
                    const raw = pre.textContent || '';
                    // Step 1: Earliest-only for jump/callJump of the same text
                    const earliestByTextForJump = new Map();
                    for (const sp of spans) {
                        if (sp && (sp.kind === 'jump' || sp.kind === 'callJump') && typeof sp.text === 'string') {
                            const t = sp.text;
                            const cur = earliestByTextForJump.get(t);
                            if (!cur || (sp.startColumn || 0) < (cur.startColumn || 0)) {
                                earliestByTextForJump.set(t, sp);
                            }
                        }
                    }
                    // Step 2: Build list, skipping later duplicates of jump/callJump for same text
                    const seenByPosText = new Set();
                    const uniq = [];
                    for (const sp of spans) {
                        if (sp && (sp.kind === 'jump' || sp.kind === 'callJump')) {
                            const keep = earliestByTextForJump.get(sp.text);
                            if (keep !== sp) continue;
                        }
                        const key = `${sp.startColumn}|${sp.text}`;
                        if (seenByPosText.has(key)) continue;
                        seenByPosText.add(key);
                        uniq.push(sp);
                    }
                    uniq.sort((a,b)=>a.startColumn - b.startColumn);
                    let out = '';
                    let cur = 0;
                    for (const s of uniq) {
                        const idx = Math.max(0, Math.min(raw.length, (s.startColumn||1) - 1));
                        out += raw.slice(cur, idx).replace(/</g,'&lt;');
                        const cls = s.kind ? ` injected-value ${s.kind}` : ' injected-value';
                        const needsBracket = (s.kind === 'reg' || s.kind === 'define' || s.kind === 'jump' || s.kind === 'callJump');
                        const alreadyBracketed = typeof s.text === 'string' && s.text.startsWith('[') && s.text.endsWith(']');
                        const display = needsBracket && !alreadyBracketed ? `[${s.text}]` : s.text;
                        out += `<span class="${cls}">${String(display||'').replace(/</g,'&lt;')}</span>`;
                        cur = idx;
                    }
                    out += raw.slice(cur).replace(/</g,'&lt;');
                    pre.innerHTML = out;
                }
            }
            const container = el.querySelector('#source-code-view');
            const highlighted = container ? container.querySelector('.source-line.highlight') : null;
            if (container && highlighted) {
                try { highlighted.scrollIntoView({ block: 'center' }); } catch {}
                const top = highlighted.offsetTop - (container.clientHeight / 2) + (highlighted.clientHeight / 2);
                container.scrollTop = Math.max(0, Math.min(top, container.scrollHeight - container.clientHeight));
            }
        }
    }

    class SidebarView {
        constructor(root, appController) {
            this.root = root;
            this.appController = appController;
            this.basic = new SidebarBasicInfoView(root);
            this.basic.appController = appController; // Referenz für Parent-Navigation
            this.next = new SidebarNextInstructionView(root);
            this.state = new SidebarStateView(root);
            this.source = new SidebarSourceView(root);
        }
        update(details, navigationDirection) {
            if (!details) return;
            this.basic.update(details.basicInfo, navigationDirection);
            this.next.update(details.nextInstruction, navigationDirection);
            this.state.update(details.internalState, navigationDirection);
            this.source.update(details.sourceView);
        }
    }

    class SidebarManager {
        constructor() {
            this.sidebar = document.getElementById('sidebar');
            this.toggleBtn = document.getElementById('sidebar-toggle');
            this.isVisible = false;
            
            // Toggle button event listener
            this.toggleBtn.addEventListener('click', () => {
                this.toggleSidebar();
            });
        }
        
        showSidebar() {
            this.sidebar.classList.add('visible');
            this.isVisible = true;
        }
        
        hideSidebar() {
            this.sidebar.classList.remove('visible');
            this.isVisible = false;
        }
        
        toggleSidebar() {
            if (this.isVisible) {
                this.hideSidebar();
            } else {
                this.showSidebar();
            }
        }
        
        setToggleButtonVisible(visible) {
            this.toggleBtn.style.display = visible ? 'block' : 'none';
        }
        
        // Auto-hide when no organism is selected
        autoHide() {
            this.hideSidebar();
        }
        
        // Auto-show when organism is selected
        autoShow() {
            this.showSidebar();
        }
    }

    class ToolbarView {
        constructor(controller) {
            this.controller = controller;
            
            // Button event listeners
            document.getElementById('btn-prev').addEventListener('click', () => this.controller.navigateToTick(this.controller.state.currentTick - 1));
            document.getElementById('btn-next').addEventListener('click', () => this.controller.navigateToTick(this.controller.state.currentTick + 1));
            
            const input = document.getElementById('tick-input');
            document.getElementById('btn-goto').addEventListener('click', () => {
                const v = parseInt(input.value, 10);
                if (!Number.isNaN(v)) this.controller.navigateToTick(v);
            });
            
            // Input field event listeners
            input.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    const v = parseInt(input.value, 10);
                    if (!Number.isNaN(v)) this.controller.navigateToTick(v);
                }
            });
            input.addEventListener('change', () => {
                const v = parseInt(input.value, 10);
                if (!Number.isNaN(v)) this.controller.navigateToTick(v);
            });
            
            // Input field click - select all text
            input.addEventListener('click', () => {
                input.select();
            });
            
            // Keyboard shortcuts
            document.addEventListener('keydown', (e) => {
                // Only handle shortcuts when not typing in input field
                if (document.activeElement === input) return;
                
                if (e.key === ' ') {
                    e.preventDefault(); // Prevent page scroll
                    this.controller.navigateToTick(this.controller.state.currentTick + 1);
                } else if (e.key === 'Backspace') {
                    e.preventDefault(); // Prevent browser back
                    this.controller.navigateToTick(this.controller.state.currentTick - 1);
                }
            });
        }
    }

    class AppController {
        constructor() {
            this.statusManager = new StatusManager();
            this.api = new ApiService(this.statusManager);
            this.canvas = document.getElementById('worldCanvas');
            this.renderer = new WorldRenderer(this.canvas, { WORLD_SHAPE: [100,30], CELL_SIZE: 22, TYPE_CODE:0, TYPE_DATA:1, TYPE_ENERGY:2, TYPE_ENERGY:2, TYPE_STRUCTURE:3, COLOR_BG:'#0a0a14', COLOR_EMPTY_BG:'#14141e', COLOR_CODE_BG:'#3c5078', COLOR_DATA_BG:'#32323c', COLOR_STRUCTURE_BG:'#ff7878', COLOR_ENERGY_BG:'#ffe664', COLOR_CODE_TEXT:'#ffffff', COLOR_DATA_TEXT:'#ffffff', COLOR_STRUCTURE_TEXT:'#323232', COLOR_ENERGY_TEXT:'#323232', COLOR_DEAD:'#505050' }, {});
            this.sidebar = new SidebarView(document.getElementById('sidebar'), this);
            this.sidebarManager = new SidebarManager();
            this.toolbar = new ToolbarView(this);
            this.state = { currentTick: 0, selectedOrganismId: null, lastTickData: null, totalTicks: null };
            this.canvas.addEventListener('click', (e) => this.onCanvasClick(e));
            
                    // Tracking für Navigationsrichtung (für Änderungs-Hervorhebung)
        this.lastNavigationDirection = null; // 'forward', 'backward', 'goto'
        
        // Referenz auf den AppController für Parent-Navigation
        this.appController = null;
        }
        async init() { await this.navigateToTick(0); }
        async navigateToTick(tick) {
            let target = typeof tick === 'number' ? tick : 0;
            if (target < 0) target = 0;
            if (typeof this.state.totalTicks === 'number' && this.state.totalTicks > 0) {
                const maxTick = Math.max(0, this.state.totalTicks - 1);
                if (target > maxTick) target = maxTick;
            }
            
            // Bestimme die Navigationsrichtung für Änderungs-Hervorhebung
            if (target === this.state.currentTick + 1) {
                this.lastNavigationDirection = 'forward';
            } else if (target === this.state.currentTick - 1) {
                this.lastNavigationDirection = 'backward';
            } else {
                this.lastNavigationDirection = 'goto';
            }
            
            try {
            const data = await this.api.fetchTickData(target);
            this.state.currentTick = target;
            this.state.lastTickData = data;
            if (typeof data.totalTicks === 'number') {
                this.state.totalTicks = data.totalTicks;
            }
            if (data.worldMeta && Array.isArray(data.worldMeta.shape)) {
                this.renderer.config.WORLD_SHAPE = data.worldMeta.shape;
            }
            // ISA-Mapping is intentionally not used; rely solely on cell.opcodeName provided by backend
            const typeToId = t => ({ CODE:0, DATA:1, ENERGY:2, STRUCTURE:3 })[t] ?? 1;
            const cells = (data.worldState?.cells||[]).map(c => ({ position: JSON.stringify(c.position), type: typeToId(c.type), value: c.value, opcodeName: c.opcodeName }));
            const organisms = (data.worldState?.organisms||[]).map(o => ({ organismId: o.id, programId: o.programId, energy: o.energy, positionJson: JSON.stringify(o.position), dps: o.dps, dv: o.dv }));
            this.renderer.draw({ cells, organisms, selectedOrganismId: this.state.selectedOrganismId });
            const ids = Object.keys(data.organismDetails||{});
            const sel = this.state.selectedOrganismId && ids.includes(this.state.selectedOrganismId) ? this.state.selectedOrganismId : null;
            if (sel) {
                    this.sidebar.update(data.organismDetails[sel], this.lastNavigationDirection);
                    this.sidebarManager.autoShow();
                    this.sidebarManager.setToggleButtonVisible(true);
            } else {
                    // No organism selected - auto-hide sidebar
                    this.sidebarManager.autoHide();
                    this.sidebarManager.setToggleButtonVisible(false);
            }
            this.updateTickUi();
            } catch (error) {
                // Error is already displayed by ApiService
                console.error('Failed to navigate to tick:', error);
            }
        }
        updateTickUi() {
            const input = document.getElementById('tick-input');
            if (input) input.value = String(this.state.currentTick || 0);
            const suffix = document.getElementById('tick-total-suffix');
            if (suffix) suffix.textContent = '/' + (this.state.totalTicks != null ? this.state.totalTicks : 'N/A');
            if (input && typeof this.state.totalTicks === 'number' && this.state.totalTicks > 0) {
                try { input.max = String(Math.max(0, this.state.totalTicks - 1)); } catch (_) {}
            }
        }
        onCanvasClick(event) {
            const rect = this.canvas.getBoundingClientRect();
            const x = event.clientX - rect.left;
            const y = event.clientY - rect.top;
            const gridX = Math.floor(x / this.renderer.config.CELL_SIZE);
            const gridY = Math.floor(y / this.renderer.config.CELL_SIZE);
            const organisms = (this.state.lastTickData?.worldState?.organisms)||[];
            for (const o of organisms) {
                const pos = o.position;
                if (Array.isArray(pos) && pos[0] === gridX && pos[1] === gridY) {
                    this.state.selectedOrganismId = String(o.id);
                    const det = this.state.lastTickData.organismDetails?.[this.state.selectedOrganismId];
                                    if (det) {
                    this.sidebar.update(det, this.lastNavigationDirection);
                }
                this.sidebarManager.autoShow();
                this.sidebarManager.setToggleButtonVisible(true);
                    const typeToId = t => ({ CODE:0, DATA:1, ENERGY:2, STRUCTURE:3 })[t] ?? 1;
                    this.renderer.draw({
                        cells: (this.state.lastTickData.worldState?.cells||[]).map(c=>({position: JSON.stringify(c.position), type: typeToId(c.type), value: c.value, opcodeName: c.opcodeName })),
                        organisms: (organisms||[]).map(o2=>({ organismId: o2.id, programId: o2.programId, energy: o2.energy, positionJson: JSON.stringify(o2.position), dps: o2.dps, dv: o2.dv })),
                        selectedOrganismId: o.id
                    });
                    return;
                }
            }
        }
        

    }

    window.EvoDebugger.ApiService = ApiService;
    window.EvoDebugger.SidebarView = SidebarView;
    window.EvoDebugger.ToolbarView = ToolbarView;
    window.EvoDebugger.AppController = AppController;
    window.EvoDebugger.controller = new AppController();
    // Auto-load first tick
    window.EvoDebugger.controller.init().catch(console.error);
});


