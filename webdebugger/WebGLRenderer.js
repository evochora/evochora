class WebGLRenderer {
    static organismColorPalette = [
        '#32cd32', '#1e90ff', '#dc143c', '#ffd700',
        '#ffa500', '#9370db', '#00ffff'
    ];

    constructor(container, config, isa) {
        this.container = container;
        this.config = config;
        this.isa = isa;
        this.organismColorMap = new Map();
        this.cellData = new Map();
        this.cellObjects = new Map();
        this.app = new PIXI.Application();
        this.tooltip = document.getElementById('cell-tooltip');
        this.tooltipTimeout = null;
        this.lastMousePosition = null;
        this.tooltipDelay = 300;
        this.cellFont = {
            fontFamily: 'Monospaced, "Courier New"',
            fontSize: this.config.cellSize * 0.4,
            fill: 0xffffff,
            align: 'center',
        };
    }

    async init() {
        await this.app.init({
            width: (this.config.worldSize?.[0] ?? 100) * this.config.cellSize,
            height: (this.config.worldSize?.[1] ?? 30) * this.config.cellSize,
            backgroundColor: this.config.backgroundColor,
            autoDensity: true,
            resolution: window.devicePixelRatio || 1,
        });
        this.container.innerHTML = '';
        this.container.appendChild(this.app.view);

        this.cellContainer = new PIXI.Container();
        this.textContainer = new PIXI.Container();
        this.markerContainer = new PIXI.Container();

        this.app.stage.addChild(this.cellContainer, this.textContainer, this.markerContainer);

        this.setupTooltipEvents();
    }

    updateWorldShape(worldShape) {
        if (worldShape && Array.isArray(worldShape) && worldShape.length >= 2) {
            this.config.worldSize = worldShape;
            this.app.renderer.resize(
                worldShape[0] * this.config.cellSize,
                worldShape[1] * this.config.cellSize
            );
        }
    }

    draw(worldState) {
        const { cells, organisms, selectedOrganismId } = worldState;
        const newCellKeys = new Set();

        // Update cell data and PIXI objects
        for (const cell of cells) {
            const pos = this.parsePosition(cell.position);
            if (!pos) continue;

            const key = `${pos[0]},${pos[1]}`;
            newCellKeys.add(key);
            this.cellData.set(key, cell);
            this.drawCell(cell, pos);
        }

        // Remove old cell objects
        for (const key of this.cellObjects.keys()) {
            if (!newCellKeys.has(key)) {
                const { background, text } = this.cellObjects.get(key);
                if (background) this.cellContainer.removeChild(background);
                if (text) this.textContainer.removeChild(text);
                this.cellObjects.delete(key);
                this.cellData.delete(key);
            }
        }

        // Draw organisms
        this.markerContainer.removeChildren();
        if (organisms) {
            const allDps = new Map();
            for (const organism of organisms) {
                const color = this.getOrganismColor(organism.organismId);
                const dpsArray = Array.isArray(organism.dps) ? organism.dps : (organism.dpsJson ? JSON.parse(organism.dpsJson) : null);
                const activeDpIndex = organism.activeDpIndex || 0;

                if (Array.isArray(dpsArray)) {
                    dpsArray.forEach((dpPos, idx) => {
                        const key = Array.isArray(dpPos) ? dpPos.join('|') : String(dpPos);
                        const isActive = idx === activeDpIndex;
                        if (!allDps.has(key)) {
                            allDps.set(key, { pos: dpPos, color: color, indices: [], isActive: false });
                        }
                        const dpData = allDps.get(key);
                        dpData.indices.push(idx);
                        if (isActive) dpData.isActive = true;
                    });
                }
            }

            for (const organism of organisms) {
                this.drawOrganismWithoutDps(organism, selectedOrganismId === organism.organismId);
            }

            for (const dpData of allDps.values()) {
                this.drawDp(dpData.pos, dpData.color, dpData.indices, dpData.isActive);
            }
        }
    }

    drawCell(cell, pos) {
        const key = `${pos[0]},${pos[1]}`;
        let { background, text } = this.cellObjects.get(key) || {};

        const x = pos[0] * this.config.cellSize;
        const y = pos[1] * this.config.cellSize;

        // Draw background
        if (!background) {
            background = new PIXI.Graphics();
            background.position.set(x, y);
            this.cellContainer.addChild(background);
        }
        background.clear();
        background.rect(0, 0, this.config.cellSize, this.config.cellSize);
        background.fill(this.getBackgroundColorForType(cell.type));

        // Draw text
        const shouldHaveText = (cell.type === this.config.typeCode && (cell.value !== 0 || cell.ownerId !== 0)) || cell.type !== this.config.typeCode;
        if (shouldHaveText) {
            let label;
            if (cell.type === this.config.typeCode) {
                label = (cell.opcodeName && typeof cell.opcodeName === 'string') ? cell.opcodeName : String(cell.value);
            } else {
                label = cell.value.toString();
            }

            if (!text) {
                text = new PIXI.Text({
                    text: label,
                    style: { ...this.cellFont, fill: this.getTextColorForType(cell.type) }
                });
                text.anchor.set(0.5);
                text.position.set(x + this.config.cellSize / 2, y + this.config.cellSize / 2);
                this.textContainer.addChild(text);
            } else {
                text.text = label;
                text.style.fill = this.getTextColorForType(cell.type);
            }
        } else if (text) {
            this.textContainer.removeChild(text);
            text = null;
        }

        this.cellObjects.set(key, { background, text });
    }

    drawOrganismWithoutDps(organism, isSelected) {
        const pos = this.parsePosition(organism.positionJson);
        if (!pos) return;

        const color = this.getOrganismColor(organism.organismId);
        const x = pos[0] * this.config.cellSize;
        const y = pos[1] * this.config.cellSize;

        const graphics = new PIXI.Graphics();
        graphics.stroke({
            width: 2.5,
            color: organism.energy <= 0 ? this.config.colorDead : color
        });
        graphics.drawRect(x, y, this.config.cellSize, this.config.cellSize);
        this.markerContainer.addChild(graphics);

        let dvVec = null;
        if (organism.dvJson) dvVec = this.parsePosition(organism.dvJson);
        else if (Array.isArray(organism.dv)) dvVec = organism.dv;

        if (Array.isArray(dvVec)) {
            const dvMarker = new PIXI.Graphics();
            const edgeOffset = this.config.cellSize * 0.5;
            const cx = x + this.config.cellSize / 2;
            const cy = y + this.config.cellSize / 2;
            const px = cx + Math.sign(dvVec[0] || 0) * edgeOffset * 0.9;
            const py = cy + Math.sign(dvVec[1] || 0) * edgeOffset * 0.9;
            dvMarker.circle(px, py, this.config.cellSize * 0.1);
            dvMarker.fill(color);
            this.markerContainer.addChild(dvMarker);
        }
    }

    drawDp(pos, color, indices, isActive = false) {
        const x = pos[0] * this.config.cellSize;
        const y = pos[1] * this.config.cellSize;

        const graphics = new PIXI.Graphics();
        graphics.stroke({ width: 2.0, color });
        // Dashed line not directly supported in PIXI.Graphics, so we draw a solid line as a fallback.
        // For a true dashed line, a more complex solution like PIXI.TilingSprite or a custom shader would be needed.
        // Given the constraints, a solid line is a reasonable substitute.
        graphics.drawRect(x, y, this.config.cellSize, this.config.cellSize);

        if (isActive) {
            graphics.stroke({ width: 1.0, color });
            const spacing = 4;
            for (let i = -this.config.cellSize; i < this.config.cellSize; i += spacing) {
                graphics.moveTo(x + Math.max(0, i), y + Math.max(0, -i));
                graphics.lineTo(x + Math.min(this.config.cellSize, i + this.config.cellSize), y + Math.min(this.config.cellSize, this.config.cellSize - i));
            }
        }
        this.markerContainer.addChild(graphics);

        if (Array.isArray(indices) && indices.length > 0) {
            const text = new PIXI.Text({
                text: indices.join(','),
                style: {
                    ...this.cellFont,
                    fill: color,
                    fontWeight: '900',
                    fontSize: this.config.cellSize * 0.45,
                    dropShadow: true,
                    dropShadowColor: 'rgba(0,0,0,0.8)',
                    dropShadowBlur: 1,
                    dropShadowAngle: Math.PI / 4,
                    dropShadowDistance: 1,
                }
            });
            text.anchor.set(0.5);
            text.position.set(x + this.config.cellSize / 2, y + this.config.cellSize / 2);
            this.markerContainer.addChild(text);
        }
    }

    parsePosition(posString) {
        try {
            if (posString && posString.startsWith('[')) return JSON.parse(posString);
            return null;
        } catch (e) { return null; }
    }

    getOrganismColor(id) {
        if (!this.organismColorMap.has(id)) {
            const paletteIndex = (id - 1) % WebGLRenderer.organismColorPalette.length;
            this.organismColorMap.set(id, WebGLRenderer.organismColorPalette[paletteIndex]);
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

    setupTooltipEvents() {
        this.app.view.addEventListener('mousemove', (event) => this.handleMouseMove(event));
        this.app.view.addEventListener('mouseleave', () => {
            this.hideTooltip();
            if (this.tooltipTimeout) clearTimeout(this.tooltipTimeout);
            this.lastMousePosition = null;
        });
    }

    handleMouseMove(event) {
        const rect = this.app.view.getBoundingClientRect();
        const x = event.clientX - rect.left;
        const y = event.clientY - rect.top;

        const worldWidth = this.app.screen.width / this.app.renderer.resolution;
        const worldHeight = this.app.screen.height / this.app.renderer.resolution;

        if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight) {
            this.hideTooltip();
            return;
        }

        const gridX = Math.floor(x / this.config.cellSize);
        const gridY = Math.floor(y / this.config.cellSize);
        const currentPos = `${gridX},${gridY}`;

        if (this.lastMousePosition === currentPos) return;

        this.hideTooltip();
        this.lastMousePosition = currentPos;

        if (this.tooltipTimeout) clearTimeout(this.tooltipTimeout);

        const cell = this.findCellAt(gridX, gridY);
        if (cell) {
            this.tooltipTimeout = setTimeout(() => this.showTooltip(event, cell, gridX, gridY), this.tooltipDelay);
        }
    }

    findCellAt(gridX, gridY) {
        const key = `${gridX},${gridY}`;
        return this.cellData.get(key) || { type: this.config.typeCode, value: 0, ownerId: 0, opcodeName: 'NOP' };
    }

    showTooltip(event, cell, gridX, gridY) {
        if (!this.tooltip) return;

        const typeName = this.getTypeName(cell.type);
        const opcodeInfo = (cell.opcodeName && (cell.ownerId !== 0 || cell.value !== 0)) ? `(${cell.opcodeName})` : '';

        this.tooltip.innerHTML = `
            <span class="tooltip-coords">[${gridX}|${gridY}]</span>
            <span class="tooltip-type">${typeName}:${cell.value}${opcodeInfo}</span>
            <span class="tooltip-separator">•</span>
            <span class="tooltip-owner">Owner: ${cell.ownerId || 0}</span>
        `;

        const { clientX, clientY } = event;
        const { innerWidth, innerHeight } = window;
        const { offsetWidth, offsetHeight } = this.tooltip;

        let left = clientX;
        let top = clientY - 8 - offsetHeight;

        if (left + offsetWidth > innerWidth) left = innerWidth - offsetWidth - 10;
        if (left < 10) left = 10;
        if (top < 10) top = clientY + 20;

        this.tooltip.style.left = `${left}px`;
        this.tooltip.style.top = `${top}px`;
        this.tooltip.classList.add('show');
    }

    hideTooltip() {
        if (this.tooltip) {
            this.tooltip.classList.remove('show');
        }
    }
}
