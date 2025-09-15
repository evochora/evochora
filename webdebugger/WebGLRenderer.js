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
        this.organismGraphics = new Map(); // Cache for organism graphics
        this.dpGraphics = new Map(); // Cache for DP graphics
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
            // Performance optimizations
            powerPreference: 'high-performance',
            antialias: false,
            backgroundAlpha: 1,
        });
        this.container.innerHTML = '';
        this.container.appendChild(this.app.view);

        this.cellContainer = new PIXI.Container();
        this.textContainer = new PIXI.Container();
        this.markerContainer = new PIXI.Container();

        this.app.stage.addChild(this.cellContainer, this.textContainer, this.markerContainer);

        this.setupTooltipEvents();
        this.setupRightClickScrolling();
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

        // Draw organisms and DPs synchronously for better performance
        this.drawOrganismsAndDps(organisms, selectedOrganismId);
    }

    drawOrganismsAndDps(organisms, selectedOrganismId) {

        // Clear existing graphics but keep them for reuse
        this.markerContainer.removeChildren();
        
        // Clear graphics cache for organisms that no longer exist
        const currentOrganismIds = new Set(organisms?.map(o => o.organismId) || []);
        for (const [id, graphics] of this.organismGraphics.entries()) {
            if (!currentOrganismIds.has(id)) {
                this.organismGraphics.delete(id);
            }
        }
        
        // Clear DP graphics cache for DPs that no longer exist
        const currentDpKeys = new Set();
        if (organisms) {
            for (const organism of organisms) {
                const dpsArray = Array.isArray(organism.dps) ? organism.dps : (organism.dpsJson ? JSON.parse(organism.dpsJson) : null);
                if (Array.isArray(dpsArray)) {
                    dpsArray.forEach(dpPos => {
                        const key = Array.isArray(dpPos) ? dpPos.join('|') : String(dpPos);
                        currentDpKeys.add(key);
                    });
                }
            }
        }
        for (const [key, graphics] of this.dpGraphics.entries()) {
            if (!currentDpKeys.has(key)) {
                this.dpGraphics.delete(key);
            }
        }
        
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

            // Draw organisms first
            for (const organism of organisms) {
                this.drawOrganismWithoutDps(organism, selectedOrganismId === organism.organismId);
            }

            // Draw DPs on top
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
        let pos;
        if (organism.positionJson) {
            pos = this.parsePosition(organism.positionJson);
        } else if (organism.position) {
            pos = organism.position;
        }

        if (!pos || !Array.isArray(pos)) return;

        const color = this.getOrganismColor(organism.organismId);
        const x = pos[0] * this.config.cellSize;
        const y = pos[1] * this.config.cellSize;

        // Draw arrow pointing in DP direction instead of rectangle outline
        let dvVec = null;
        if (organism.dvJson) dvVec = this.parsePosition(organism.dvJson);
        else if (Array.isArray(organism.dv)) dvVec = organism.dv;

        // Reuse existing graphics or create new one
        let organismGraphics = this.organismGraphics.get(organism.organismId);
        if (!organismGraphics) {
            organismGraphics = new PIXI.Graphics();
            this.organismGraphics.set(organism.organismId, organismGraphics);
        }
        
        organismGraphics.clear();
        const strokeColor = organism.energy <= 0 ? this.convertColorToPixi(this.config.colorDead) : color;
        
        // Draw semi-transparent background for the whole cell
        organismGraphics.beginFill(strokeColor, 0.2);
        organismGraphics.drawRect(x, y, this.config.cellSize, this.config.cellSize);
        organismGraphics.endFill();
        
        if (Array.isArray(dvVec) && (dvVec[0] !== 0 || dvVec[1] !== 0)) {
            // Calculate arrow position and direction - make it fill the whole cell
            const cx = x + this.config.cellSize / 2;
            const cy = y + this.config.cellSize / 2;
            
            // Normalize direction vector
            const length = Math.sqrt(dvVec[0] * dvVec[0] + dvVec[1] * dvVec[1]);
            const dirX = dvVec[0] / length;
            const dirY = dvVec[1] / length;
            
            // Calculate arrow points to fill the entire cell
            const halfCellSize = this.config.cellSize / 2;
            
            // Arrow tip position (at the edge of the cell in direction of movement)
            const tipX = cx + dirX * halfCellSize;
            const tipY = cy + dirY * halfCellSize;
            
            // Arrow base positions (at the opposite edge of the cell)
            const base1X = cx - dirX * halfCellSize + (-dirY) * halfCellSize;
            const base1Y = cy - dirY * halfCellSize + dirX * halfCellSize;
            const base2X = cx - dirX * halfCellSize - (-dirY) * halfCellSize;
            const base2Y = cy - dirY * halfCellSize - dirX * halfCellSize;
            
            // Draw arrow triangle with solid fill
            organismGraphics.beginFill(strokeColor, 0.8);
            organismGraphics.moveTo(tipX, tipY);
            organismGraphics.lineTo(base1X, base1Y);
            organismGraphics.lineTo(base2X, base2Y);
            organismGraphics.lineTo(tipX, tipY);
            organismGraphics.endFill();
        } else {
            // If no direction vector, draw a filled circle that fills most of the cell
            const cx = x + this.config.cellSize / 2;
            const cy = y + this.config.cellSize / 2;
            const radius = this.config.cellSize * 0.4;
            
            organismGraphics.beginFill(strokeColor, 0.8);
            organismGraphics.circle(cx, cy, radius);
            organismGraphics.endFill();
        }
        
        this.markerContainer.addChild(organismGraphics);
    }

    drawDp(pos, color, indices, isActive = false) {
        const x = pos[0] * this.config.cellSize;
        const y = pos[1] * this.config.cellSize;
        const dpKey = `${pos[0]},${pos[1]}`;

        // Reuse existing graphics or create new one
        let graphics = this.dpGraphics.get(dpKey);
        if (!graphics) {
            graphics = new PIXI.Graphics();
            this.dpGraphics.set(dpKey, graphics);
        }
        
        // Draw DP as a semi-transparent overlay with border
        // Use simpler rendering for better performance
        graphics.lineStyle(1.0, color, 0.8); // Thinner border for performance
        graphics.drawRect(x, y, this.config.cellSize, this.config.cellSize);
        
        if (isActive) {
            // Active DP: darker overlay
            graphics.beginFill(color, 0.3); // Semi-transparent
            graphics.drawRect(x, y, this.config.cellSize, this.config.cellSize);
            graphics.endFill();
        } else {
            // Inactive DP: lighter overlay
            graphics.beginFill(color, 0.15); // Very light overlay
            graphics.drawRect(x, y, this.config.cellSize, this.config.cellSize);
            graphics.endFill();
        }
        
        this.markerContainer.addChild(graphics);

        if (Array.isArray(indices) && indices.length > 0) {
            const text = new PIXI.Text({
                text: indices.join(','),
                style: {
                    ...this.cellFont,
                    fill: color, // Back to organism color
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

    convertColorToPixi(cssColor) {
        if (typeof cssColor === 'string' && cssColor.startsWith('#')) {
            return parseInt(cssColor.replace('#', ''), 16);
        }
        return cssColor; // Already in correct format or not a hex string
    }

    getOrganismColor(id) {
        if (!this.organismColorMap.has(id)) {
            const paletteIndex = (id - 1) % WebGLRenderer.organismColorPalette.length;
            const cssColor = WebGLRenderer.organismColorPalette[paletteIndex];
            const pixiColor = this.convertColorToPixi(cssColor);
            this.organismColorMap.set(id, pixiColor);
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

    setupRightClickScrolling() {
        let isRightClickScrolling = false;
        let lastMousePos = { x: 0, y: 0 };
        
        // Right mouse button down
        this.app.view.addEventListener('mousedown', (event) => {
            if (event.button === 2) { // Right mouse button
                isRightClickScrolling = true;
                lastMousePos = { x: event.clientX, y: event.clientY };
                this.app.view.style.cursor = 'grabbing';
                event.preventDefault();
            }
        });
        
        // Mouse move during right click
        this.app.view.addEventListener('mousemove', (event) => {
            if (isRightClickScrolling) {
                const deltaX = event.clientX - lastMousePos.x;
                const deltaY = event.clientY - lastMousePos.y;
                
                // Get the world container (parent of canvas)
                const worldContainer = this.app.view.parentElement;
                if (worldContainer) {
                    worldContainer.scrollLeft -= deltaX;
                    worldContainer.scrollTop -= deltaY;
                }
                
                lastMousePos = { x: event.clientX, y: event.clientY };
                event.preventDefault();
            }
        });
        
        // Mouse button up
        this.app.view.addEventListener('mouseup', (event) => {
            if (event.button === 2) { // Right mouse button
                isRightClickScrolling = false;
            }
            // Always reset cursor to default arrow when any mouse button is released
            this.app.view.style.cursor = 'default';
        });
        
        // Mouse leave - stop scrolling
        this.app.view.addEventListener('mouseleave', () => {
            isRightClickScrolling = false;
            this.app.view.style.cursor = 'default';
        });
        
        // Prevent context menu on right click
        this.app.view.addEventListener('contextmenu', (event) => {
            event.preventDefault();
        });
        
        // Change cursor to indicate scrollable area
        this.app.view.style.cursor = 'default';
    }

}
