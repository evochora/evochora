/**
 * Manages the PIXI.js-based rendering of the simulation environment grid.
 *
 * This class is responsible for:
 * <ul>
 *   <li>Setting up and managing the PIXI.js canvas.</li>
 *   <li>Implementing a camera system for panning and viewport-based rendering.</li>
 *   <li>Fetching and rendering only the visible portion of the world from the API.</li>
 *   <li>Displaying cells, molecules, and organism markers (IP, DV, DP).</li>
 *   <li>Handling user interactions like panning (right-mouse drag) and tooltips.</li>
 * </ul>
 *
 * @class EnvironmentGrid
 */
class EnvironmentGrid {
    /**
     * Initializes the EnvironmentGrid instance.
     *
     * @param {HTMLElement} container - The DOM element to contain the PIXI.js canvas.
     * @param {object} config - The application configuration object.
     * @param {EnvironmentApi} environmentApi - The API client for fetching environment data.
     */
    constructor(container, config, environmentApi) {
        this.container = container;
        this.config = config;
        this.environmentApi = environmentApi;

        // PIXI core
        this.app = new PIXI.Application();
        this.cellContainer = null;
        this.textContainer = null;
        this.organismContainer = null;

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

        // Organism rendering state (filled by AppController later)
        this.currentTick = 0;
        this.currentRunId = null;
        this.currentOrganisms = [];

        // Incremental, non-flickering graphics caches for organisms
        // ipGraphics: organismId -> PIXI.Graphics (IP marker for that organism)
        // dpGraphics: "x,y" -> { graphics: PIXI.Graphics, text: PIXI.Text | null }
        this.ipGraphics = new Map();
        this.dpGraphics = new Map();
    }

    /**
     * Initializes the PIXI.js application, sets up containers, and binds event listeners.
     * The canvas is sized to the viewport, and the camera defines which part of the world is visible.
     * This method must be called after the DOM is ready.
     * @returns {Promise<void>} A promise that resolves when initialization is complete.
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
        this.organismContainer = new PIXI.Container();
        this.app.stage.addChild(this.cellContainer, this.textContainer, this.organismContainer);

        this.setupTooltipEvents();
        this.setupInteractionEvents();
        this.setupResizeListener();

        this.clampCameraToWorld();
        this.updateStagePosition();
    }

    /**
     * Updates the world shape (dimensions in cells) and adjusts the camera.
     *
     * @param {number[]} worldShape - An array representing the world size, e.g., `[width, height]`.
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
     * Ensures the camera stays within the world boundaries.
     * Prevents panning outside the defined world area.
     * @private
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
     * Centers the camera on a specific world coordinate.
     *
     * @param {number} cellX - The target X coordinate in cells.
     * @param {number} cellY - The target Y coordinate in cells.
     */
    centerOn(cellX, cellY) {
        const cellSize = this.config.cellSize;
        
        // Convert cell coordinates to pixel coordinates
        const worldX = cellX * cellSize;
        const worldY = cellY * cellSize;
        
        // Center the camera on this position
        this.cameraX = worldX - this.viewportWidth / 2;
        this.cameraY = worldY - this.viewportHeight / 2;
        
        // Clamp camera to world bounds
        this.clampCameraToWorld();
        
        // Update stage position
        this.updateStagePosition();
        
        // Trigger viewport reload to show the new region
        if (this.onViewportChange) {
            this.onViewportChange();
        }
    }

