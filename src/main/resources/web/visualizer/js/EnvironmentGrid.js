/**
 * PIXI.js-based renderer for environment grid with viewport-based loading.
 */
class EnvironmentGrid {
    constructor(container, config, environmentApi) {
        this.container = container;
        this.config = config;
        this.environmentApi = environmentApi;
        
        // Region tracking for loaded regions
        this.loadedRegions = new Set();
        
        // Request management
        this.currentAbortController = null;
        
        // PIXI.js setup
        this.app = new PIXI.Application();
        this.cellData = new Map();
        this.cellObjects = new Map();
        
        // Tooltip
        this.tooltip = document.getElementById('cell-tooltip');
        this.tooltipTimeout = null;
        this.lastMousePosition = null;
        this.tooltipDelay = 300;
        
        // Font configuration
        this.cellFont = {
            fontFamily: 'Monospaced, "Courier New"',
            fontSize: this.config.cellSize * 0.4,
            fill: 0xffffff,
            align: 'center',
        };
        
        // Molecule type mapping (String → int)
        this.typeMapping = {
            'CODE': 0,
            'DATA': 1,
            'ENERGY': 2,
            'STRUCTURE': 3
        };
    }
    
    /**
     * Initializes the PIXI.js application.
     */
    async init() {
        await this.app.init({
            width: (this.config.worldSize?.[0] ?? 100) * this.config.cellSize,
            height: (this.config.worldSize?.[1] ?? 30) * this.config.cellSize,
            backgroundColor: this.config.backgroundColor,
            autoDensity: true,
            resolution: window.devicePixelRatio || 1,
            powerPreference: 'high-performance',
            antialias: false,
            backgroundAlpha: 1,
        });
        
        this.container.innerHTML = '';
        this.container.appendChild(this.app.view);
        
        this.cellContainer = new PIXI.Container();
        this.textContainer = new PIXI.Container();
        
        this.app.stage.addChild(this.cellContainer, this.textContainer);
        
        this.setupTooltipEvents();
        this.setupScrollListener();
    }
    
    /**
     * Updates the world shape and resizes the canvas.
     * 
     * @param {number[]} worldShape - World dimensions [width, height]
     */
    updateWorldShape(worldShape) {
        if (worldShape && Array.isArray(worldShape) && worldShape.length >= 2) {
            this.config.worldSize = worldShape;
            this.app.renderer.resize(
                worldShape[0] * this.config.cellSize,
                worldShape[1] * this.config.cellSize
            );
        }
    }
    
    /**
     * Calculates the visible region in grid coordinates.
     * 
     * @returns {{x1: number, x2: number, y1: number, y2: number}} Viewport region
     */
    getVisibleRegion() {
        const scrollContainer = this.container;
        const scrollLeft = scrollContainer.scrollLeft;
        const scrollTop = scrollContainer.scrollTop;
        const viewWidth = this.app.view.clientWidth;
        const viewHeight = this.app.view.clientHeight;
        
        const x1 = Math.floor(scrollLeft / this.config.cellSize);
        const x2 = Math.ceil((scrollLeft + viewWidth) / this.config.cellSize);
        const y1 = Math.floor(scrollTop / this.config.cellSize);
        const y2 = Math.ceil((scrollTop + viewHeight) / this.config.cellSize);
        
        return { x1, x2, y1, y2 };
    }
    
    /**
     * Loads environment data for the current viewport.
     * 
     * @param {number} tick - Current tick number
     * @param {string|null} runId - Optional run ID
     */
    async loadViewport(tick, runId = null) {
        const viewport = this.getVisibleRegion();
        const regionKey = `${tick}-${viewport.x1}-${viewport.x2}-${viewport.y1}-${viewport.y2}`;
        
        // Check if region is already loaded
        if (this.loadedRegions.has(regionKey)) {
            return; // Skip API call
        }
        
        // Abort previous request if still pending
        if (this.currentAbortController) {
            this.currentAbortController.abort();
        }
        
        // Create new AbortController for this request
        this.currentAbortController = new AbortController();
        
        try {
            const data = await this.environmentApi.fetchEnvironmentData(tick, viewport, {
                runId: runId,
                signal: this.currentAbortController.signal
            });
            
            // Mark region as loaded
            this.loadedRegions.add(regionKey);
            
            // Render cells
            this.renderCells(data.cells);
            
        } catch (error) {
            // Ignore AbortError (request was cancelled)
            if (error.name === 'AbortError') {
                return;
            }
            // Log other errors
            console.error('Failed to load environment data:', error);
        } finally {
            // Clear abort controller if request completed
            if (this.currentAbortController && !this.currentAbortController.signal.aborted) {
                this.currentAbortController = null;
            }
        }
    }
    
