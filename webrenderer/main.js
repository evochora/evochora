document.addEventListener('DOMContentLoaded', async () => {

    // DOM-Elemente holen (unverändert)
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
                        const reverseAliasMap = new Map();
                        if (artifact.registerAliasMap) {
                            for (const [alias, regId] of Object.entries(artifact.registerAliasMap)) {
                                reverseAliasMap.set(regId, alias);
                            }
                        }
                        artifact.reverseAliasMap = reverseAliasMap;
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
        // Holen Sie ALLE neuen Felder aus der DB
        const orgStmt = db.prepare("SELECT organismId, programId, energy, positionJson, dpJson, dvJson, disassembledInstructionJson, dataRegisters, procRegisters, dataStack, callStack, formalParameters FROM organism_states WHERE tickNumber = :tick");
        orgStmt.bind({ ':tick': tick });
        const organisms = [];
        while(orgStmt.step()) {
            const orgData = orgStmt.getAsObject();
            // JSON-Felder direkt parsen
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

        let nextInstruction;
        try {
            nextInstruction = org.disassembledInstructionJson ? JSON.parse(org.disassembledInstructionJson) : null;
        } catch (e) {
            detailsContent.innerHTML = `<p style="color:red;">Fehler beim Lesen der Instruktions-Daten.</p>`;
            return;
        }

        const artifact = programArtifacts.get(org.programId);
        const ipAsInt = JSON.parse(org.positionJson)[0];

        const formatInstructionLine = (instruction, lineIp) => {
             if (!instruction || !instruction.opcodeName || instruction.opcodeName.startsWith("UNKNOWN")) {
                return `&gt; (IP ${lineIp}: ---)`;
            }
            let sourceInfo = "";
            if (artifact && artifact.sourceMap && artifact.sourceMap[lineIp]) {
                const si = artifact.sourceMap[lineIp];
                sourceInfo = `<i>(${si.fileName}:${si.lineNumber})</i> `;
            }
             const argsHtml = instruction.arguments.map(arg => `<b>${arg.name.replace(/</g, "&lt;")}</b>`).join(' ');
            return `&gt; ${sourceInfo}${instruction.opcodeName} ${argsHtml}`;
        };

        const instructionHtml = formatInstructionLine(nextInstruction, ipAsInt);

        // --- START DER ÄNDERUNGEN ---

        const drsHtml = org.dataRegisters.map((val, i) => `DR${i}=${val}`).join(', ');
        const prsHtml = org.procRegisters.length > 0 ? org.procRegisters.map((val, i) => `PR${i}=${val}`).join(', ') : '[]';
        const dsHtml = org.dataStack.slice(-5).reverse().join(', ') || '[]';
        const csHtml = org.callStack.length > 0 ? org.callStack.join(' &rarr; ') : '[]';
        const fprsHtml = org.formalParameters.length > 0 ? org.formalParameters.join('<br>') : '[]';

        detailsContent.innerHTML = `
<h3>Organismus #${org.organismId}</h3><hr>
<b>Programm-ID:</b> ${org.programId || 'N/A'}
<b>Energie (ER):</b> ${org.energy}
<b>Position (IP):</b> ${org.positionJson}
<b>Datenzeiger (DP):</b> ${org.dpJson}
<b>Richtung (DV):</b> ${org.dvJson}
<hr><b>Nächste Instruktion</b>
<div class="code-view">${instructionHtml}</div>
<hr><b>Register (DRs):</b>
<div class="code-view">${drsHtml}</div>
<b>Prozedur-Register (PRs):</b>
<div class="code-view">${prsHtml}</div>
<b>Formale Parameter (FPRs):</b>
<div class="code-view">${fprsHtml}</div>
<b>Data Stack (DS - Top 5):</b>
<div class="code-view">${dsHtml}</div>
<b>Call Stack (CS):</b>
<div class="code-view">${csHtml}</div>
        `;
        // --- ENDE DER ÄNDERUNGEN ---
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
            if (pos[0] === gridX && pos[1] === gridY) {
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