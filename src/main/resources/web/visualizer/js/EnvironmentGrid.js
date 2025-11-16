/**
 * PIXI.js-based renderer for environment grid using a camera-based viewport.
 * <p>
 * Core ideas:
 * <ul>
 *   <li>Canvas is always viewport-sized (no DOM scrolling for the world).</li>
 *   <li>A logical camera (cameraX, cameraY) defines which part of the world is visible.</li>
 *   <li>Right mouse button drag pans the camera.</li>
 *   <li>Only the visible region is requested from the server (viewport-based loading).</li>
 * </ul>
 */
class EnvironmentGrid {
    constructor(container, config, environmentApi) {
        this.container = container;
        this.config = config;
        this.environmentApi = environmentApi;

        // PIXI core
        this.app = new PIXI.Application();
        this.cellContainer = null;
        this.textContainer = null;

        // World size (in cells) - set via updateWorldShape()
        this.worldWidthCells = this.config.worldSize?.[0] ?? null;
        this.worldHeightCells = this.config.worldSize?.[1] ?? null;

        // Camera (top-left of viewport in world pixels)
        this.cameraX = 0;
        this.cameraY = 0;

        // Viewport size (logical pixels)
        this.viewportWidth = 0;
        this.viewportHeight = 0;

        // Region tracking for loaded regions
        this.loadedRegions = new Set();

        // Request management
        this.currentAbortController = null;

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

        // Cell data and PIXI objects
        this.cellData = new Map();     // key: "x,y" -> {type,value,ownerId,opcodeName}
        this.cellObjects = new Map();  // key: "x,y" -> {background,text}

        // Camera panning (right mouse drag)
        this.isPanning = false;
        this.panStartX = 0;
        this.panStartY = 0;
        this.cameraStartX = 0;
        this.cameraStartY = 0;

        // Debounce for viewport-based loading
        this.viewportLoadTimeout = null;
    }

    /**
     * Initializes the PIXI.js application.
     * Canvas is sized to viewport; camera defines what part of the world is visible.
     */
    async init() {
        // Wait for layout to get viewport size
        await new Promise(resolve => {
            requestAnimationFrame(() => {
                requestAnimationFrame(resolve);
            });
        });

        this.viewportWidth = this.container.clientWidth || Math.max(window.innerWidth - 40, 400);
        this.viewportHeight = this.container.clientHeight || Math.max(window.innerHeight - 100, 300);

        const devicePixelRatio = window.devicePixelRatio || 1;

        await this.app.init({
            width: this.viewportWidth,
            height: this.viewportHeight,
            backgroundColor: this.config.backgroundColor,
            autoDensity: true,
            resolution: devicePixelRatio,
            powerPreference: 'high-performance',
            antialias: false,
            backgroundAlpha: 1,
        });

        const canvas = this.app.view;
        canvas.style.position = 'absolute';
        canvas.style.top = '0';
        canvas.style.left = '0';
        canvas.style.zIndex = '1';

        this.container.innerHTML = '';
        this.container.appendChild(canvas);

        this.cellContainer = new PIXI.Container();
        this.textContainer = new PIXI.Container();
        this.app.stage.addChild(this.cellContainer, this.textContainer);

        this.setupTooltipEvents();
        this.setupInteractionEvents();
        this.setupResizeListener();

        this.clampCameraToWorld();
        this.updateStagePosition();
    }

    /**
     * Updates the world shape.
     *
     * @param {number[]} worldShape [width,height] in cells
     */
    updateWorldShape(worldShape) {
        if (worldShape && Array.isArray(worldShape) && worldShape.length >= 2) {
            this.config.worldSize = worldShape;
            this.worldWidthCells = worldShape[0];
            this.worldHeightCells = worldShape[1];

            this.clampCameraToWorld();
            this.updateStagePosition();
            this.requestViewportLoad();
        }
    }

    /**
     * Ensures camera stays within world bounds if world size is known.
     */
    clampCameraToWorld() {
        if (this.worldWidthCells == null || this.worldHeightCells == null) {
            return;
        }

        const worldWidthPx = this.worldWidthCells * this.config.cellSize;
        const worldHeightPx = this.worldHeightCells * this.config.cellSize;

        const maxCameraX = Math.max(0, worldWidthPx - this.viewportWidth);
        const maxCameraY = Math.max(0, worldHeightPx - this.viewportHeight);

        this.cameraX = Math.min(Math.max(this.cameraX, 0), maxCameraX);
        this.cameraY = Math.min(Math.max(this.cameraY, 0), maxCameraY);
    }

    /**
     * Updates PIXI stage position based on camera position.
     */
    updateStagePosition() {
        if (!this.app || !this.app.stage) return;
        this.app.stage.x = -this.cameraX;
        this.app.stage.y = -this.cameraY;
    }

    /**
     * Calculates the visible region in grid coordinates based on camera and viewport.
     *
     * @returns {{x1: number, x2: number, y1: number, y2: number}} Viewport region in cells
     */
    getVisibleRegion() {
        const cellSize = this.config.cellSize;
        const x1 = Math.floor(this.cameraX / cellSize);
        const x2 = Math.ceil((this.cameraX + this.viewportWidth) / cellSize);
        const y1 = Math.floor(this.cameraY / cellSize);
        const y2 = Math.ceil((this.cameraY + this.viewportHeight) / cellSize);
        return { x1, x2, y1, y2 };
    }