    /**
     * Calculates the visible region in grid coordinates based on camera and viewport.
     *
     * @returns {{x1: number, x2: number, y1: number, y2: number}} An object representing the visible region in cell coordinates.
     * @private
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
     * Triggers a debounced request to load data for the current viewport.
     * This is called after camera movements to avoid excessive API calls.
     * @private
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
     * Fetches and renders environment data for the current viewport from the API.
     * Manages an AbortController to cancel stale requests.
     *
     * @param {number} tick - The current tick number to load data for.
     * @param {string|null} [runId=null] - The optional run ID.
     * @returns {Promise<void>} A promise that resolves when the viewport data is loaded and rendered.
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

        // Abort previous request if still pending
        if (this.currentAbortController) {
            this.currentAbortController.abort();
        }

        // Create new AbortController for this request
        this.currentAbortController = new AbortController();

        const data = await this.environmentApi.fetchEnvironmentData(tick, viewport, {
            runId: runId,
            signal: this.currentAbortController.signal
        });

        // Mark region as loaded
        this.loadedRegions.add(regionKey);
        
        // Render / update cells (no full clear to avoid flicker) and
        // then remove cells in this region that were not touched by
        // this response (e.g., created only in a later tick).
        this.renderCellsWithCleanup(data.cells, viewport);

        // Reset abort controller if request completed successfully
        if (this.currentAbortController && !this.currentAbortController.signal.aborted) {
            this.currentAbortController = null;
        }
    }

    /**
     * Clears all rendered cell data and resets the loaded regions cache.
     * This is typically called when switching to a completely new tick.
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
     * Renders a batch of cells from an API response and cleans up stale cells.
     * This method performs an incremental update rather than a full clear to prevent flickering.
     *
     * @param {Array<object>} cells - An array of cell data objects from the API.
     * @param {{x1:number, x2:number, y1:number, y2:number}} region - The current viewport region.
     * @private
     */
    renderCellsWithCleanup(cells, region) {
        const updatedKeys = new Set();

        // First pass: update or create all cells from response
        for (const cell of cells) {
            const coords = cell.coordinates;
            if (!Array.isArray(coords) || coords.length < 2) continue;

            const key = `${coords[0]},${coords[1]}`;
            updatedKeys.add(key);

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

        // Second pass: remove cells in this region that weren't updated
        const { x1, x2, y1, y2 } = region;

        for (const [key, entry] of this.cellObjects.entries()) {
            if (updatedKeys.has(key)) {
                continue; // touched in this tick for this region
            }

            const parts = key.split(",");
            if (parts.length !== 2) {
                continue;
            }
            const cx = Number.parseInt(parts[0], 10);
            const cy = Number.parseInt(parts[1], 10);
            if (Number.isNaN(cx) || Number.isNaN(cy)) {
                continue;
            }

            if (cx >= x1 && cx < x2 && cy >= y1 && cy < y2) {
                const { background, text } = entry;
                if (background) {
                    this.cellContainer.removeChild(background);
                }
                if (text) {
                    this.textContainer.removeChild(text);
                }
                this.cellObjects.delete(key);
                this.cellData.delete(key);
            }
        }
    }

    /**
     * Draws a single cell.
     *
     * @param {object} cell - Cell data object with `type`, `value`, `ownerId`, etc.
     * @param {number[]} pos - The cell's coordinates `[x, y]`.
     * @private
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
     * Gets the appropriate background color for a given molecule type ID.
     *
     * @param {number} typeId - The molecule type ID.
     * @returns {number} The PIXI color value (e.g., 0xff0000).
     * @private
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
     * Gets the appropriate text color for a given molecule type ID.
     *
     * @param {number} typeId - The molecule type ID.
     * @returns {number} The PIXI color value.
     * @private
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
     * Gets the string name for a given molecule type ID.
     *
     * @param {number} typeId - The molecule type ID.
     * @returns {string} The name of the type (e.g., "CODE").
     * @private
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
     * Sets up event listeners for camera panning (right-mouse drag).
     * @private
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
     * Sets up a resize listener to automatically adjust the canvas and reload data
     * when the container size changes.
     * @private
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
     * Sets up mouse move and leave events for displaying cell tooltips.
     * @private
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
     * Handles mouse move events to determine when to show a tooltip.
     *
     * @param {MouseEvent} event - The mouse move event.
     * @private
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
     * Finds the cell data at a specific grid coordinate.
     *
     * @param {number} gridX - The grid X coordinate.
     * @param {number} gridY - The grid Y coordinate.
     * @returns {object|null} The cell data object, or a default object for an empty cell.
     * @private
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
     * Displays the tooltip with formatted information about a cell.
     *
     * @param {MouseEvent} event - The mouse event, used for positioning.
     * @param {object} cell - The cell data object.
     * @param {number} gridX - The cell's X coordinate.
     * @param {number} gridY - The cell's Y coordinate.
     * @private
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
     * @private
     */
    hideTooltip() {
        if (this.tooltip) {
            this.tooltip.classList.remove('show');
        }
    }

    /**
     * Clears the cached organism state. Called when the tick changes to ensure
     * markers from the previous tick are not carried over.
     */
    clearOrganisms() {
        // Reset cached organism list; actual graphics cleanup happens
        // incrementally within renderOrganisms() based on the new tick's data.
        this.currentOrganisms = [];
    }

    /**
     * Renders organism markers (IP, DV, and DPs) for the current tick.
     * This method performs incremental updates to the organism graphics layer to avoid
     * flickering and is safe to call on every frame.
     *
     * @param {Array<object>} organismsForTick - An array of organism summary objects for the current tick.
     */
    renderOrganisms(organismsForTick) {
        if (!Array.isArray(organismsForTick)) {
            this.currentOrganisms = [];
            return;
        }
        this.currentOrganisms = organismsForTick;

        if (!this.organismContainer) {
            return;
        }

        const cellSize = this.config.cellSize;

        // Track which organisms and DP keys exist in this tick
        const newOrganismIds = new Set();
        for (const organism of organismsForTick) {
            if (organism && typeof organism.organismId === 'number') {
                newOrganismIds.add(organism.organismId);
            }
        }

        // Remove IP graphics for organisms that no longer exist in this tick
        for (const [orgId, g] of this.ipGraphics.entries()) {
            if (!newOrganismIds.has(orgId)) {
                g.clear();
                this.organismContainer.removeChild(g);
                this.ipGraphics.delete(orgId);
            }
        }

        const ensureIpGraphics = (organismId) => {
            let graphics = this.ipGraphics.get(organismId);
            if (!graphics) {
                graphics = new PIXI.Graphics();
                this.ipGraphics.set(organismId, graphics);
                this.organismContainer.addChild(graphics);
            }
            return graphics;
        };

        // First pass: draw IPs for all organisms
        for (const organism of organismsForTick) {
            if (!organism || !Array.isArray(organism.ip) || !Array.isArray(organism.dv)) {
                continue;
            }

            const organismId = organism.organismId;
            const ipX = organism.ip[0];
            const ipY = organism.ip[1];

            // --- IP: one marker per organism ---
            const ipGraphics = ensureIpGraphics(organismId);
            ipGraphics.clear();

            const ipColor = this._getOrganismColor(organismId, organism.energy);

            const ipCellX = ipX * cellSize;
            const ipCellY = ipY * cellSize;

            // Semi-transparent background fill
            ipGraphics.beginFill(ipColor, 0.2);
            ipGraphics.drawRect(ipCellX, ipCellY, cellSize, cellSize);
            ipGraphics.endFill();

            const dvx = organism.dv[0];
            const dvy = organism.dv[1];

            if (dvx !== 0 || dvy !== 0) {
                const length = Math.sqrt(dvx * dvx + dvy * dvy) || 1;
                const dirX = dvx / length;
                const dirY = dvy / length;

                const cx = ipCellX + cellSize / 2;
                const cy = ipCellY + cellSize / 2;
                const half = cellSize / 2;

                const tipX = cx + dirX * half;
                const tipY = cy + dirY * half;

                const base1X = cx - dirX * half + (-dirY) * half;
                const base1Y = cy - dirY * half + dirX * half;
                const base2X = cx - dirX * half - (-dirY) * half;
                const base2Y = cy - dirY * half - dirX * half;

                ipGraphics.beginFill(ipColor, 0.8);
                ipGraphics.moveTo(tipX, tipY);
                ipGraphics.lineTo(base1X, base1Y);
                ipGraphics.lineTo(base2X, base2Y);
                ipGraphics.lineTo(tipX, tipY);
                ipGraphics.endFill();
            } else {
                // No DV: draw a filled circle in the cell center
                const cx = ipCellX + cellSize / 2;
                const cy = ipCellY + cellSize / 2;
                const radius = cellSize * 0.4;

                ipGraphics.beginFill(ipColor, 0.8);
                ipGraphics.circle(cx, cy, radius);
                ipGraphics.endFill();
            }
        }

        // Second pass: aggregate all DPs across all organisms
        const aggregatedDps = new Map(); // key "x,y" -> { indices:[], isActive:boolean, color:number }

        for (const org of organismsForTick) {
            if (!org || !Array.isArray(org.dataPointers)) {
                continue;
            }
            const orgId = org.organismId;
            const orgColor = this._getOrganismColor(orgId, org.energy);
            const orgActiveIndex = typeof org.activeDpIndex === "number" ? org.activeDpIndex : 0;

            org.dataPointers.forEach((dp, idx) => {
                if (!Array.isArray(dp) || dp.length < 2) {
                    return;
                }
                const dpX = dp[0];
                const dpY = dp[1];
                const cellKey = `${dpX},${dpY}`;

                let entry = aggregatedDps.get(cellKey);
                if (!entry) {
                    entry = {
                        indices: [],
                        isActive: false,
                        color: orgColor,
                        x: dpX,
                        y: dpY
                    };
                    aggregatedDps.set(cellKey, entry);
                }
                entry.indices.push(idx);
                if (idx === orgActiveIndex) {
                    entry.isActive = true;
                    // Prefer color of organism with active DP
                    entry.color = orgColor;
                }
            });
        }

        // Third pass: render all aggregated DPs
        const seenDpKeys = new Set();

        for (const [cellKey, entry] of aggregatedDps.entries()) {
            const dpX = entry.x;
            const dpY = entry.y;
            const color = entry.color;
            const isActive = entry.isActive;
            const indices = entry.indices;

            let dpEntry = this.dpGraphics.get(cellKey);
            if (!dpEntry) {
                const graphics = new PIXI.Graphics();
                const text = new PIXI.Text({
                    text: "",
                    style: {
                        ...this.cellFont,
                        fontSize: this.config.cellSize * 0.45,
                        fontWeight: "900",
                        fill: color,
                        dropShadow: true,
                        dropShadowColor: "rgba(0,0,0,0.8)",
                        dropShadowBlur: 1,
                        dropShadowAngle: Math.PI / 4,
                        dropShadowDistance: 1
                    }
                });
                text.anchor.set(0.5);

                dpEntry = { graphics, text };
                this.dpGraphics.set(cellKey, dpEntry);
                this.organismContainer.addChild(graphics);
                this.organismContainer.addChild(text);
            }

            const g = dpEntry.graphics;
            const label = dpEntry.text;

            g.clear();

            const x = dpX * cellSize;
            const y = dpY * cellSize;

            // Draw border and fill, making active DP more prominent
            const borderAlpha = isActive ? 1.0 : 0.8;
            const borderWidth = isActive ? 2.0 : 1.0;
            const fillAlpha = isActive ? 0.45 : 0.15;

            g.lineStyle(borderWidth, color, borderAlpha);
            g.drawRect(x, y, cellSize, cellSize);

            g.beginFill(color, fillAlpha);
            g.drawRect(x, y, cellSize, cellSize);
            g.endFill();

            // Update label with indices (comma-separated)
            if (label) {
                label.text = indices.join(",");
                label.style.fill = color;
                label.position.set(x + cellSize / 2, y + cellSize / 2);
            }

            seenDpKeys.add(cellKey);
        }

        // Remove DP graphics that are no longer used in this tick
        for (const [cellKey, dpEntry] of this.dpGraphics.entries()) {
            if (!seenDpKeys.has(cellKey)) {
                const { graphics, text } = dpEntry;
                if (graphics) {
                    graphics.clear();
                    this.organismContainer.removeChild(graphics);
                }
                if (text) {
                    this.organismContainer.removeChild(text);
                }
                this.dpGraphics.delete(cellKey);
            }
        }
    }

    /**
     * Internal helper to assign a stable, deterministic color per organism ID.
     * Returns a dimmed color for organisms with zero or less energy.
     *
     * @param {number} organismId - The ID of the organism.
     * @param {number} energy - The current energy of the organism.
     * @returns {number} The PIXI color value.
     * @private
     */
    _getOrganismColor(organismId, energy) {
        // Simple deterministic palette similar to old WebGLRenderer.organismColorPalette
        if (!this._organismColorMap) {
            this._organismColorMap = new Map();
            this._organismPalette = [
                0x32cd32, 0x1e90ff, 0xdc143c, 0xffd700,
                0xffa500, 0x9370db, 0x00ffff
            ];
        }

        if (!this._organismColorMap.has(organismId)) {
            const idx = (organismId - 1) % this._organismPalette.length;
            this._organismColorMap.set(organismId, this._organismPalette[idx]);
        }

        const baseColor = this._organismColorMap.get(organismId);

        // If energy <= 0, fall back to a dimmed grayish color to indicate death
        if (typeof energy === 'number' && energy <= 0) {
            return 0x555555;
        }
        return baseColor;
    }
}

// Export for global availability
window.EnvironmentGrid = EnvironmentGrid;


