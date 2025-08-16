class WorldRenderer {
    static organismColorPalette = [
        '#dc143c', '#1e90ff', '#32cd32', '#ffd700',
        '#ffa500', '#9370db', '#00ffff'
    ];

    constructor(canvas, config, isa) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d');
        this.config = config;
        this.isa = isa;
        this.cellFont = `normal ${this.config.CELL_SIZE * 0.4}px 'Monospaced', 'Courier New'`;
        this.organismColorMap = new Map();
    }

    draw(worldState) {
        const { cells, organisms, selectedOrganismId } = worldState;
        const worldShape = this.config.WORLD_SHAPE;

        if (!worldShape || !worldShape[0] || !worldShape[1]) return;

        this.canvas.width = worldShape[0] * this.config.CELL_SIZE;
        this.canvas.height = worldShape[1] * this.config.CELL_SIZE;
        this.ctx.fillStyle = this.config.COLOR_BG;
        this.ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);

        if (cells) {
            for (const cell of cells) this.drawCell(cell);
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

        const x = pos[0] * this.config.CELL_SIZE;
        const y = pos[1] * this.config.CELL_SIZE;

        this.ctx.fillStyle = this.getBackgroundColorForType(cell.type);
        this.ctx.fillRect(x, y, this.config.CELL_SIZE, this.config.CELL_SIZE);

        // --- START DER KORREKTUR ---
        // Die Logik stellt sicher, dass der Wert korrekt angezeigt wird,
        // egal ob es sich um eine Instruktion oder einen einfachen Wert handelt.
        if ((cell.type === this.config.TYPE_CODE && cell.value !== 0) || cell.type !== this.config.TYPE_CODE) {
            this.ctx.fillStyle = this.getTextColorForType(cell.type);
            this.ctx.font = this.cellFont;
            this.ctx.textAlign = 'center';
            this.ctx.textBaseline = 'middle';

            let text;
            if (cell.type === this.config.TYPE_CODE) {
                const instructionId = cell.value; // In cell_states ist der reine Wert gespeichert
                text = this.isa[instructionId] || `OP(${instructionId})`;
            } else {
                text = cell.value.toString();
            }
            this.ctx.fillText(text, x + this.config.CELL_SIZE / 2, y + this.config.CELL_SIZE / 2 + 1);
        }
        // --- ENDE DER KORREKTUR ---
    }

    drawOrganism(organism, isSelected) {
        const pos = this.parsePosition(organism.positionJson);
        if (!pos) return;

        const color = this.getOrganismColor(organism.organismId);
        const x = pos[0] * this.config.CELL_SIZE;
        const y = pos[1] * this.config.CELL_SIZE;

        this.ctx.strokeStyle = organism.energy <= 0 ? this.config.COLOR_DEAD : color;
        this.ctx.lineWidth = 2.5;
        this.ctx.strokeRect(x, y, this.config.CELL_SIZE, this.config.CELL_SIZE);

        if (isSelected && organism.energy > 0) {
            const dv = this.parsePosition(organism.dvJson);
            if (dv) {
                const centerX = x + this.config.CELL_SIZE / 2;
                const centerY = y + this.config.CELL_SIZE / 2;
                const size = this.config.CELL_SIZE * 0.2;
                const offset = this.config.CELL_SIZE * 0.35;
                this.ctx.fillStyle = color;
                this.ctx.beginPath();
                this.ctx.arc(centerX + dv[0] * offset, centerY + dv[1] * offset, size / 2, 0, 2 * Math.PI);
                this.ctx.fill();
            }
        }
    }

    getOrganismColor(id) {
        if (!this.organismColorMap.has(id)) {
            this.organismColorMap.set(id, WorldRenderer.organismColorPalette[id % WorldRenderer.organismColorPalette.length]);
        }
        return this.organismColorMap.get(id);
    }

    getBackgroundColorForType(typeId) {
        const C = this.config;
        switch (typeId) {
            case C.TYPE_CODE: return C.COLOR_CODE_BG;
            case C.TYPE_DATA: return C.COLOR_DATA_BG;
            case C.TYPE_ENERGY: return C.COLOR_ENERGY_BG;
            case C.TYPE_STRUCTURE: return C.COLOR_STRUCTURE_BG;
            default: return C.COLOR_EMPTY_BG;
        }
    }

    getTextColorForType(typeId) {
        const C = this.config;
         switch (typeId) {
            case C.TYPE_STRUCTURE: return C.COLOR_STRUCTURE_TEXT;
            case C.TYPE_ENERGY: return C.COLOR_ENERGY_TEXT;
            case C.TYPE_DATA: return C.COLOR_DATA_TEXT;
            case C.TYPE_CODE: return C.COLOR_CODE_TEXT;
            default: return C.COLOR_TEXT;
        }
    }
}