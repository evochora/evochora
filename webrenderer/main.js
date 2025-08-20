document.addEventListener('DOMContentLoaded', async () => {

    const fileInput = document.getElementById('db-upload');
    const sidebar = document.getElementById('sidebar');
    const closeSidebarBtn = document.getElementById('close-sidebar');
    const detailsContainerEl = document.getElementById('organism-details');
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
    let isSidebarVisible = false;

    const SQL = await initSqlJs({ locateFile: file => `https://cdnjs.cloudflare.com/ajax/libs/sql.js/1.10.3/${file}` });

    function showSidebar() { isSidebarVisible = true; sidebar.classList.add('visible'); }
    function hideSidebar() { isSidebarVisible = false; sidebar.classList.remove('visible'); }
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
        if (selectedOrganismId !== null && isSidebarVisible) {
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

        const orgStmt = db.prepare("SELECT organismId, programId, birthTick, energy, positionJson, dpsJson, dvJson, disassembledInstructionJson, dataRegisters, procRegisters, dataStack, callStack, formalParameters, fprs, locationRegisters, locationStack FROM organism_states WHERE tickNumber = :tick");
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
                orgData.fprs = JSON.parse(orgData.fprs || '[]');
                orgData.locationRegisters = JSON.parse(orgData.locationRegisters || '[]');
                orgData.locationStack = JSON.parse(orgData.locationStack || '[]');
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
            detailsContainerEl.innerHTML = `<h2>Organismus-Details</h2><p>Organismus #${selectedOrganismId} in diesem Tick nicht gefunden.</p>`;
            return;
        }
        const detailsContainer = document.getElementById('organism-details');
        const savedSidebarScrollTop = detailsContainer ? detailsContainer.scrollTop : 0;

        const isPerfMode = (runMode === 'performance');
        const artifact = programArtifacts.get(org.programId);

        let sourceCodeHtml = isPerfMode ? '' : `<div class="code-view">Kein Quellcode verfügbar.</div>`;
        let instructionHtml = 'N/A';

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
        const shorten = (s) => String(s)
            .replaceAll('CODE:', 'C:')
            .replaceAll('DATA:', 'D:')
            .replaceAll('ENERGY:', 'E:')
            .replaceAll('STRUCTURE:', 'S:');

        const annotateLineByAliases = (line, artifactObj) => {
            if (!artifactObj || !artifactObj.registerAliasMap) return line;
            if (line.includes('injected-value')) return line; // schon annotiert
            const matches = line.match(/%[A-Za-z0-9_]+/g);
            if (!matches) return line;
            let out = line;
            // 1) Annotate procedure header line: .PROC NAME ... WITH ...
            out = out.replace(/^(\s*\.PROC\s+([A-Za-z_.][A-Za-z0-9_.]*))([^\n]*)$/i, (m, head, procName, rest) => {
                // add address to procName if we can resolve a label
                try {
                    const labelAddrToName = artifactObj.labelAddressToName || {};
                    const addrToCoord = artifactObj.linearAddressToCoord || {};
                    let procAddr = null;
                    for (const [addr, name] of Object.entries(labelAddrToName)) {
                        if (String(name).toUpperCase() === String(procName).toUpperCase()) {
                            const coord = addrToCoord[addr] || addrToCoord[parseInt(addr)];
                            if (Array.isArray(coord)) procAddr = coord.join('|');
                            break;
                        }
                    }
                    if (procAddr) {
                        head = `${head}<span class=\"injected-value\">[${procAddr}]</span>`;
                    }
                } catch {}
                // annotate formal params after WITH
                const params = paramNames || [];
                let r = rest;
                if (params.length > 0) {
                    for (let i = 0; i < params.length; i++) {
                        const pName = params[i];
                        const escapedP = escapeRegExp(pName);
                        let bracketStr = `[%FPR${i}=N/A]`;
                        if (Array.isArray(orgObj.formalParameters) && orgObj.formalParameters[i]) {
                            const mm = String(orgObj.formalParameters[i]).match(/\[(.*?)\]=(.*)$/);
                            if (mm) bracketStr = `[${shorten(mm[1] + '=' + mm[2])}]`;
                        }
                        const rx = new RegExp(`(^|[^A-Za-z0-9_])(${escapedP})(?![^<]*>)(?![A-Za-z0-9_])`, 'g');
                        r = r.replace(rx, `$1$2<span class=\"injected-value\">${bracketStr}</span>`);
                    }
                }
                return `${head}${r}`;
            });
            for (const alias of matches) {
                const regId = artifactObj.registerAliasMap[alias];
                if (regId == null) continue;
                // Wenn alias bereits mit einer injected-value Annotation versehen ist, auslassen
                const alreadyAnnotated = new RegExp(`${escapeRegExp(alias)}<span class=\"injected-value\"`).test(out);
                if (alreadyAnnotated) continue;
                const annotation = `<span class=\"injected-value\">[${canonicalNameForRegId(regId)}=${shorten(valueForRegId(regId))}]</span>`;
                const escaped = escapeRegExp(alias);
                const regex = new RegExp(`(^|[^A-Za-z0-9_])(${escaped})(?![^<]*>)(?![A-Za-z0-9_])`, 'g');
                out = out.replace(regex, `$1$2${annotation}`);
            }
            return out;
        };
        const annotateLabels = (line, artifactObj) => {
            if (!artifactObj) return line;
            const labelAddrToName = artifactObj.labelAddressToName || {};
            const addrToCoord = artifactObj.linearAddressToCoord || {};
            // Build name -> coord cache
            const nameToCoord = {};
            for (const [addrKey, name] of Object.entries(labelAddrToName)) {
                const coord = addrToCoord[addrKey] || addrToCoord[parseInt(addrKey)];
                if (Array.isArray(coord)) nameToCoord[name] = coord;
            }
            let out = line;
            // Annotate label definitions at start of line: LABEL:
            out = out.replace(/^(\s*)([A-Za-z_][A-Za-z0-9_]*)\s*:(.*)$/,(m, pre, lbl, rest)=>{
                const coord = nameToCoord[lbl];
                if (!coord) return m;
                const ann = `<span class=\"injected-value\">[${coord.join('|')}]</span>`;
                return `${pre}${lbl}${ann}:${rest}`;
            });
            // Annotate label references in text (e.g., CALL MY_LABEL) with address only (no redundant =value). Avoid double brackets.
            const tokenRegex = /(^|[^A-Za-z0-9_])([A-Za-z_][A-Za-z0-9_]*)(?![^<]*>)(?![A-Za-z0-9_])/g;
            out = out.replace(tokenRegex, (match, pre, tok) => {
                const coord = nameToCoord[tok];
                if (!coord) return match;
                // Avoid double annotation or cases where already annotated as [addr]=[addr]
                if (new RegExp(`${tok}\\[`).test(out)) return match; // already followed by [ ... ]
                if (new RegExp(`${tok}<span class=\\"injected-value\\"`).test(out)) return match;
                const ann = `<span class=\"injected-value\">[${coord.join('|')}]</span>`;
                return `${pre}${tok}${ann}`;
            });
            return out;
        };
        const annotateLineByFormalParams = (line, artifactObj, orgObj) => {
            if (!artifactObj || !artifactObj.procNameToParamNames || !orgObj || !Array.isArray(orgObj.callStack) || orgObj.callStack.length === 0) return line;
            const topEntry = orgObj.callStack[orgObj.callStack.length - 1];
            if (!topEntry) return line;
            // Prozedurname aus erstem Token extrahieren, auch wenn Parameter angehängt sind
            const currentProc = String(topEntry).trim().split(/\s+/)[0];
            const paramNames = artifactObj.procNameToParamNames[currentProc ? currentProc.toUpperCase() : currentProc];
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
                out = out.replace(regex, `$1$2<span class=\"injected-value\">${shorten(bracketStr)}</span>`);
            }
            return out;
        };

        // Annotate macro-like parameters (e.g., IFI REGISTER EXPECTED_LITERAL) by mapping tokens to disassembled args
        const annotateMacroParams = (line, instructionObj, artifactObj, orgObj) => {
            try {
                if (!instructionObj || !Array.isArray(instructionObj.arguments)) return line;
                const m = line.match(/^\s*([A-Za-z_.][A-Za-z0-9_.]*)\s+(.+)$/);
                if (!m) return line;
                const firstToken = m[1];
                if (firstToken.startsWith('.')) return line; // skip directives like .PROC
                if (typeof instructionObj.opcodeName === 'string' && firstToken.toUpperCase() !== String(instructionObj.opcodeName).toUpperCase()) {
                    // opcode mismatch → likely not an instruction line (e.g., header). Skip to avoid wrong annotations
                    return line;
                }
                const paramsPart = m[2];
                const tokens = paramsPart.split(/\s+/).filter(Boolean);
                const exclude = new Set(['WITH','EXPORT','IMPORT','AS','MACRO','ENDM']);
                // Exclude formal param names of current proc to avoid clobbering with FPRs
                let formalParamNames = [];
                try {
                    if (artifactObj && artifactObj.procNameToParamNames && orgObj && Array.isArray(orgObj.callStack) && orgObj.callStack.length > 0) {
                        const topEntry = orgObj.callStack[orgObj.callStack.length - 1];
                        const currentProc = String(topEntry).trim().split(/\s+/)[0];
                        formalParamNames = artifactObj.procNameToParamNames[currentProc ? currentProc.toUpperCase() : currentProc] || [];
                    }
                } catch {}
                formalParamNames.forEach(n => exclude.add(String(n).toUpperCase()));
                // Exclude known label names
                try {
                    const lan = artifactObj && artifactObj.labelAddressToName ? Object.values(artifactObj.labelAddressToName) : [];
                    lan.forEach(n => exclude.add(String(n).toUpperCase()))
                } catch {}
                let out = line;
                let argIdx = 0;
                for (let i = 0; i < tokens.length && argIdx < instructionObj.arguments.length; i++) {
                    const tok = tokens[i];
                    // Only uppercase identifiers without % treated as macro params
                    if (!/^[A-Z][A-Z0-9_]*$/.test(tok)) continue;
                    if (exclude.has(tok.toUpperCase())) continue;
                    const arg = instructionObj.arguments[argIdx++];
                    const escapedTok = escapeRegExp(tok);
                    const alreadyAnnotated = new RegExp(`${escapedTok}<span class=\\"injected-value\\"`).test(out);
                    if (alreadyAnnotated) continue;
                    const valuePart = shorten(arg.fullDisplayValue);
                    const annotation = arg.type === 'register'
                        ? `<span class=\"injected-value\">[${arg.name}=${valuePart}]</span>`
                        : `<span class=\"injected-value\">[${valuePart}]</span>`;
                    const regex = new RegExp(`(^|[^A-Za-z0-9_])(${escapedTok})(?![^<]*>)(?![A-Za-z0-9_])`, 'g');
                    out = out.replace(regex, `$1$2${annotation}`);
                }
                return out;
            } catch { return line; }
        };

        // Basale Debug-Ausgaben
        console.debug('[WR] runMode:', runMode, 'isPerfMode:', isPerfMode, 'artifact?', !!artifact);

        // Source-Rendering nur im Debug-Modus
        if (!isPerfMode && artifact && artifact.sourceMap && artifact.sources) {
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
                    const displayName = absName; // exakter Pfad wie im .INCLUDE
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

                    // Build header path: //org/... (vorzugsweise ab /org/), sonst ab /src/main/resources/
                    const normalized = String(displayName).replace(/\\/g, '/');
                    let shortPath = normalized;
                    const orgIdx = normalized.indexOf('/org/');
                    if (orgIdx >= 0) shortPath = normalized.substring(orgIdx);
                    else {
                        const rIdx = normalized.indexOf('/src/main/resources/');
                        if (rIdx >= 0) shortPath = normalized.substring(rIdx + '/src/main/resources/'.length);
                    }
                    const headerLine = `//${shortPath}`;

                    sourceCodeHtml = `<div class="code-view source-code-view">`;
                    // Header-Zeile ohne Zeilennummer, aber mit leerem line-number-Span für bündige Ausrichtung
                    sourceCodeHtml += `<div class="source-line"><span class="line-number"></span><pre>${headerLine}</pre></div>`;
                sourceLines.forEach((line, index) => {
                    const lineNum = index + 1;
                        const isHighlighted = lineNum === effectiveHighlightedLine;
                    let processedLine = line.replace(/</g, "&lt;");

                    if (isHighlighted) {
                         const instruction = JSON.parse(org.disassembledInstructionJson);
                            console.debug('[WR] disassembled for line', lineNum, instruction);
                            if (instruction && Array.isArray(instruction.arguments)) {
                                // Annotate macro-like parameter tokens positionally
                                processedLine = annotateMacroParams(processedLine, instruction, artifact, org);
                                // Limit register token annotation to CALL to avoid duplicates
                                const isRealCall = instruction.opcodeName === 'CALL';
                                if (isRealCall) {
                                    instruction.arguments
                                        .filter(arg => arg.type === 'register')
                                        .forEach(arg => {
                                            let tokenToReplace = arg.name;
                                            if (artifact.registerAliasMap) {
                                const alias = Object.keys(artifact.registerAliasMap).find(key => artifact.registerAliasMap[key] === arg.value);
                                                if (alias) tokenToReplace = alias;
                                            }
                                            const alreadyAnnotated = new RegExp(`${escapeRegExp(tokenToReplace)}<span class=\"injected-value\"`).test(processedLine);
                                            if (!alreadyAnnotated) {
                                                const annotation = `<span class=\"injected-value\">[${arg.name}=${shorten(arg.fullDisplayValue)}]</span>`;
                                                const escapedTok = escapeRegExp(tokenToReplace);
                                                const regex = new RegExp(`(^|[^A-Za-z0-9_])(${escapedTok})(?![^<]*>)(?![A-Za-z0-9_])`, 'g');
                                                processedLine = processedLine.replace(regex, `$1$2${annotation}`);
                                            }
                                        });
                                }
                            }
                            // Aliase immer annotieren (sichert beide Parameter in PUSH-Ticks), doppelte werden vermieden
                            processedLine = annotateLineByAliases(processedLine, artifact);
                            // Labels annotieren (Definitionen und Verwendungen)
                            processedLine = annotateLabels(processedLine, artifact);
                            // Formale Parameter annotieren (wirkt nur in Prozeduren)
                            processedLine = annotateLineByFormalParams(processedLine, artifact, org);
                        }

                        sourceCodeHtml += `<div class=\"source-line ${isHighlighted ? 'highlight' : ''}\"><span class=\"line-number\">${lineNum}</span><pre>${processedLine}</pre></div>`;
                });
                sourceCodeHtml += '</div>';
                    // Auto-scroll nur im Quelltext-Container
                    setTimeout(() => {
                        const container = detailsContainerEl.querySelector('.source-code-view');
                        const highlighted = container ? container.querySelector('.source-line.highlight') : null;
                        if (container && highlighted) {
                            const targetTop = highlighted.offsetTop - (container.clientHeight / 2) + (highlighted.clientHeight / 2);
                            container.scrollTop = Math.max(0, targetTop);
                        }
                    }, 0);
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
                            return `${outer}<span class="injected-value">[${a.name}=${shorten(a.fullDisplayValue)}]</span>`;
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
                        const normalized = String(disassembled.sourceFileName).replace(/\\/g, '/');
                        let shortPath = normalized;
                        const orgIdx = normalized.indexOf('/org/');
                        if (orgIdx >= 0) shortPath = normalized.substring(orgIdx);
                        else {
                            const rIdx = normalized.indexOf('/src/main/resources/');
                            if (rIdx >= 0) shortPath = normalized.substring(rIdx + '/src/main/resources/'.length);
                        }
                        const headerLine = `//${shortPath}`;
                        sourceCodeHtml = `<div class="code-view source-code-view">`;
                        sourceCodeHtml += `<div class=\"source-line\"><span class=\"line-number\"></span><pre>${headerLine}</pre></div>`;
                        sourceLines.forEach((line, index) => {
                            const lineNum = index + 1;
                            const isHighlighted = lineNum === highlightedLine;
                            let processedLine = line.replace(/</g, "&lt;");
                            if (isHighlighted) {
                                processedLine = annotateLineByAliases(processedLine, artifact);
                                processedLine = annotateLabels(processedLine, artifact);
                                try { const dis = JSON.parse(org.disassembledInstructionJson); processedLine = annotateMacroParams(processedLine, dis, artifact, org); } catch {}
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

        const dsHtml = isPerfMode ? '' : (org.dataStack.length > 0 ? org.dataStack.slice(-8).reverse().join('<br>') : '[]');
        let csHtml = isPerfMode ? '' : (org.callStack.length > 0 ? org.callStack.join(' &rarr; ') : '[]');

        // Kompakte Registeranzeige (nur Debug)
        let regsHtml = '';
        if (!isPerfMode) {
            const prev = window.__prevRegs = window.__prevRegs || new Map();
            const key = `${org.organismId}`;
            const prevState = prev.get(key) || {};

            const initialized = !!prevState.initialized;
            const markChanged = (cur, prevVal) => (!initialized || cur === prevVal) ? '' : ' style="background:#2a2a3a;border-radius:3px;padding:0 2px;"';

            const drNorm = (org.dataRegisters || []).map(shorten);
            const prNorm = (org.procRegisters || []).map(shorten);
            const fprNorm = (org.fprs || []).map(shorten);
            const lrNorm = (org.locationRegisters || []).map(shorten);
            const lsNorm = (org.locationStack || []).map(shorten);
            const drLine = drNorm.map((v,i)=>`<span${markChanged(v, (prevState.dr||[])[i])}>${i}=${v}</span>`).join(' ');
            const prLine = prNorm.map((v,i)=>`<span${markChanged(v, (prevState.pr||[])[i])}>${i}=${v}</span>`).join(' ');
            const fprLine = fprNorm.map((v,i)=>`<span${markChanged(v, (prevState.fpr||[])[i])}>${i}=${v}</span>`).join(' ');

            const dsShort = (org.dataStack||[]).map(shorten);
            const csShort = (org.callStack||[]);
            const dsChanged = initialized && JSON.stringify(dsShort) !== JSON.stringify(prevState.ds||[]);
            const csChanged = initialized && JSON.stringify(csShort) !== JSON.stringify(prevState.cs||[]);
            const paramsArray = (org.formalParameters||[]).map(shorten);
            // Build call stack lines: one line per entry; use server-provided strings (bereits inkl. Parametern)
            const csLines = (org.callStack||[]);
            const labelStyle = '';
            const kv = (label, html, changed) => `<div class=\"kv\"${changed?' style=\"background:#2a2a3a;border-radius:3px;padding:0 2px;\"':''}><div class=\"lbl\">${label}</div><div class=\"val\">${html}</div></div>`;
            regsHtml = `
<div class=\"code-view\" style=\"white-space:normal;line-height:1.4;font-size:0.85em;\"> 
${kv('DR:', drLine, false)}
${kv('PR:', prLine, false)}
${kv('FPR:', fprLine, false)}
${kv('LR:', lrNorm.join(' ')||'[]', false)}
${kv('LS:', lsNorm.join(' ')||'[]', false)}
${kv('DS:', dsShort.join(' ')||'[]', dsChanged)}
${kv('CS:', csLines.length? csLines.join('<br>') : '[]', csChanged)}
</div>`;

            prev.set(key, { initialized: true, dr: drNorm, pr: prNorm, fpr: fprNorm, ds: dsShort, cs: csShort });
        }
        if (!isPerfMode && org.formalParameters && org.formalParameters.length > 0) {
            // Parameter werden im obersten Call-Stack-Eintrag angezeigt
        }

        const ageTicks = typeof org.birthTick === 'number' ? Math.max(0, currentTick - org.birthTick) : 'N/A';
        const titleEl = document.querySelector('#organism-details h2');
        if (titleEl) {
            titleEl.textContent = `Organismus #${org.organismId} (${org.programId || 'N/A'}) - Age: ${ageTicks}`;
            titleEl.style.fontSize = '16px';
            titleEl.style.margin = '6px 0 6px';
        }
        const statusLine = `IP: ${org.positionJson} | ER: ${org.energy} | DPs: ${org.dpsJson || '[]'} | DV: ${org.dvJson}`;
        const debugNoteHtml = isPerfMode ? `<div style=\"margin:6px 0;color:#888;font-size:0.8em;\">No debug info available</div>` : '';
        const instrBoxHtml = isPerfMode ? '' : `<div class=\"code-view\" style=\"font-size:0.85em;\">${shorten(instructionHtml)}</div>`;
        // Reihenfolge: Status, (Perf-Hinweis), Instruktion (nur Debug), Register+CS (nur Debug), Quelltext (nur Debug)
        detailsContainerEl.innerHTML = `
<h2>Organismus-Details</h2>
<div class=\"code-view\" style=\"margin-bottom:6px;color:#ccc;font-size:0.85em;\">${shorten(statusLine)}</div>
${debugNoteHtml}
${instrBoxHtml}
${regsHtml}
${sourceCodeHtml}
        `;
        // Auto-scroll zur markierten Zeile nach DOM-Update (nur Quelltext-Container)
        setTimeout(() => {
            const container = detailsContainerEl.querySelector('.source-code-view');
            const highlighted = container ? container.querySelector('.source-line.highlight') : null;
            if (container && highlighted) {
                const cRect = container.getBoundingClientRect();
                const hRect = highlighted.getBoundingClientRect();
                const relativeTop = hRect.top - cRect.top + container.scrollTop;
                const relativeBottom = relativeTop + highlighted.clientHeight;
                const viewTop = container.scrollTop;
                const viewBottom = viewTop + container.clientHeight;
                if (relativeTop < viewTop) {
                    container.scrollTop = Math.max(0, relativeTop - Math.floor(container.clientHeight * 0.2));
                } else if (relativeBottom > viewBottom) {
                    container.scrollTop = relativeBottom - Math.floor(container.clientHeight * 0.8);
                }
            }
            if (detailsContainer) detailsContainer.scrollTop = savedSidebarScrollTop;
        }, 0);
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