    /**
     * Requests a viewport load via AppController (debounced).
     */
    requestViewportLoad() {
        if (!this.onViewportChange) {
            return;
        }
        if (this.viewportLoadTimeout) {
            clearTimeout(this.viewportLoadTimeout);
        }
        this.viewportLoadTimeout = setTimeout(() => {
            this.onViewportChange();
        }, 80);
    }

    /**
     * Loads environment data for the current viewport.
     *
     * @param {number} tick - Current tick number
     * @param {string|null} runId - Optional run ID
     */
    async loadViewport(tick, runId = null) {
        // Ensure viewport size is known
        if (this.viewportWidth === 0 || this.viewportHeight === 0) {
            await new Promise(resolve => {
                requestAnimationFrame(() => {
                    requestAnimationFrame(resolve);
                });
            });
            this.viewportWidth = this.container.clientWidth || this.viewportWidth || Math.max(window.innerWidth - 40, 400);
            this.viewportHeight = this.container.clientHeight || this.viewportHeight || Math.max(window.innerHeight - 100, 300);
        }

        // Track current tick (used only for potential future optimizations)
        this.currentTick = tick;

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
            
            // Render / update cells (no full clear to avoid flicker)
            this.renderCells(data.cells);
        } catch (error) {
            if (error.name === 'AbortError') {
                return;
            }
            console.error('Failed to load environment data:', error);
        } finally {
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
     * Renders cells from API response (additive / overriding, ohne Full-Clear).
     *
     * @param {Array} cells - Array of cell data from API
     */
    renderCells(cells) {
        for (const cell of cells) {
            const coords = cell.coordinates;
            if (!Array.isArray(coords) || coords.length < 2) continue;

            const key = `${coords[0]},${coords[1]}`;

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

            // Draw / update cell
            this.drawCell(cellData, coords);
        }
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
     * Sets up right-mouse-drag interaction for camera panning.
     */
    setupInteractionEvents() {
        const canvas = this.app.view;

        // Prevent context menu on right click
        canvas.addEventListener('contextmenu', (event) => {
            event.preventDefault();
        });

        canvas.addEventListener('mousedown', (event) => {
            if (event.button !== 2) return; // only right mouse button
            event.preventDefault();

            this.isPanning = true;
            this.panStartX = event.clientX;
            this.panStartY = event.clientY;
            this.cameraStartX = this.cameraX;
            this.cameraStartY = this.cameraY;

            const onMouseMove = (moveEvent) => {
                if (!this.isPanning) return;
                const dx = moveEvent.clientX - this.panStartX;
                const dy = moveEvent.clientY - this.panStartY;

                // Drag right -> show more world on the right (camera moves left)
                this.cameraX = this.cameraStartX - dx;
                this.cameraY = this.cameraStartY - dy;

                this.clampCameraToWorld();
                this.updateStagePosition();
                this.requestViewportLoad();
            };

            const onMouseUp = (upEvent) => {
                if (upEvent.button === 2) {
                    this.isPanning = false;
                    window.removeEventListener('mousemove', onMouseMove);
                    window.removeEventListener('mouseup', onMouseUp);
                }
            };

            window.addEventListener('mousemove', onMouseMove);
            window.addEventListener('mouseup', onMouseUp);
        });
    }

    /**
     * Sets up resize listener to reload viewport when container size changes.
     */
    setupResizeListener() {
        let resizeTimeout = null;
        let lastDevicePixelRatio = window.devicePixelRatio || 1;

        const handleResize = () => {
            const currentDevicePixelRatio = window.devicePixelRatio || 1;

            this.viewportWidth = this.container.clientWidth || Math.max(window.innerWidth - 40, 400);
            this.viewportHeight = this.container.clientHeight || Math.max(window.innerHeight - 100, 300);

            if (currentDevicePixelRatio !== lastDevicePixelRatio) {
                this.app.renderer.resolution = currentDevicePixelRatio;
                lastDevicePixelRatio = currentDevicePixelRatio;
            }

            this.app.renderer.resize(this.viewportWidth, this.viewportHeight);

            this.clampCameraToWorld();
            this.updateStagePosition();

            this.loadedRegions.clear();
            this.requestViewportLoad();
        };

        if (typeof ResizeObserver !== 'undefined') {
            this.resizeObserver = new ResizeObserver(() => {
                if (resizeTimeout) {
                    clearTimeout(resizeTimeout);
                }
                resizeTimeout = setTimeout(handleResize, 150);
            });
            this.resizeObserver.observe(this.container);
        } else {
            window.addEventListener('resize', () => {
                if (resizeTimeout) {
                    clearTimeout(resizeTimeout);
                }
                resizeTimeout = setTimeout(handleResize, 150);
            });
        }
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

        if (x < 0 || y < 0 || x >= rect.width || y >= rect.height) {
            this.hideTooltip();
            return;
        }

        // Convert screen coordinates to world coordinates using camera
        const worldX = x + this.cameraX;
        const worldY = y + this.cameraY;

        const gridX = Math.floor(worldX / this.config.cellSize);
        const gridY = Math.floor(worldY / this.config.cellSize);
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


