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
                // Breite: 8 Zeichen für perfekte Ausrichtung ohne Scroll
                formatted.push(String(value).padEnd(8));
            }

            return formatted.join('');
        };

        // Hilfsfunktion für DP-Formatierung mit dynamischer Anzahl (schmalere Breite)
        const formatDPs = (dps, previousDps, activeDpIndex) => {
            if (!dps || dps.length === 0) return '';

            const formatted = [];

            for (let i = 0; i < dps.length; i++) {
                let value = '';
                if (dps[i] && dps[i].length > 0) {
                    value = `${dps[i].join('|')}`;

                    // Setze aktiven DP in eckige Klammern
                    if (i === activeDpIndex) {
                        value = `[${value}]`;
                    }

                    // Prüfe, ob sich der DP-Wert geändert hat (nur bei "weiter" Navigation)
                    if (navigationDirection === 'forward' && previousDps && previousDps[i] && previousDps[i].length > 0) {
                        const previousValue = `${previousDps[i].join('|')}`;
                        if (value !== previousValue) {
                            // Markiere nur den einzelnen Wert mit .changed
                            value = `<div class="changed-field">${value}</div>`;
                        }
                    }
                }
                
                // Breite: 8 Zeichen für perfekte Ausrichtung ohne Scroll
                formatted.push(value.padEnd(8));
                
                // Füge Abstand zwischen allen DPs hinzu: DP0 in Spalte 1, DP1 in Spalte 3, DP2 in Spalte 5, etc.
                if (i < dps.length - 1) {
                    // Nach jedem DP: 1 zusätzliche Spalte (8 Zeichen) für Spalten 1, 3, 5, 7, ...
                    formatted.push(' '.repeat(8));
                }
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
                displayStack = formattedStack.slice(0, maxColumns - 1); // -1 für "(+X)"
                const remainingCount = formattedStack.length - (maxColumns - 1);
                displayStack.push(`(+${remainingCount})`); // Zeige Anzahl der nicht angezeigten Einträge
            }

            // Formatiere jeden Wert mit fester Breite (8 Zeichen wie bei Registern)
            const formattedValues = displayStack.map(value => {
                return String(value).padEnd(8);
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
                            // Rekursive FPR-Auflösung für diesen Parameter
                            // WICHTIG: param.drId ist eigentlich die FPR-ID (2000, 2001, etc.)
                            const finalRegisterId = resolveFprBinding(param.drId, callStack, index);

                            // Bestimme Register-Typ und Index
                            let registerDisplay;
                            if (finalRegisterId >= 2000) {
                                registerDisplay = `%FPR${finalRegisterId - 2000}`;
                            } else if (finalRegisterId >= 1000) {
                                registerDisplay = `%PR${finalRegisterId - 1000}`;
                            } else {
                                registerDisplay = `%DR${finalRegisterId}`;
                            }

                            // Hole den aktuellen Register-Wert aus dem Call Stack
                            let registerValue = param.value; // Fallback
                            if (finalRegisterId < 2000) { // Nur für DRs und PRs
                                const foundParam = findDrParameter(finalRegisterId, callStack);
                                if (foundParam) {
                                    registerValue = foundParam.value;
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
                    displayEntries = formattedEntries.slice(0, maxColumns - 1); // -1 für "(+X)"
                    const remainingCount = formattedEntries.length - (maxColumns - 1);
                    displayEntries.push(`(+${remainingCount})`); // Zeige Anzahl der nicht angezeigten Einträge
                }

                // Verteile auf Spalten statt mit -> verketten
                return displayEntries.map(entry => String(entry).padEnd(8)).join('');
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

        el.innerHTML = `<div class="code-view" style="font-size:0.9em;">DP:  ${formatDPs(state.dps, this.previousState?.dps, state.activeDpIndex)}\nDR:  ${formatRegisters(state.dataRegisters, this.previousState?.dataRegisters, true)}\nPR:  ${formatRegisters(state.procRegisters, this.previousState?.procRegisters, true)}\nFPR: ${formatRegisters(state.fpRegisters, this.previousState?.fpRegisters, true)}\nLR:  ${formatRegisters(state.locationRegisters, this.previousState?.locationRegisters, true)}\n${dataStackChanged ? '<div class="changed-line">DS:  ' : 'DS:  '}${formatStack(state.dataStack, this.previousState?.dataStack, state.dataRegisters?.length || 8, true)}${dataStackChanged ? '</div>' : ''}\n${locationStackChanged ? '<div class="changed-line">LS:  ' : 'LS:  '}${formatStack(state.locationStack, this.previousState?.locationStack, state.dataRegisters?.length || 8, true)}${locationStackChanged ? '</div>' : ''}\n${callStackChanged ? '<div class="changed-line">CS:  ' : 'CS:  '}${formatCallStack(state.callStack, this.previousState?.callStack)}${callStackChanged ? '</div>' : ''}</div>`;

        // Speichere den aktuellen Zustand für den nächsten Vergleich
        this.previousState = { ...state };
    }
}

// Export für globale Verfügbarkeit
window.SidebarStateView = SidebarStateView;
