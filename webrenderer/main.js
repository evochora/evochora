document.addEventListener('DOMContentLoaded', async () => {

    const fileInput = document.getElementById('db-upload');
    const sidebar = document.getElementById('sidebar');
    const closeSidebarBtn = document.getElementById('close-sidebar');
    const detailsContent = document.getElementById('details-content');
    const currentTickSpan = document.getElementById('current-tick');
    const totalTicksSpan = document.getElementById('total-ticks');
    const tickInput = document.getElementById('tick-input');
    const canvas = document.getElementById('worldCanvas');
    const btnFirst = document.getElementById('btn-first'), btnPrev = document.getElementById('btn-prev'), btnGoto = document.getElementById('btn-goto'), btnNext = document.getElementById('btn-next'), btnLast = document.getElementById('btn-last');

    let db = null;
    let minTick = 0, maxTick = 0, currentTick = 0;
    let selectedOrganismId = null;
    let lastWorldState = {};
    let programArtifacts = new Map();
    let renderer = null;
    let simConfig = {}, simIsa = {};
    let runMode = 'performance';

    const SQL = await initSqlJs({ locateFile: file => `https://cdnjs.cloudflare.com/ajax/libs/sql.js/1.10.3/${file}` });

    function showSidebar() { sidebar.classList.add('visible'); }
    function hideSidebar() { sidebar.classList.remove('visible'); }
    closeSidebarBtn.addEventListener('click', hideSidebar);

    fileInput.addEventListener('change', (event) => {
        const file = event.target.files[0];
        if (!file) return;
        const reader = new FileReader();
        reader.onload = (e) => {
            try {
                db = new SQL.Database(new Uint8Array(e.target.result));
                initializeSimulation();
            } catch (error) {
                alert("Fehler beim Öffnen der Datenbankdatei.");
                console.error("DB Öffnungsfehler:", error);
            }
        };
        reader.readAsArrayBuffer(file);
    });

    function initializeSimulation() {
        try {
            const metaStmt = db.prepare("SELECT key, value FROM simulation_metadata");
            while(metaStmt.step()) {
                const row = metaStmt.getAsObject();
                if (row.key === 'worldShape') simConfig.WORLD_SHAPE = JSON.parse(row.value);
                else if (row.key === 'isaMap') simIsa = JSON.parse(row.value);
                else if (row.key === 'runMode') runMode = row.value;
            }
            metaStmt.free();

            if (!simConfig.WORLD_SHAPE || Object.keys(simIsa).length === 0) throw new Error("Metadaten unvollständig.");

            simConfig = { ...simConfig, CELL_SIZE: 22, TYPE_CODE: 0, TYPE_DATA: 1, TYPE_ENERGY: 2, TYPE_STRUCTURE: 3, COLOR_BG: '#0a0a14', COLOR_EMPTY_BG: '#14141e', COLOR_CODE_BG: '#3c5078', COLOR_DATA_BG: '#32323c', COLOR_STRUCTURE_BG: '#ff7878', COLOR_ENERGY_BG: '#ffe664', COLOR_CODE_TEXT: '#ffffff', COLOR_DATA_TEXT: '#ffffff', COLOR_STRUCTURE_TEXT: '#323232', COLOR_ENERGY_TEXT: '#323232', COLOR_DEAD: '#505050' };
            renderer = new WorldRenderer(canvas, simConfig, simIsa);

            const artifactsResult = db.exec("SELECT programId, artifactJson FROM programs");
            if (artifactsResult.length > 0) {
                 artifactsResult[0].values.forEach(([id, json]) => {
                    try {
                        const artifact = JSON.parse(json);
                        programArtifacts.set(id, artifact);
                    }
                    catch (e) { console.warn(`Konnte Artefakt für Programm "${id}" nicht parsen.`); }
                });
            }

            const result = db.exec("SELECT MAX(tickNumber), MIN(tickNumber) FROM ticks");
            if (result.length > 0 && result[0].values.length > 0 && result[0].values[0][0] !== null) {
                maxTick = result[0].values[0][0];
                minTick = result[0].values[0][1] || 0;
                totalTicksSpan.textContent = maxTick;
                navigateToTick(minTick);
            } else {
                 alert("Datenbank geladen, aber sie scheint keine Ticks zu enthalten.");
            }
        } catch (error) {
            alert("Fehler beim Initialisieren der Simulation aus der Datenbank.");
            console.error("Fehler bei initializeSimulation:", error);
        }
    }

    function navigateToTick(tick) {
        if (!db || !renderer) return;
        const newTick = Math.max(minTick, Math.min(tick, maxTick));
        currentTick = newTick;
        tickInput.value = currentTick;
        currentTickSpan.textContent = currentTick;
        renderWorldForTick(currentTick);
        if (selectedOrganismId !== null) {
            updateSidebar();
        }
    }

    btnFirst.addEventListener('click', () => navigateToTick(minTick));
    btnPrev.addEventListener('click', () => navigateToTick(currentTick - 1));
    btnNext.addEventListener('click', () => navigateToTick(currentTick + 1));
    btnLast.addEventListener('click', () => navigateToTick(maxTick));
    btnGoto.addEventListener('click', () => navigateToTick(parseInt(tickInput.value, 10)));
    tickInput.addEventListener('change', () => navigateToTick(parseInt(tickInput.value, 10)));

    function renderWorldForTick(tick) {
        if (!db || !renderer) return;
        const cellsStmt = db.prepare("SELECT positionJson as position, type, value FROM cell_states WHERE tickNumber = :tick");
        cellsStmt.bind({ ':tick': tick });
        const cells = [];
        while(cellsStmt.step()) cells.push(cellsStmt.getAsObject());
        cellsStmt.free();

        const orgStmt = db.prepare("SELECT organismId, programId, energy, positionJson, dpJson, dvJson, disassembledInstructionJson, dataRegisters, procRegisters, dataStack, callStack, formalParameters FROM organism_states WHERE tickNumber = :tick");
        orgStmt.bind({ ':tick': tick });
        const organisms = [];
        while(orgStmt.step()) {
            const orgData = orgStmt.getAsObject();
            try {
                orgData.dataRegisters = JSON.parse(orgData.dataRegisters);
                orgData.procRegisters = JSON.parse(orgData.procRegisters);
                orgData.dataStack = JSON.parse(orgData.dataStack);
                orgData.callStack = JSON.parse(orgData.callStack);
                orgData.formalParameters = JSON.parse(orgData.formalParameters);
            } catch(e) { console.error("Fehler beim Parsen von Organismus-Daten:", e); }
            organisms.push(orgData);
        }
        orgStmt.free();
        lastWorldState = { cells, organisms, selectedOrganismId };
        renderer.draw(lastWorldState);
    }

    function updateSidebar() {
        if (selectedOrganismId === null || !lastWorldState.organisms) return;
        const org = lastWorldState.organisms.find(o => o.organismId === selectedOrganismId);
        if (!org) {
            detailsContent.innerHTML = `<p>Organismus #${selectedOrganismId} in diesem Tick nicht gefunden.</p>`;
            return;
        }

        const isPerfMode = (runMode === 'performance');
        const perfModeMsg = '<i style="color:#888;">(Performance-Modus)</i>';
        const artifact = programArtifacts.get(org.programId);

        let sourceCodeHtml = `<div class="code-view">${isPerfMode ? perfModeMsg : 'Kein Quellcode verfügbar.'}</div>`;
        let instructionHtml = isPerfMode ? perfModeMsg : 'N/A';

        // Hilfsfunktionen für Token-Ersatz im Quelltext
        const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const canonicalNameForRegId = (id) => {
            if (typeof id !== 'number') return '%?';
            if (id < 1000) return `%DR${id}`;
            if (id < 2000) return `%PR${id - 1000}`;
            return `%FPR${id - 2000}`;
        };
        const valueForRegId = (id) => {
            if (typeof id !== 'number') return 'N/A';
            if (id < 1000) return org.dataRegisters?.[id] ?? 'N/A';
            if (id < 2000) return org.procRegisters?.[id - 1000] ?? 'N/A';
            return 'N/A';
        };
        const annotateLineByAliases = (line, artifactObj) => {
            if (!artifactObj || !artifactObj.registerAliasMap) return line;
            if (line.includes('injected-value')) return line; // schon annotiert
            const matches = line.match(/%[A-Za-z0-9_]+/g);
            if (!matches) return line;
            let out = line;
            for (const alias of matches) {
                const regId = artifactObj.registerAliasMap[alias];
                if (regId == null) continue;
                // Wenn alias bereits mit einer injected-value Annotation versehen ist, auslassen
                const alreadyAnnotated = new RegExp(`${escapeRegExp(alias)}<span class=\\"injected-value\\"`).test(out);
                if (alreadyAnnotated) continue;
                const annotation = `<span class=\"injected-value\">[${canonicalNameForRegId(regId)}=${valueForRegId(regId)}]</span>`;
                const escaped = escapeRegExp(alias);
                const regex = new RegExp(`(^|[^A-Za-z0-9_])(${escaped})(?![^<]*>)(?![A-Za-z0-9_])`, 'g');
                out = out.replace(regex, `$1$2${annotation}`);
            }
            return out;
        };
        const annotateLineByFormalParams = (line, artifactObj, orgObj) => {
            if (!artifactObj || !artifactObj.procNameToParamNames || !orgObj || !Array.isArray(orgObj.callStack) || orgObj.callStack.length === 0) return line;
            if (line.includes('injected-value')) return line; // schon annotiert
            const currentProc = orgObj.callStack[orgObj.callStack.length - 1];
            if (!currentProc) return line;
            const paramNames = artifactObj.procNameToParamNames[currentProc.toUpperCase()];
            if (!Array.isArray(paramNames) || paramNames.length === 0) return line;
            let out = line;
            for (let i = 0; i < paramNames.length; i++) {
                const pName = paramNames[i];
                const escapedP = escapeRegExp(pName);
                // Wenn pName bereits annotiert ist, überspringen
                const alreadyAnnotated = new RegExp(`${escapedP}<span class=\\"injected-value\\"`).test(out);
                if (alreadyAnnotated) continue;
                let bracketStr = `[%FPR${i}=N/A]`;
                if (Array.isArray(orgObj.formalParameters) && orgObj.formalParameters[i]) {
                    const m = String(orgObj.formalParameters[i]).match(/\[(.*?)\]=(.*)$/);
                    if (m) {
                        bracketStr = `[${m[1]}=${m[2]}]`;
                    }
                }
                const regex = new RegExp(`(^|[^A-Za-z0-9_])(${escapedP})(?![^<]*>)(?![A-Za-z0-9_])`, 'g');
                out = out.replace(regex, `$1$2<span class=\"injected-value\">${bracketStr}</span>`);
            }
            return out;
        };

        // Basale Debug-Ausgaben
        console.debug('[WR] runMode:', runMode, 'isPerfMode:', isPerfMode, 'artifact?', !!artifact);

        // Für das Source-Rendering nicht am Performance-Modus festhalten – reine Browserarbeit
        if (artifact && artifact.sourceMap && artifact.sources) {
            console.debug('[WR] Artifact keys:', Object.keys(artifact));
            console.debug('[WR] registerAliasMap size:', artifact.registerAliasMap ? Object.keys(artifact.registerAliasMap).length : 0);
            console.debug('[WR] relativeCoordToLinearAddress keys sample:', artifact.relativeCoordToLinearAddress ? Object.keys(artifact.relativeCoordToLinearAddress).slice(0, 5) : []);
            const ip = JSON.parse(org.positionJson);
            console.debug('[WR] IP:', ip);
            // Ursprungskoordinate aus dem Artefakt bestimmen, falls vorhanden; fallback 0|0
            const originFromLinear = (artifact && artifact.linearAddressToCoord) ? (artifact.linearAddressToCoord["0"] || artifact.linearAddressToCoord[0]) : null;
            const originCoord = originFromLinear ? originFromLinear : (artifact && artifact.machineCodeLayout && (artifact.machineCodeLayout["0"] || artifact.machineCodeLayout[0]) ? (artifact.machineCodeLayout["0"] || artifact.machineCodeLayout[0]) : null);
            const origin = Array.isArray(originCoord) ? originCoord : [0,0];
            console.debug('[WR] originCoord:', originCoord, 'origin used:', origin);
            const relativeIp = ip.map((v, idx) => v - (origin[idx] || 0));
            const relativeIpKey = `${relativeIp[0]}|${relativeIp[1]}`;
            console.debug('[WR] relativeIpKey:', relativeIpKey);

            const linearAddress = artifact.relativeCoordToLinearAddress[relativeIpKey];
            console.debug('[WR] linearAddress:', linearAddress);
            const sourceInfo = artifact.sourceMap[linearAddress];
            console.debug('[WR] sourceInfo:', sourceInfo);

            if (sourceInfo && sourceInfo.fileName && artifact.sources) {
                const fileKey = artifact.sources[sourceInfo.fileName] ? sourceInfo.fileName : (sourceInfo.fileName.split(/[\\\/]/).pop());
                const sourceLines = artifact.sources[fileKey];
                const highlightedLine = sourceInfo.lineNumber;

                if (Array.isArray(sourceLines)) {
                    const absName = sourceInfo.fileName;
                    const baseName = absName.split(/[\\\/]/).pop();
                    const displayName = baseName === 'test.s' ? baseName : `./lib/${baseName}`;
                    // POP/PUSH-Heuristik: In Prozedurdateien ggf. Hervorhebung verschieben
                    let effectiveHighlightedLine = highlightedLine;
                    try {
                        const isProcedureFile = baseName !== 'test.s';
                        const instr = JSON.parse(org.disassembledInstructionJson);
                        if (isProcedureFile && instr) {
                            const headerLineText = (sourceLines[highlightedLine - 1] || '').trim().toUpperCase();
                            const isHeader = headerLineText === 'MY_PROC' || headerLineText.startsWith('.PROC');
                            if (instr.opcodeName === 'PUSH' && isHeader) {
                                const retIndex = sourceLines.findIndex(l => String(l).trim().toUpperCase().startsWith('RET'));
                                if (retIndex >= 0) {
                                    effectiveHighlightedLine = retIndex + 1;
                                    console.debug('[WR] shifted highlight (PUSH) to RET at line', effectiveHighlightedLine);
                                }
                            }
                            // Bei POP keine Verschiebung (Header bleiben)
                        }
                    } catch(_) {}

                    sourceCodeHtml = `<b>${displayName}</b><div class="code-view source-code-view">`;
                    sourceLines.forEach((line, index) => {
                        const lineNum = index + 1;
                        const isHighlighted = lineNum === effectiveHighlightedLine;
                        let processedLine = line.replace(/</g, "&lt;");

                        if (isHighlighted) {
                            const instruction = JSON.parse(org.disassembledInstructionJson);
                            console.debug('[WR] disassembled for line', lineNum, instruction);
                            if (instruction && Array.isArray(instruction.arguments)) {
                                const isRealCall = instruction.opcodeName === 'CALL';
                                instruction.arguments
                                    .filter(arg => arg.type === 'register')
                                    .forEach(arg => {
                                        // Disassembly-basierte Annotation nur bei echtem CALL, um Duplikate in PUSH-Ticks zu vermeiden
                                        if (!isRealCall) return;
                                        let tokenToReplace = arg.name;
                                        if (artifact.registerAliasMap) {
                                            const alias = Object.keys(artifact.registerAliasMap).find(key => artifact.registerAliasMap[key] === arg.value);
                                            if (alias) tokenToReplace = alias;
                                        }
                                        const alreadyAnnotated = new RegExp(`${escapeRegExp(tokenToReplace)}<span class=\\"injected-value\\"`).test(processedLine);
                                        if (!alreadyAnnotated) {
                                            const annotation = `<span class=\"injected-value\">[${arg.name}=${arg.fullDisplayValue}]</span>`;
                                            const escapedTok = escapeRegExp(tokenToReplace);
                                            const regex = new RegExp(`(^|[^A-Za-z0-9_])(${escapedTok})(?![^<]*>)(?![A-Za-z0-9_])`, 'g');
                                            processedLine = processedLine.replace(regex, `$1$2${annotation}`);
                                        }
                                    });
                            }
                            // Aliase immer annotieren (sichert beide Parameter in PUSH-Ticks), doppelte werden vermieden
                            processedLine = annotateLineByAliases(processedLine, artifact);
                            // Formale Parameter annotieren (wirkt nur in Prozeduren)
                            processedLine = annotateLineByFormalParams(processedLine, artifact, org);
                        }

                        sourceCodeHtml += `<div class="source-line ${isHighlighted ? 'highlight' : ''}"><span class="line-number">${lineNum}</span><pre>${processedLine}</pre></div>`;
                    });
                    sourceCodeHtml += '</div>';
                }

                const disassembled = JSON.parse(org.disassembledInstructionJson);
                if (disassembled && disassembled.opcodeName) {
                    const parts = disassembled.arguments.map(a => {
                        if (a.type === 'register') {
                            let outer = a.name;
                            if (artifact.registerAliasMap) {
                                const alias = Object.keys(artifact.registerAliasMap).find(key => artifact.registerAliasMap[key] === a.value);
                                if (alias) outer = alias;
                            }
                            return `${outer}<span class="injected-value">[${a.name}=${a.fullDisplayValue}]</span>`;
                        }
                        return a.name;
                    });
                    instructionHtml = `${disassembled.opcodeName} ${parts.join(' ')}`;
                }
            }
            // Fallback: Wenn SourceMap nicht aufgelöst werden konnte, aber Disassembler Source-Infos liefert
            if ((!sourceInfo || !sourceInfo.fileName) && !sourceCodeHtml.includes('source-code-view')) {
                try {
                    const disassembled = JSON.parse(org.disassembledInstructionJson);
                    if (disassembled && disassembled.sourceFileName && artifact.sources && artifact.sources[disassembled.sourceFileName]) {
                        const sourceLines = artifact.sources[disassembled.sourceFileName];
                        const highlightedLine = disassembled.sourceLineNumber;
                        sourceCodeHtml = `<b>${disassembled.sourceFileName}</b><div class="code-view source-code-view">`;
                        sourceLines.forEach((line, index) => {
                            const lineNum = index + 1;
                            const isHighlighted = lineNum === highlightedLine;
                            let processedLine = line.replace(/</g, "&lt;");
                            if (isHighlighted) {
                                processedLine = annotateLineByAliases(processedLine, artifact);
                                processedLine = annotateLineByFormalParams(processedLine, artifact, org);
                            }
                            sourceCodeHtml += `<div class="source-line ${isHighlighted ? 'highlight' : ''}"><span class="line-number">${lineNum}</span><pre>${processedLine}</pre></div>`;
                        });
                        sourceCodeHtml += '</div>';
                    }
                } catch (_) { /* ignore */ }
            }
        }

        // Falls kein Source-Mapping vorhanden war, trotzdem die disassemblierte Instruktion anzeigen (nicht im Performance-Modus)
        if (!isPerfMode && instructionHtml === 'N/A' && org.disassembledInstructionJson) {
            try {
                const disassembled = JSON.parse(org.disassembledInstructionJson);
                if (disassembled && disassembled.opcodeName) {
                    const artifact = programArtifacts.get(org.programId);
                    const parts = disassembled.arguments.map(a => {
                        if (a.type === 'register') {
                            let outer = a.name;
                            if (artifact && artifact.registerAliasMap) {
                                const alias = Object.keys(artifact.registerAliasMap).find(key => artifact.registerAliasMap[key] === a.value);
                                if (alias) outer = alias;
                            }
                            return `${outer}<span class="injected-value">[${a.name}=${a.fullDisplayValue}]</span>`;
                        }
                        return a.name;
                    });
                    instructionHtml = `${disassembled.opcodeName} ${parts.join(' ')}`;
                }
            } catch (_) { /* ignore */ }
        }

        const dsHtml = isPerfMode ? perfModeMsg : (org.dataStack.length > 0 ? org.dataStack.slice(-8).reverse().join('<br>') : '[]');
        let csHtml = isPerfMode ? perfModeMsg : (org.callStack.length > 0 ? org.callStack.join(' &rarr; ') : '[]');
        if (!isPerfMode && org.formalParameters && org.formalParameters.length > 0) {
            csHtml += `<br><span style="color:#888;">Parameter (Top Frame):</span><br>` + org.formalParameters.join('<br>');
        }

        detailsContent.innerHTML = `
<h3>Organismus #${org.organismId}</h3><hr>
<b>Programm-ID:</b> ${org.programId || 'N/A'}
<b>Energie (ER):</b> ${org.energy}
<b>Position (IP):</b> ${org.positionJson}
<b>Datenzeiger (DP):</b> ${org.dpJson}
<b>Richtung (DV):</b> ${org.dvJson}
<hr><b>Quellcode: </b>${sourceCodeHtml}
<hr>
<b>Aktuelle Instruktion:</b>
<div class="code-view">${instructionHtml}</div>
<b>Data Stack (Top 8):</b>
<div class="code-view">${dsHtml}</div>
<b>Call Stack:</b>
<div class="code-view">${csHtml}</div>
        `;
    }

    canvas.addEventListener('click', (event) => {
        if (!lastWorldState.organisms || !renderer) return;
        const rect = canvas.getBoundingClientRect();
        const x = event.clientX - rect.left;
        const y = event.clientY - rect.top;

        const gridX = Math.floor(x / renderer.config.CELL_SIZE);
        const gridY = Math.floor(y / renderer.config.CELL_SIZE);

        let foundOrganism = false;
        for (const org of lastWorldState.organisms) {
            const pos = JSON.parse(org.positionJson);
            if (pos[0] === gridX && (pos.length === 1 || pos[1] === gridY)) {
                selectedOrganismId = org.organismId;
                updateSidebar();
                showSidebar();
                foundOrganism = true;
                break;
            }
        }

        if (!foundOrganism) {
            selectedOrganismId = null;
            hideSidebar();
        }
        renderWorldForTick(currentTick);
    });
});
