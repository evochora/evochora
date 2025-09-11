class WorldRenderer {
    static organismColorPalette = [
        '#32cd32', '#1e90ff', '#dc143c', '#ffd700',
        '#ffa500', '#9370db', '#00ffff'
    ];

    constructor(canvas, config, isa) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d');
        this.config = config;
        this.isa = isa;
        this.cellFont = `normal ${this.config.cellSize * 0.4}px 'Monospaced', 'Courier New'`;
        this.organismColorMap = new Map();
        
        // Tooltip-Referenz
        this.tooltip = document.getElementById('cell-tooltip');
        
        // Tooltip-Delay
        this.tooltipDelay = 300; // 300ms Verzögerung
        this.tooltipTimeout = null;
        this.lastMousePosition = null;
        
        // Mouse-Event-Handler für Tooltip
        this.setupTooltipEvents();
        
        // Initialize canvas size - wird erst nach dem Laden der Metadaten gesetzt
        // this.updateCanvasSize(); // Entfernt - wird erst nach dem Laden der Metadaten aufgerufen
    }
    
    /**
     * Updates the world shape and resizes the canvas accordingly
     * @param {Array} worldShape - [width, height] of the world
     */
    updateWorldShape(worldShape) {
        if (worldShape && Array.isArray(worldShape) && worldShape.length >= 2) {
            this.config.worldSize = worldShape;
            this.updateCanvasSize();
        }
    }
    
    /**
     * Updates the canvas size based on the current world shape and cell size
     */
    updateCanvasSize() {
        if (!this.config.worldSize || !Array.isArray(this.config.worldSize) || this.config.worldSize.length < 2) {
            throw new Error('World size not available. Please load simulation metadata first.');
        }
        
        const [width, height] = this.config.worldSize;
        this.canvas.width = width * this.config.cellSize;
        this.canvas.height = height * this.config.cellSize;
    }

    draw(worldState) {
        const { cells, organisms, selectedOrganismId } = worldState;
        
        if (!this.config.worldSize || !Array.isArray(this.config.worldSize) || this.config.worldSize.length < 2) {
            console.error('Cannot draw world: World size not available');
            return;
        }
        
        const worldShape = this.config.worldSize;

        // Speichere aktuelle Zellen für Tooltip
        this.currentCells = cells;

        this.canvas.width = worldShape[0] * this.config.cellSize;
        this.canvas.height = worldShape[1] * this.config.cellSize;
        this.ctx.fillStyle = this.config.backgroundColor;
        this.ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);

        // Iterate over the entire grid and draw each cell
        for (let x = 0; x < worldShape[0]; x++) {
            for (let y = 0; y < worldShape[1]; y++) {
                const cell = this.findCellAt(x, y);
                this.drawCell(cell);
            }
        }
        if (organisms) {
            for (const organism of organisms) this.drawOrganism(organism, selectedOrganismId === organism.organismId);
        }
    }

    parsePosition(posString) {
        try {
            if (posString && posString.startsWith('[')) return JSON.parse(posString);
            return null;
        } catch (e) { return null; }
    }

    drawCell(cell) {
        const pos = this.parsePosition(cell.position);
        if (!pos) return;

        const x = pos[0] * this.config.cellSize;
        const y = pos[1] * this.config.cellSize;

        this.ctx.fillStyle = this.getBackgroundColorForType(cell.type);
        this.ctx.fillRect(x, y, this.config.cellSize, this.config.cellSize);

        if ((cell.type === this.config.typeCode && (cell.value !== 0 || cell.ownerId !== 0)) || cell.type !== this.config.typeCode) {
            this.ctx.fillStyle = this.getTextColorForType(cell.type);
            this.ctx.font = this.cellFont;
            this.ctx.textAlign = 'center';
            this.ctx.textBaseline = 'middle';

            let text;
            if (cell.type === this.config.typeCode) {
                text = (cell.opcodeName && typeof cell.opcodeName === 'string') ? cell.opcodeName : String(cell.value);
            } else {
                text = cell.value.toString();
            }
            this.ctx.fillText(text, x + this.config.cellSize / 2, y + this.config.cellSize / 2 + 1);
        }
    }

    drawOrganism(organism, isSelected) {
        const pos = this.parsePosition(organism.positionJson);
        if (!pos) return;

        const color = this.getOrganismColor(organism.organismId);
        const x = pos[0] * this.config.cellSize;
        const y = pos[1] * this.config.cellSize;

        this.ctx.strokeStyle = organism.energy <= 0 ? this.config.colorDead : color;
        this.ctx.lineWidth = 2.5;
        this.ctx.strokeRect(x, y, this.config.cellSize, this.config.cellSize);

        // Draw all DPs for the organism (selected or not)
        const dpsArray = Array.isArray(organism.dps) ? organism.dps
            : (organism.dpsJson ? (() => { try { const a = JSON.parse(organism.dpsJson); return Array.isArray(a) ? a : null; } catch { return null; } })() : null);
        const activeDpIndex = organism.activeDpIndex || 0; // Aktiver DP-Index
        if (Array.isArray(dpsArray)) {
            const byCell = new Map();
            dpsArray.forEach((dpPos, idx) => {
                const key = Array.isArray(dpPos) ? dpPos.join('|') : String(dpPos);
                if (!byCell.has(key)) byCell.set(key, { pos: dpPos, idxs: [] });
                byCell.get(key).idxs.push(idx);
            });
            for (const { pos, idxs } of byCell.values()) {
                const isActive = idxs.includes(activeDpIndex);
                this.drawDp(pos, color, idxs, isActive);
            }
        }
        // DV marker: draw at the edge of the IP cell for any available dv vector (dv or dvJson)
        let dvVec = null;
        if (organism.dvJson) {
            const pv = this.parsePosition(organism.dvJson);
            if (Array.isArray(pv)) dvVec = pv;
        } else if (Array.isArray(organism.dv)) {
            dvVec = organism.dv;
        }
        if (Array.isArray(dvVec)) {
            const edgeOffset = this.config.cellSize * 0.5;
            this.ctx.fillStyle = color;
            const cx = x + this.config.cellSize / 2;
            const cy = y + this.config.cellSize / 2;
            const px = cx + Math.sign(dvVec[0]||0) * edgeOffset * 0.9;
            const py = cy + Math.sign(dvVec[1]||0) * edgeOffset * 0.9;
            this.ctx.beginPath();
            this.ctx.arc(px, py, this.config.cellSize * 0.1, 0, 2*Math.PI);
            this.ctx.fill();
        }

    }

    drawDp(pos, color, indices, isActive = false) {
        const x = pos[0] * this.config.cellSize;
        const y = pos[1] * this.config.cellSize;
        this.ctx.strokeStyle = color;
        this.ctx.lineWidth = 2.0; // Dicker als vorher (1.5), aber dünner als IP (2.5)
        this.ctx.setLineDash([3, 3]); // Gestrichelte Linie für DP
        this.ctx.strokeRect(x, y, this.config.cellSize, this.config.cellSize); // So groß wie IP-Rahmen
        this.ctx.setLineDash([]); // Linienstil zurücksetzen
        
        // Schraffur für aktiven DP
        if (isActive) {
            this.ctx.strokeStyle = color;
            this.ctx.lineWidth = 1.0;
            this.ctx.setLineDash([]); // Durchgezogene Linien für Schraffur
            const cellSize = this.config.cellSize;
            const spacing = 4; // Abstand zwischen Schraffurlinien
            // Diagonale Schraffur von links oben nach rechts unten, nur innerhalb der Zelle
            for (let i = -cellSize; i < cellSize; i += spacing) {
                this.ctx.beginPath();
                // Startpunkt: links oben oder links außerhalb
                const startX = Math.max(x, x + i);
                const startY = y + Math.max(0, -i);
                // Endpunkt: rechts unten oder rechts außerhalb
                const endX = Math.min(x + cellSize, x + i + cellSize);
                const endY = y + Math.min(cellSize, cellSize - i);
                this.ctx.moveTo(startX, startY);
                this.ctx.lineTo(endX, endY);
                this.ctx.stroke();
            }
        }
        
        if (Array.isArray(indices) && indices.length > 0) {
            const text = indices.join(',');
            const centerX = x + this.config.cellSize / 2;
            const centerY = y + this.config.cellSize / 2 + 1;
            
            // Schatten für bessere Sichtbarkeit
            this.ctx.fillStyle = 'rgba(0, 0, 0, 0.8)';
            this.ctx.font = `900 ${this.config.cellSize * 0.45}px 'Monospaced', 'Courier New'`; // 900 = extra bold, 45% für mehrere DPs
            this.ctx.textAlign = 'center';
            this.ctx.textBaseline = 'middle';
            this.ctx.fillText(text, centerX + 1, centerY + 1); // Schatten
            
            // Haupttext
            this.ctx.fillStyle = color;
            this.ctx.fillText(text, centerX, centerY);
        }
    }

    getOrganismColor(id) {
        if (!this.organismColorMap.has(id)) {
            // Organismus ID 1 bekommt Index 0, ID 2 bekommt Index 1, etc.
            const paletteIndex = (id - 1) % WorldRenderer.organismColorPalette.length;
            this.organismColorMap.set(id, WorldRenderer.organismColorPalette[paletteIndex]);
        }
        return this.organismColorMap.get(id);
    }

    getBackgroundColorForType(typeId) {
        const C = this.config;
        switch (typeId) {
            case C.typeCode: return C.colorCodeBg;
            case C.typeData: return C.colorDataBg;
            case C.typeEnergy: return C.colorEnergyBg;
            case C.typeStructure: return C.colorStructureBg;
            default: return C.colorEmptyBg;
        }
    }

    getTextColorForType(typeId) {
        const C = this.config;
         switch (typeId) {
            case C.typeStructure: return C.colorStructureText;
            case C.typeEnergy: return C.colorEnergyText;
            case C.typeData: return C.colorDataText;
            case C.typeCode: return C.colorCodeText;
            default: return C.colorText;
        }
    }

    setupTooltipEvents() {
        this.canvas.addEventListener('mousemove', (event) => this.handleMouseMove(event));
        this.canvas.addEventListener('mouseleave', () => {
            this.hideTooltip();
            if (this.tooltipTimeout) {
                clearTimeout(this.tooltipTimeout);
                this.tooltipTimeout = null;
            }
            this.lastMousePosition = null;
        });
    }

    handleMouseMove(event) {
        const rect = this.canvas.getBoundingClientRect();
        const x = event.clientX - rect.left;
        const y = event.clientY - rect.top;
        
        // Prüfe, ob Maus über dem Canvas ist
        if (x < 0 || y < 0 || x >= this.canvas.width || y >= this.canvas.height) {
            this.hideTooltip();
            if (this.tooltipTimeout) {
                clearTimeout(this.tooltipTimeout);
                this.tooltipTimeout = null;
            }
            this.lastMousePosition = null;
            return;
        }
        
        // Konvertiere zu Grid-Koordinaten
        const gridX = Math.floor(x / this.config.cellSize);
        const gridY = Math.floor(y / this.config.cellSize);
        
        // Prüfe, ob sich die Position geändert hat
        const currentPos = `${gridX},${gridY}`;
        if (this.lastMousePosition === currentPos) {
            return; // Keine Änderung, nichts tun
        }
        
        // Position hat sich geändert - Tooltip ausblenden und Timer zurücksetzen
        this.hideTooltip();
        this.lastMousePosition = currentPos;
        
        // Verzögerung für Tooltip
        if (this.tooltipTimeout) {
            clearTimeout(this.tooltipTimeout);
        }
        
        // Suche nach Zelle an dieser Position
        const cell = this.findCellAt(gridX, gridY);
        
        if (cell) {
            // Tooltip nach Verzögerung anzeigen
            this.tooltipTimeout = setTimeout(() => {
                this.showTooltip(event, cell, gridX, gridY);
            }, this.tooltipDelay);
        }
    }

    findCellAt(gridX, gridY) {
        if (!this.currentCells) return null;
        
        // Suche nach existierender Zelle
        const existingCell = this.currentCells.find(cell => {
            const pos = this.parsePosition(cell.position);
            return pos && pos[0] === gridX && pos[1] === gridY;
        });
        
        if (existingCell) {
            return existingCell;
        }
        
        // Erstelle virtuelle Zelle für leere Positionen
        return {
            type: this.config.typeCode,
            value: 0,
            ownerId: 0,
            opcodeName: 'NOP'
        };
    }

    showTooltip(event, cell, gridX, gridY) {
        if (!this.tooltip) return;
        
        // Tooltip-Inhalt erstellen
        const typeName = this.getTypeName(cell.type);
        const opcodeInfo = (cell.opcodeName && (cell.ownerId !== 0 || cell.value !== 0)) ? `(${cell.opcodeName})` : '';
        
        this.tooltip.innerHTML = `
            <span class="tooltip-coords">[${gridX}|${gridY}]</span>
            <span class="tooltip-type">${typeName}:${cell.value}${opcodeInfo}</span>
            <span class="tooltip-separator">•</span>
            <span class="tooltip-owner">Owner: ${cell.ownerId || 0}</span>
        `;
        
        // Tooltip-Position berechnen (verhindert Überlappung mit Fensterrand)
        const tooltipRect = this.tooltip.getBoundingClientRect();
        const viewportWidth = window.innerWidth;
        const viewportHeight = window.innerHeight;
        
        let left = event.clientX;
        let top = event.clientY - 8; // 8px über der Maus
        
        // Verhindere Überlappung mit rechten Rand
        if (left + tooltipRect.width > viewportWidth) {
            left = viewportWidth - tooltipRect.width - 10;
        }
        
        // Verhindere Überlappung mit linken Rand
        if (left < 10) {
            left = 10;
        }
        
        // Verhindere Überlappung mit oberen Rand
        if (top < 10) {
            top = event.clientY + 20; // Unter der Maus
        }
        
        // Verhindere Überlappung mit unteren Rand
        if (top + tooltipRect.height > viewportHeight) {
            top = viewportHeight - tooltipRect.height - 10;
        }
        
        // Tooltip-Position setzen
        this.tooltip.style.left = left + 'px';
        this.tooltip.style.top = top + 'px';
        
        // Tooltip anzeigen
        this.tooltip.classList.add('show');
    }

    hideTooltip() {
        if (this.tooltip) {
            this.tooltip.classList.remove('show');
        }
    }

    getTypeName(typeId) {
        const C = this.config;
        switch (typeId) {
            case C.typeCode: return 'CODE';
            case C.typeData: return 'DATA';
            case C.typeEnergy: return 'ENERGY';
            case C.typeStructure: return 'STRUCTURE';
            default: return 'UNKNOWN';
        }
    }
}