    /**
     * Clears all loaded regions and cell data (e.g., when switching ticks).
     */
    clear() {
        this.loadedRegions.clear();
        
        // Remove all cell objects
        for (const { background, text } of this.cellObjects.values()) {
            if (background) this.cellContainer.removeChild(background);
            if (text) this.textContainer.removeChild(text);
        }
        this.cellObjects.clear();
        this.cellData.clear();
    }
    
    /**
     * Renders cells from API response.
     * 
     * @param {Array} cells - Array of cell data from API
     */
    renderCells(cells) {
        const newCellKeys = new Set();
        
        for (const cell of cells) {
            const coords = cell.coordinates;
            if (!Array.isArray(coords) || coords.length < 2) continue;
            
            const key = `${coords[0]},${coords[1]}`;
            newCellKeys.add(key);
            
            // Convert moleculeType string to int
            const typeId = this.typeMapping[cell.moleculeType] ?? 0;
            
            // Store cell data
            const cellData = {
                type: typeId,
                value: cell.moleculeValue,
                ownerId: cell.ownerId,
                opcodeName: cell.opcodeName || null
            };
            this.cellData.set(key, cellData);
            
            // Draw cell
            this.drawCell(cellData, coords);
        }
        
        // Remove cells that are no longer in the response
        // (Note: We keep all rendered cells for viewport-based rendering)
    }
    
    /**
     * Draws a single cell.
     * 
     * @param {Object} cell - Cell data with type, value, ownerId, opcodeName
     * @param {number[]} pos - Cell coordinates [x, y]
     */
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
                // Use opcodeName if available, otherwise moleculeValue
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
    
    /**
     * Gets background color for molecule type.
     * 
     * @param {number} typeId - Molecule type ID
     * @returns {number} PIXI color value
     */
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
    
    /**
     * Gets text color for molecule type.
     * 
     * @param {number} typeId - Molecule type ID
     * @returns {number} PIXI color value
     */
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
    
    /**
     * Gets type name for molecule type.
     * 
     * @param {number} typeId - Molecule type ID
     * @returns {string} Type name
     */
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
    
    /**
     * Sets up scroll listener for viewport-based loading.
     */
    setupScrollListener() {
        let scrollTimeout = null;
        this.container.addEventListener('scroll', () => {
            // Debounce scroll events
            if (scrollTimeout) {
                clearTimeout(scrollTimeout);
            }
            scrollTimeout = setTimeout(() => {
                // Trigger viewport load (will be called by AppController with current tick)
                if (this.onViewportChange) {
                    this.onViewportChange();
                }
            }, 100);
        });
    }
    
    /**
     * Sets up tooltip events for cell information display.
     */
    setupTooltipEvents() {
        this.app.view.addEventListener('mousemove', (event) => this.handleMouseMove(event));
        this.app.view.addEventListener('mouseleave', () => {
            this.hideTooltip();
            if (this.tooltipTimeout) clearTimeout(this.tooltipTimeout);
            this.lastMousePosition = null;
        });
    }
    
    /**
     * Handles mouse move events for tooltip display.
     * 
     * @param {MouseEvent} event - Mouse event
     */
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
    
    /**
     * Finds cell at grid coordinates.
     * 
     * @param {number} gridX - Grid X coordinate
     * @param {number} gridY - Grid Y coordinate
     * @returns {Object|null} Cell data or default empty cell
     */
    findCellAt(gridX, gridY) {
        const key = `${gridX},${gridY}`;
        return this.cellData.get(key) || { 
            type: this.config.typeCode, 
            value: 0, 
            ownerId: 0, 
            opcodeName: 'NOP' 
        };
    }
    
    /**
     * Shows tooltip with cell information.
     * 
     * @param {MouseEvent} event - Mouse event
     * @param {Object} cell - Cell data
     * @param {number} gridX - Grid X coordinate
     * @param {number} gridY - Grid Y coordinate
     */
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
    
    /**
     * Hides the tooltip.
     */
    hideTooltip() {
        if (this.tooltip) {
            this.tooltip.classList.remove('show');
        }
    }
}

// Export for global availability
window.EnvironmentGrid = EnvironmentGrid;

