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

        if (!isPerfMode && artifact && artifact.sourceMap && artifact.sources) {
            const ip = JSON.parse(org.positionJson);
            const initialPos = [0,0];
            const relativeIp = [ip[0] - initialPos[0], ip[1] - initialPos[1]];
            const relativeIpKey = `${relativeIp[0]}|${relativeIp[1]}`;

            const linearAddress = artifact.relativeCoordToLinearAddress[relativeIpKey];
            const sourceInfo = artifact.sourceMap[linearAddress];

            if (sourceInfo && sourceInfo.fileName && artifact.sources[sourceInfo.fileName]) {
                const sourceLines = artifact.sources[sourceInfo.fileName];
                const highlightedLine = sourceInfo.lineNumber;

                sourceCodeHtml = `<b>${sourceInfo.fileName}</b><div class="code-view source-code-view">`;
                sourceLines.forEach((line, index) => {
                    const lineNum = index + 1;
                    const isHighlighted = lineNum === highlightedLine;
                    let processedLine = line.replace(/</g, "&lt;");

                    if (isHighlighted) {
                         const instruction = JSON.parse(org.disassembledInstructionJson);
                         if(instruction && instruction.arguments) {
                            instruction.arguments.forEach(arg => {
                                const realRegName = arg.name;
                                const alias = Object.keys(artifact.registerAliasMap).find(key => artifact.registerAliasMap[key] === arg.value);
                                const tokenToReplace = alias || realRegName;
                                const annotation = `<span class="injected-value">[${realRegName}=${arg.fullDisplayValue}]</span>`;
                                processedLine = processedLine.replace(new RegExp(`\\b${tokenToReplace}\\b`, 'gi'), `${tokenToReplace}${annotation}`);
                            });
                         }
                    }

                    sourceCodeHtml += `<div class="source-line ${isHighlighted ? 'highlight' : ''}"><span class="line-number">${lineNum}</span><pre>${processedLine}</pre></div>`;
                });
                sourceCodeHtml += '</div>';

                const disassembled = JSON.parse(org.disassembledInstructionJson);
                if (disassembled && disassembled.opcodeName) {
                    const args = disassembled.arguments.map(a => `<b>${a.name}</b>`).join(' ');
                    instructionHtml = `${disassembled.opcodeName} ${args}`;
                }
            }
        }

        const dsHtml = isPerfMode ? perfModeMsg : (org.dataStack.length > 0 ? org.dataStack.slice(-8).reverse().join('<br>') : '[]');
        const csHtml = isPerfMode ? perfModeMsg : (org.callStack.length > 0 ? org.callStack.join(' &rarr; ') : '[]');

        detailsContent.innerHTML = `
<h3>Organismus #${org.organismId}</h3><hr>
<b>Programm-ID:</b> ${org.programId || 'N/A'}
<b>Energie (ER):</b> ${org.energy}
<b>Position (IP):</b> ${org.positionJson}
<b>Datenzeiger (DP):</b> ${org.dpJson}
<b>Richtung (DV):</b> ${org.dvJson}
<hr><b>Quellcode</b>
${sourceCodeHtml}
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
