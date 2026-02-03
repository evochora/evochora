import { loadingManager } from './ui/LoadingManager.js';

/**
 * Manages the PIXI.js-based rendering of the simulation environment grid.
 * This class acts as a context for different rendering strategies (e.g., detailed vs. zoomed-out)
 * and handles all shared logic like camera control, user interaction, and API communication.
 *
 * @class EnvironmentGrid
 */
export class EnvironmentGrid {
    /**
     * @param {HTMLElement} container - The DOM element to contain the PIXI.js canvas.
     * @param {object} config - The application configuration object.
     * @param {EnvironmentApi} environmentApi - The API client for fetching environment data.
     */
    constructor(controller, container, config, environmentApi) {
        this.controller = controller;
        this.container = container;
        this.config = config;
        this.environmentApi = environmentApi;

        // --- PIXI Core & Scene Graph ---
        this.app = new PIXI.Application();
        // Main containers for different layers
        this.gridBackground = new PIXI.Graphics(); // Background for empty cells
        this.cellContainer = new PIXI.Container();
        this.textContainer = new PIXI.Container();
        this.organismContainer = new PIXI.Container();

        // --- World & Camera State ---
        this.worldWidthCells = this.config.worldSize?.[0] ?? null;
        this.worldHeightCells = this.config.worldSize?.[1] ?? null;
        this.cameraX = 0;
        this.cameraY = 0;
        this.viewportWidth = 0;
        this.viewportHeight = 0;
        
        // --- Zoom & Rendering Strategy ---
        this.isZoomedOut = false;
        this.zoomOutScale = 1;  // 1-4 px per cell in zoomed-out mode
        // The detailed renderer is the default strategy
        this.detailedRenderer = new DetailedRendererStrategy(this);
        this.zoomedOutRenderer = new ZoomedOutRendererStrategy(this);
        this.activeRenderer = this.detailedRenderer;

        // --- Data & Request Management ---
        this.loadedRegions = new Set();
        this.currentAbortController = null;
        this.currentTick = 0;
        this.currentRunId = null;
        this.currentOrganisms = [];
        this.cellData = new Map(); // key: "x,y" -> {type,value,ownerId,opcodeName}
        this._rawCells = null;      // Raw cells from API (for async cellData building)
        this._cellDataReady = false; // Flag: true when cellData map is fully built

        // --- Prefetch Management ---
        this._prefetchAbortController = null;  // Separate controller for background prefetch
        this._fullWorldPrefetched = false;     // Track if full world prefetch completed for current tick

        // --- Zoomed-Out Viewport Caching is handled by ZoomedOutRendererStrategy ---
        // The renderer preserves its pixel buffer across pans and tracks loaded regions

        // --- UI & Interaction State ---
        this.tooltip = document.getElementById('cell-tooltip');
        this.tooltipTimeout = null;
        this.lastMousePosition = null;
        this.tooltipDelay = 300;
        this.isPanning = false;
        this.panStartX = 0;
        this.panStartY = 0;
        this.cameraStartX = 0;
        this.cameraStartY = 0;
        this.viewportLoadTimeout = null;
        this._stageUpdatePending = false; // RAF throttle flag for stage position updates

        // --- Virtual Scrollbar Elements ---
        this.vScrollTrack = document.getElementById('scrollbar-track-v');
        this.vScrollThumb = document.getElementById('scrollbar-thumb-v');
        this.hScrollTrack = document.getElementById('scrollbar-track-h');
        this.hScrollThumb = document.getElementById('scrollbar-thumb-h');
    }

    /**
     * Initializes the PIXI.js application, sets up containers, and binds event listeners.
     * @returns {Promise<void>}
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

        this.app.stage.addChild(this.gridBackground, this.cellContainer, this.textContainer, this.organismContainer);

        this.detailedRenderer.init();
        this.zoomedOutRenderer.init();
        
        this.setupTooltipEvents();
        this.setupInteractionEvents();
        this.setupResizeListener();

        this.clampCameraToWorld();
        this.updateStagePosition();
        this.updateScrollbars();
        this.setupScrollbarInteraction();
    }

    /**
     * Toggles the zoom state and switches the active rendering strategy.
     * Preserves the viewport center position across zoom changes.
     * @param {boolean} isZoomedOut - The new zoom state.
     */
    setZoom(isZoomedOut) {
        // Calculate viewport center in cell coordinates (before zoom change)
        const oldCellSize = this.getCurrentCellSize();
        const centerCellX = (this.cameraX + this.viewportWidth / 2) / oldCellSize;
        const centerCellY = (this.cameraY + this.viewportHeight / 2) / oldCellSize;

        // Apply zoom state change
        this.isZoomedOut = isZoomedOut;
        this.activeRenderer = this.isZoomedOut ? this.zoomedOutRenderer : this.detailedRenderer;

        // Calculate new camera position to preserve center (after zoom change)
        const newCellSize = this.getCurrentCellSize();
        this.cameraX = centerCellX * newCellSize - this.viewportWidth / 2;
        this.cameraY = centerCellY * newCellSize - this.viewportHeight / 2;

        // Clear everything, as all scales and positions are now invalid.
        this.clear();
        this.updateGridBackground();
        this.clampCameraToWorld();
        this.updateStagePosition();

        // Invalidate caches when switching modes (free memory)
        // Note: clear() already calls clearCache() on both renderers,
        // but we explicitly clear the "other" renderer to ensure memory is freed
        if (isZoomedOut) {
            this.detailedRenderer.clearCache();
        } else {
            this.zoomedOutRenderer.clearCache();
        }
    }
    
    getCurrentCellSize() {
        return this.isZoomedOut ? this.zoomOutScale : this.config.cellSize;
    }

    /**
     * Sets the zoom-out scale (pixels per cell in zoomed-out mode).
     * Preserves the viewport center position across scale changes.
     * @param {number} scale - The new scale (1-10).
     */
    setZoomOutScale(scale) {
        const newScale = Math.max(1, Math.min(10, Math.round(scale)));
        if (this.zoomOutScale === newScale) return;

        if (this.isZoomedOut) {
            // Calculate viewport center in cell coordinates (before scale change)
            const oldScale = this.zoomOutScale;
            const centerCellX = (this.cameraX + this.viewportWidth / 2) / oldScale;
            const centerCellY = (this.cameraY + this.viewportHeight / 2) / oldScale;

            // Apply new scale
            this.zoomOutScale = newScale;
            localStorage.setItem('evochora-zoom-out-scale', String(newScale));

            // Calculate new camera position to preserve center
            this.cameraX = centerCellX * newScale - this.viewportWidth / 2;
            this.cameraY = centerCellY * newScale - this.viewportHeight / 2;

            this.zoomedOutRenderer.clearCache();
            this.updateGridBackground();
            this.clampCameraToWorld();
            this.updateStagePosition();
        } else {
            // Not in zoomed-out mode, just store the value
            this.zoomOutScale = newScale;
            localStorage.setItem('evochora-zoom-out-scale', String(newScale));
        }
    }

    /**
     * Gets the current zoom-out scale.
     * @returns {number} The current scale (1-4).
     */
    getZoomOutScale() {
        return this.zoomOutScale;
    }

    /**
     * Updates the world shape (dimensions in cells) and adjusts the camera.
     * @param {number[]} worldShape - An array representing the world size, e.g., `[width, height]`.
     */
    updateWorldShape(worldShape) {
        if (worldShape && Array.isArray(worldShape) && worldShape.length >= 2) {
            this.config.worldSize = worldShape;
            this.worldWidthCells = worldShape[0];
            this.worldHeightCells = worldShape[1];

            this.updateGridBackground();
            this.clampCameraToWorld();
            this.updateStagePosition();
            this.requestViewportLoad();
            this.updateScrollbars();
        }
    }
    
    /**
     * Draws the grid background with empty cell color.
     * This ensures empty areas show the correct color instead of canvas background.
     * @private
     */
    updateGridBackground() {
        if (this.worldWidthCells == null || this.worldHeightCells == null) return;
        
        const cellSize = this.getCurrentCellSize();
        const worldWidthPx = this.worldWidthCells * cellSize;
        const worldHeightPx = this.worldHeightCells * cellSize;
        
        this.gridBackground.clear();
        this.gridBackground.rect(0, 0, worldWidthPx, worldHeightPx);
        this.gridBackground.fill(this.config.colorEmptyBg);
    }

    /**
     * Ensures the camera stays within the world boundaries.
     * Allows scrolling 50px beyond the grid edges to show the border area.
     * @private
     */
    clampCameraToWorld() {
        if (this.worldWidthCells == null || this.worldHeightCells == null) {
            return;
        }

        const cellSize = this.getCurrentCellSize();
        const worldWidthPx = this.worldWidthCells * cellSize;
        const worldHeightPx = this.worldHeightCells * cellSize;
        
        // Allow 50px margin beyond the grid for visual border
        const margin = 50;
        const minCameraX = -margin;
        const minCameraY = -margin;
        const maxCameraX = Math.max(0, worldWidthPx - this.viewportWidth + margin);
        const maxCameraY = Math.max(0, worldHeightPx - this.viewportHeight + margin);

        this.cameraX = Math.min(Math.max(this.cameraX, minCameraX), maxCameraX);
        this.cameraY = Math.min(Math.max(this.cameraY, minCameraY), maxCameraY);
    }

    /**
     * Updates PIXI stage position based on camera position.
     */
    updateStagePosition() {
        if (!this.app || !this.app.stage) return;
        this.app.stage.x = -this.cameraX;
        this.app.stage.y = -this.cameraY;
        this.updateScrollbars();
    }

    /**
     * Schedules a stage position update for the next animation frame.
     * Throttles rapid updates (e.g., from high-frequency mouse events) to max once per frame.
     * @private
     */
    _scheduleStageUpdate() {
        if (this._stageUpdatePending) return;
        this._stageUpdatePending = true;
        requestAnimationFrame(() => {
            this.clampCameraToWorld();
            this.updateStagePosition();
            this._stageUpdatePending = false;
            // Notify camera moved (for immediate visual feedback like minimap)
            if (this.onCameraMoved) {
                this.onCameraMoved();
            }
        });
    }

    /**
     * Calculates the visible region in grid coordinates based on camera and viewport.
     * @returns {{x1: number, x2: number, y1: number, y2: number}}
     * @private
     */
    getVisibleRegion() {
        const cellSize = this.getCurrentCellSize();
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
     * Fetches and renders environment data for the current viewport.
     * @param {number} tick - The current tick number to load data for.
     * @param {string|null} [runId=null] - The optional run ID.
     * @param {boolean} [includeMinimap=false] - Whether to include minimap data in the response.
     * @returns {Promise<{minimap?: {width: number, height: number, cellTypes: Uint8Array}}>} Result with optional minimap data.
     */
    async loadViewport(tick, runId = null, includeMinimap = false) {
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

        this.currentTick = tick;
        const viewport = this.getVisibleRegion();

        // --- Check if viewport is already loaded (both modes) ---
        const renderer = this.activeRenderer;

        // Invalidate if tick or run changed
        if (renderer._loadedTick !== tick || renderer._loadedRunId !== runId) {
            renderer.clearCache();
            renderer._loadedTick = tick;
            renderer._loadedRunId = runId;
            this._fullWorldPrefetched = false; // Reset prefetch state on tick change
        }

        // Note: We intentionally do NOT abort running prefetch when viewport changes.
        // Prefetch loads in background and will complete, making future pans faster.

        // Check if all cells in viewport have been loaded
        if (renderer.isRegionFullyLoaded(viewport)) {
            // Even if cache hit, trigger prefetch
            this._triggerPrefetch(tick, runId, viewport);

            // On cache hit, still fetch minimap if requested (needed for initial load)
            if (includeMinimap) {
                const minimapController = new AbortController();
                try {
                    const data = await this.environmentApi.fetchEnvironmentData(tick, viewport, {
                        runId: runId,
                        signal: minimapController.signal,
                        includeMinimap: true
                    });
                    return { minimap: data.minimap };
                } catch (error) {
                    if (error.name !== 'AbortError') {
                        console.warn('[EnvironmentGrid] Failed to fetch minimap on cache hit:', error.message);
                    }
                }
            }
            return {};
        }

        // Abort previous request
        if (this.currentAbortController) {
            this.currentAbortController.abort();
        }
        this.currentAbortController = new AbortController();

        const data = await this.environmentApi.fetchEnvironmentData(tick, viewport, {
            runId: runId,
            signal: this.currentAbortController.signal,
            includeMinimap: includeMinimap
        });

        // --- Update Zoomed-Out Cache ---
        // The renderer tracks loaded regions internally when renderCells is called

        // Store raw cells and start async map building for tooltips
        this._rawCells = data.cells;
        this._buildCellDataAsync();  // Non-blocking, runs in background

        // --- Timing: Render cells ---
        const renderStart = performance.now();
        
        // Delegate rendering to the active strategy
        this.renderCellsWithCleanup(data.cells, viewport);
        
        const renderTime = performance.now() - renderStart;
        
        // Log post-fetch timing (complements EnvironmentApi profiling)
        console.debug(`[EnvironmentGrid] Tick ${tick}`, {
            renderMs: renderTime.toFixed(1),
            cells: data.cells.length
        });

        // Reset abort controller
        if (this.currentAbortController && !this.currentAbortController.signal.aborted) {
            this.currentAbortController = null;
        }

        // Trigger prefetch based on mode
        this._triggerPrefetch(tick, runId, viewport);

        // Return minimap data if present
        return { minimap: data.minimap };
    }

    /**
     * Triggers background prefetch based on current mode:
     * - Zoomed-Out: Capped ring prefetch (max 2000x2000 cells)
     * - Zoomed-In: Ring prefetch (expanding outward from viewport)
     * @param {number} tick - The current tick.
     * @param {string|null} runId - The current run ID.
     * @param {{x1: number, y1: number, x2: number, y2: number}} viewport - Current viewport.
     * @private
     */
    _triggerPrefetch(tick, runId, viewport) {
        if (this.isZoomedOut) {
            // Capped ring prefetch for zoomed-out mode (max 2000x2000 cells)
            this._triggerCappedRingPrefetch(tick, runId, viewport, 2000);
        } else {
            this._triggerRingPrefetch(tick, runId, viewport, 1);
        }
    }

    /**
     * Triggers a capped ring prefetch for zoomed-out mode.
     * Expands from viewport but limits total region size.
     * @param {number} tick - The current tick.
     * @param {string|null} runId - The current run ID.
     * @param {{x1: number, y1: number, x2: number, y2: number}} viewport - Current viewport.
     * @param {number} maxSize - Maximum width/height in cells.
     * @private
     */
    _triggerCappedRingPrefetch(tick, runId, viewport, maxSize) {
        // Skip if a prefetch is already running (let it complete)
        if (this._prefetchAbortController) return;

        // Skip if world size unknown
        if (!this.worldWidthCells || !this.worldHeightCells) return;

        // Calculate viewport center
        const viewportCenterX = (viewport.x1 + viewport.x2) / 2;
        const viewportCenterY = (viewport.y1 + viewport.y2) / 2;

        // Calculate prefetch region centered on viewport, capped at maxSize
        const halfWidth = Math.min(maxSize / 2, this.worldWidthCells / 2);
        const halfHeight = Math.min(maxSize / 2, this.worldHeightCells / 2);

        const expandedRegion = {
            x1: Math.max(0, Math.floor(viewportCenterX - halfWidth)),
            y1: Math.max(0, Math.floor(viewportCenterY - halfHeight)),
            x2: Math.min(this.worldWidthCells, Math.ceil(viewportCenterX + halfWidth)),
            y2: Math.min(this.worldHeightCells, Math.ceil(viewportCenterY + halfHeight))
        };

        // Skip if region is already fully loaded
        if (this.zoomedOutRenderer.isRegionFullyLoaded(expandedRegion)) {
            return;
        }

        // Use requestIdleCallback for low-priority prefetch
        requestIdleCallback(async () => {
            // Verify state hasn't changed
            if (!this.isZoomedOut) return;
            if (this.zoomedOutRenderer._loadedTick !== tick) return;

            // Create dedicated abort controller for prefetch
            this._prefetchAbortController = new AbortController();

            try {
                const data = await this.environmentApi.fetchEnvironmentData(tick, expandedRegion, {
                    runId: runId,
                    signal: this._prefetchAbortController.signal,
                    includeMinimap: false,
                    showLoading: false  // Prefetch should not trigger loading indicator
                });

                // Verify we're still in the same state before rendering
                if (!this.isZoomedOut || this.zoomedOutRenderer._loadedTick !== tick) {
                    return;
                }

                // Render when browser is idle to avoid blocking user interactions
                requestIdleCallback(() => {
                    if (!this.isZoomedOut || this.zoomedOutRenderer._loadedTick !== tick) return;
                    // Pass viewport for centering when texture limits require clamping
                    this.zoomedOutRenderer.renderCells(data.cells, expandedRegion, viewport);
                }, { timeout: 100 });

            } catch (error) {
                if (error.name !== 'AbortError') {
                    console.warn('[Prefetch] Failed:', error.message);
                }
            } finally {
                this._prefetchAbortController = null;
            }
        }, { timeout: 100 }); // Short timeout - execute soon but yield to user interactions
    }

    /**
     * Triggers a ring prefetch for zoomed-in mode.
     * Expands outward from the current viewport in rings.
     * @param {number} tick - The current tick.
     * @param {string|null} runId - The current run ID.
     * @param {{x1: number, y1: number, x2: number, y2: number}} viewport - Current viewport.
     * @param {number} ringNumber - Ring expansion factor (1 = +1 viewport around current).
     * @private
     */
    _triggerRingPrefetch(tick, runId, viewport, ringNumber) {
        // Skip if a prefetch is already running (let it complete)
        if (this._prefetchAbortController) return;

        // Skip if world size unknown
        if (!this.worldWidthCells || !this.worldHeightCells) return;

        // Calculate expanded region (ring around viewport)
        const viewportWidth = viewport.x2 - viewport.x1;
        const viewportHeight = viewport.y2 - viewport.y1;
        const expansion = ringNumber;

        const expandedRegion = {
            x1: Math.max(0, viewport.x1 - viewportWidth * expansion),
            y1: Math.max(0, viewport.y1 - viewportHeight * expansion),
            x2: Math.min(this.worldWidthCells, viewport.x2 + viewportWidth * expansion),
            y2: Math.min(this.worldHeightCells, viewport.y2 + viewportHeight * expansion)
        };

        // Skip if expanded region is already fully loaded
        if (this.detailedRenderer.isRegionFullyLoaded(expandedRegion)) {
            return;
        }

        // Use requestIdleCallback for low-priority prefetch
        requestIdleCallback(async () => {
            // Verify state hasn't changed
            if (this.isZoomedOut) return;
            if (this.detailedRenderer._loadedTick !== tick) return;

            // Create dedicated abort controller for prefetch
            this._prefetchAbortController = new AbortController();

            try {
                const data = await this.environmentApi.fetchEnvironmentData(tick, expandedRegion, {
                    runId: runId,
                    signal: this._prefetchAbortController.signal,
                    includeMinimap: false,
                    showLoading: false  // Prefetch should not trigger loading indicator
                });

                // Verify we're still in the same state before rendering
                if (this.isZoomedOut || this.detailedRenderer._loadedTick !== tick) {
                    return;
                }

                // Render when browser is idle to avoid blocking user interactions
                requestIdleCallback(() => {
                    if (this.isZoomedOut || this.detailedRenderer._loadedTick !== tick) return;
                    this.detailedRenderer.renderCells(data.cells, expandedRegion);
                }, { timeout: 100 });

            } catch (error) {
                if (error.name !== 'AbortError') {
                    console.warn('[Prefetch] Failed:', error.message);
                }
            } finally {
                this._prefetchAbortController = null;
            }
        }, { timeout: 500 }); // Longer timeout for ring prefetch - less urgent
    }

    /**
     * Clears all rendered artifacts. Delegates to the active renderer.
     */
    clear() {
        // Force-clear BOTH renderers to prevent artifacts when switching states
        this.detailedRenderer.clear();
        this.zoomedOutRenderer.clear();
    }
    
    /**
     * Renders a batch of cells. Delegates to the active renderer.
     * @param {Array<object>} cells - An array of cell data objects from the API.
     * @param {{x1:number, x2:number, y1:number, y2:number}} region - The current viewport region.
     * @private
     */
    renderCellsWithCleanup(cells, region) {
        if (loadingManager) loadingManager.incrementTasks();
        try {
            this.activeRenderer.renderCells(cells, region);
        } finally {
            if (loadingManager) loadingManager.decrementTasks();
        }
    }

    /**
     * Centers the camera on a specific world coordinate.
     * 
     * @param {number} cellX - The target X coordinate in cells.
     * @param {number} cellY - The target Y coordinate in cells.
     */
    centerOn(cellX, cellY) {
        const cellSize = this.getCurrentCellSize();

        // Convert cell coordinates to pixel coordinates
        const worldX = cellX * cellSize;
        const worldY = cellY * cellSize;

        // Center the camera on this position
        this.cameraX = worldX - this.viewportWidth / 2;
        this.cameraY = worldY - this.viewportHeight / 2;

        // Use throttled update (same as panning) to avoid blocking during minimap drag
        this._scheduleStageUpdate();
        this.requestViewportLoad();
    }

    /**
     * Calculates the visible region in grid coordinates based on camera and viewport.
     *
     * @returns {{x1: number, x2: number, y1: number, y2: number}} An object representing the visible region in cell coordinates.
     * @private
     */
    getVisibleRegion() {
        const cellSize = this.getCurrentCellSize();
        const x1 = Math.floor(this.cameraX / cellSize);
        const x2 = Math.ceil((this.cameraX + this.viewportWidth) / cellSize);
        const y1 = Math.floor(this.cameraY / cellSize);
        const y2 = Math.ceil((this.cameraY + this.viewportHeight) / cellSize);
        return { x1, x2, y1, y2 };
    }

    /**
     * Returns the viewport bounds in world (cell) coordinates.
     * Used by the minimap to show the currently visible area.
     *
     * @returns {{x: number, y: number, width: number, height: number}} Viewport bounds in cell coordinates.
     */
    getViewportBounds() {
        const region = this.getVisibleRegion();
        return {
            x: region.x1,
            y: region.y1,
            width: region.x2 - region.x1,
            height: region.y2 - region.y1
        };
    }

    /**
     * Renders organism markers. Delegates to the active renderer.
     * @param {Array<object>} organismsForTick - An array of organism summary objects.
     */
    renderOrganisms(organismsForTick) {
        if (!Array.isArray(organismsForTick)) {
            this.currentOrganisms = [];
            return;
        }
        this.currentOrganisms = organismsForTick;

        if (loadingManager) loadingManager.incrementTasks();
        try {
            this.activeRenderer.renderOrganisms(organismsForTick);
        } finally {
            if (loadingManager) loadingManager.decrementTasks();
        }
    }

    /**
     * Sets up event listeners for camera panning (left-mouse drag) and future clicks.
     * @private
     */
    setupInteractionEvents() {
        const canvas = this.app.view;
        const DRAG_THRESHOLD = 5; // Pixels the mouse must move to initiate a drag

        let isPotentialDrag = false;

        // Prevent context menu on right click (we keep this for usability)
        canvas.addEventListener('contextmenu', (event) => {
            event.preventDefault();
        });

        canvas.addEventListener('mousedown', (event) => {
            if (event.button !== 0) return; // Only handle left mouse button
            event.preventDefault();

            // Remove focus from any input field when clicking on the grid
            if (document.activeElement && document.activeElement.tagName === 'INPUT') {
                document.activeElement.blur();
            }

            isPotentialDrag = true;
            this.isPanning = false; // Reset panning state
            this.panStartX = event.clientX;
            this.panStartY = event.clientY;
            this.cameraStartX = this.cameraX;
            this.cameraStartY = this.cameraY;

            const onMouseMove = (moveEvent) => {
                if (!isPotentialDrag) return;

                const dx = moveEvent.clientX - this.panStartX;
                const dy = moveEvent.clientY - this.panStartY;

                // Check if we've moved past the threshold
                if (!this.isPanning && Math.sqrt(dx * dx + dy * dy) > DRAG_THRESHOLD) {
                    this.isPanning = true; // Start panning
                }

                if (this.isPanning) {
                    this.cameraX = this.cameraStartX - dx;
                    this.cameraY = this.cameraStartY - dy;

                    this._scheduleStageUpdate();
                    this.requestViewportLoad();
                }
            };

            const onMouseUp = (upEvent) => {
                if (upEvent.button !== 0) return; // Only react to left mouse up

                if (!this.isPanning && isPotentialDrag) {
                    // --- FUTURE CLICK LOGIC GOES HERE ---
                    const rect = canvas.getBoundingClientRect();
                    const worldX = (upEvent.clientX - rect.left) + this.cameraX;
                    const worldY = (upEvent.clientY - rect.top) + this.cameraY;
                    const cellX = Math.floor(worldX / this.config.cellSize);
                    const cellY = Math.floor(worldY / this.config.cellSize);
                    // Cell click detected - cellX, cellY available for future use
                }
                
                // Cleanup
                isPotentialDrag = false;
                this.isPanning = false;
                window.removeEventListener('mousemove', onMouseMove);
                window.removeEventListener('mouseup', onMouseUp);
            };

            window.addEventListener('mousemove', onMouseMove);
            window.addEventListener('mouseup', onMouseUp);
        });
    }

    /**
     * Sets up event listeners to make the virtual scrollbars interactive.
     * Allows users to drag the scrollbar thumbs to pan the camera.
     * @private
     */
    setupScrollbarInteraction() {
        if (!this.hScrollThumb || !this.vScrollThumb) return;
        
        // Margin for scrolling beyond grid edges (must match clampCameraToWorld)
        const margin = 50;

        // --- Horizontal Scrollbar Interaction ---
        this.hScrollThumb.addEventListener('mousedown', (e) => {
            e.preventDefault();
            const startX = e.clientX;
            const startCameraX = this.cameraX;
            const trackWidth = this.hScrollTrack.clientWidth;
            const worldWidthPx = this.worldWidthCells * this.getCurrentCellSize();
            const scrollableWidth = worldWidthPx + 2 * margin;

            const onMouseMove = (moveEvent) => {
                const dx = moveEvent.clientX - startX;
                // Convert pixel delta on scrollbar to pixel delta in world
                const cameraDeltaX = (dx / trackWidth) * scrollableWidth;
                this.cameraX = startCameraX + cameraDeltaX;

                this._scheduleStageUpdate();
                this.requestViewportLoad(); // Debounced load
            };

            const onMouseUp = () => {
                window.removeEventListener('mousemove', onMouseMove);
                window.removeEventListener('mouseup', onMouseUp);
            };

            window.addEventListener('mousemove', onMouseMove);
            window.addEventListener('mouseup', onMouseUp);
        });

        // --- Vertical Scrollbar Interaction ---
        this.vScrollThumb.addEventListener('mousedown', (e) => {
            e.preventDefault();
            const startY = e.clientY;
            const startCameraY = this.cameraY;
            const trackHeight = this.vScrollTrack.clientHeight;
            const worldHeightPx = this.worldHeightCells * this.getCurrentCellSize();
            const scrollableHeight = worldHeightPx + 2 * margin;

            const onMouseMove = (moveEvent) => {
                const dy = moveEvent.clientY - startY;
                // Convert pixel delta on scrollbar to pixel delta in world
                const cameraDeltaY = (dy / trackHeight) * scrollableHeight;
                this.cameraY = startCameraY + cameraDeltaY;

                this._scheduleStageUpdate();
                this.requestViewportLoad(); // Debounced load
            };

            const onMouseUp = () => {
                window.removeEventListener('mousemove', onMouseMove);
                window.removeEventListener('mouseup', onMouseUp);
            };

            window.addEventListener('mousemove', onMouseMove);
            window.addEventListener('mouseup', onMouseUp);
        });

        // --- Horizontal Track Click (jump to position) ---
        this.hScrollTrack.addEventListener('click', (e) => {
            // Ignore if clicking on thumb
            if (e.target === this.hScrollThumb) return;

            const trackRect = this.hScrollTrack.getBoundingClientRect();
            const clickX = e.clientX - trackRect.left;
            const trackWidth = this.hScrollTrack.clientWidth;
            const worldWidthPx = this.worldWidthCells * this.getCurrentCellSize();
            const scrollableWidth = worldWidthPx + 2 * margin;

            // Calculate camera position from click position
            this.cameraX = (clickX / trackWidth) * scrollableWidth - margin;
            this.clampCameraToWorld();
            this.updateStagePosition();
            this.requestViewportLoad();
        });

        // --- Vertical Track Click (jump to position) ---
        this.vScrollTrack.addEventListener('click', (e) => {
            // Ignore if clicking on thumb
            if (e.target === this.vScrollThumb) return;

            const trackRect = this.vScrollTrack.getBoundingClientRect();
            const clickY = e.clientY - trackRect.top;
            const trackHeight = this.vScrollTrack.clientHeight;
            const worldHeightPx = this.worldHeightCells * this.getCurrentCellSize();
            const scrollableHeight = worldHeightPx + 2 * margin;

            // Calculate camera position from click position
            this.cameraY = (clickY / trackHeight) * scrollableHeight - margin;
            this.clampCameraToWorld();
            this.updateStagePosition();
            this.requestViewportLoad();
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
     * Updates the position and size of the virtual scrollbars.
     * This should be called whenever the camera, world size, or viewport size changes.
     * @private
     */
    updateScrollbars() {
        if (!this.hScrollTrack || !this.vScrollTrack) return;

        const cellSize = this.getCurrentCellSize();
        const worldWidthPx = this.worldWidthCells * cellSize;
        const worldHeightPx = this.worldHeightCells * cellSize;
        
        // Margin for scrolling beyond grid edges (must match clampCameraToWorld)
        const margin = 50;
        
        // Total scrollable range including margins
        const scrollableWidth = worldWidthPx + 2 * margin;
        const scrollableHeight = worldHeightPx + 2 * margin;

        // --- Horizontal Scrollbar ---
        if (worldWidthPx > this.viewportWidth) {
            this.hScrollTrack.style.display = 'block';
            const trackWidth = this.hScrollTrack.clientWidth;

            const thumbWidth = (this.viewportWidth / scrollableWidth) * trackWidth;
            // Offset cameraX by margin since camera can go negative
            const thumbX = ((this.cameraX + margin) / scrollableWidth) * trackWidth;

            this.hScrollThumb.style.width = `${Math.max(thumbWidth, 10)}px`; // min width 10px
            this.hScrollThumb.style.left = `${Math.max(0, thumbX)}px`;
        } else {
            this.hScrollTrack.style.display = 'none';
        }

        // --- Vertical Scrollbar ---
        if (worldHeightPx > this.viewportHeight) {
            this.vScrollTrack.style.display = 'block';
            const trackHeight = this.vScrollTrack.clientHeight;

            const thumbHeight = (this.viewportHeight / scrollableHeight) * trackHeight;
            // Offset cameraY by margin since camera can go negative
            const thumbY = ((this.cameraY + margin) / scrollableHeight) * trackHeight;

            this.vScrollThumb.style.height = `${Math.max(thumbHeight, 10)}px`; // min height 10px
            this.vScrollThumb.style.top = `${Math.max(0, thumbY)}px`;
        } else {
            this.vScrollTrack.style.display = 'none';
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

        const cellSize = this.getCurrentCellSize();
        const worldX = x + this.cameraX;
        const worldY = y + this.cameraY;

        const gridX = Math.floor(worldX / cellSize);
        const gridY = Math.floor(worldY / cellSize);

        // --- NEW: Boundary Check ---
        // Do not show tooltip if the cursor is outside the defined world grid
        if (
            this.worldWidthCells === null || this.worldHeightCells === null ||
            gridX < 0 || gridX >= this.worldWidthCells ||
            gridY < 0 || gridY >= this.worldHeightCells
        ) {
            this.hideTooltip();
            return;
        }
        // --- End of Boundary Check ---

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
     * Builds the cellData map asynchronously in chunks to avoid blocking the UI.
     * Tooltips are unavailable until the map is fully built.
     * @private
     */
    async _buildCellDataAsync() {
        if (!this._rawCells) return;

        this._cellDataReady = false;
        this.cellData.clear();

        const CHUNK_SIZE = 50000;
        const cells = this._rawCells;

        for (let i = 0; i < cells.length; i += CHUNK_SIZE) {
            const end = Math.min(i + CHUNK_SIZE, cells.length);

            for (let j = i; j < end; j++) {
                const cell = cells[j];
                const coords = cell.coordinates;
                if (!Array.isArray(coords) || coords.length < 2) continue;
                const key = `${coords[0]},${coords[1]}`;
                this.cellData.set(key, {
                    type: this.detailedRenderer.typeMapping[cell.moleculeType] ?? 0,
                    value: cell.moleculeValue,
                    ownerId: cell.ownerId,
                    opcodeName: cell.opcodeName || null,
                    opcodeId: cell.opcodeId,
                    marker: cell.marker || 0
                });
            }

            // Yield to browser between chunks to keep UI responsive
            await new Promise(resolve => setTimeout(resolve, 0));
        }

        this._cellDataReady = true;
    }
    
    /**
     * Finds the cell data at a specific grid coordinate.
     *
     * @param {number} gridX - The grid X coordinate.
     * @param {number} gridY - The grid Y coordinate.
     * @returns {object|null} The cell data object, null if map not ready, or default for empty cell.
     * @private
     */
    findCellAt(gridX, gridY) {
        // Return null if cellData map is still being built
        if (!this._cellDataReady) {
            return null;
        }

        const key = `${gridX},${gridY}`;
        return this.cellData.get(key) || {
            type: 0,
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
        // For unknown opcodes (??), show full ID in tooltip
        let opcodeInfo = '';
        if (cell.opcodeName && (cell.ownerId !== 0 || cell.value !== 0)) {
            if (cell.opcodeName === '??' && cell.opcodeId >= 0) {
                opcodeInfo = `(Unknown: ${cell.opcodeId})`;
            } else {
                opcodeInfo = `(${cell.opcodeName})`;
            }
        }
        const markerInfo = cell.marker ? ` M:${cell.marker}` : '';

        this.tooltip.innerHTML = `
            <span class="tooltip-coords">[${gridX}|${gridY}]</span>
            <span class="tooltip-type">${typeName}:${cell.value}${opcodeInfo}</span>
            <span class="tooltip-separator">•</span>
            <span class="tooltip-owner">Owner: ${cell.ownerId || 0}${markerInfo}</span>
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
            case C.typeLabel: return 'LABEL';
            default: return 'UNKNOWN';
        }
    }

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


// ===================================================================================
// == RENDERER STRATEGIES
// ===================================================================================

/**
 * Abstract base class for a rendering strategy.
 * Provides shared viewport caching logic via a loaded mask.
 */
class BaseRendererStrategy {
    constructor(grid) {
        this.grid = grid;
        this.config = grid.config;

        // --- Viewport Caching: Shared by all strategies ---
        this._loadedMask = null;       // Uint8Array, 1 = cell loaded, 0 = not loaded
        this._loadedTick = null;       // Current tick for cache invalidation
        this._loadedRunId = null;      // Current run for cache invalidation
    }

    init() { /* no-op */ }
    clear() { /* no-op */ }
    renderCells(_cells, _region) { /* no-op */ }
    renderOrganisms(_organisms) { /* no-op */ }

    /**
     * Clears the loaded mask (called when tick or run changes).
     * Subclasses may override to clear additional cached data.
     */
    clearCache() {
        this._loadedMask = null;
        this._loadedTick = null;
        this._loadedRunId = null;
    }

    /**
     * Checks if the given viewport is fully covered by already loaded data.
     * @param {{x1: number, y1: number, x2: number, y2: number}} viewport
     * @returns {boolean}
     */
    isRegionFullyLoaded(viewport) {
        if (!this._loadedMask) return false;

        const width = this.grid.worldWidthCells;
        const height = this.grid.worldHeightCells;

        const x1 = Math.max(0, viewport.x1);
        const y1 = Math.max(0, viewport.y1);
        const x2 = Math.min(width, viewport.x2);
        const y2 = Math.min(height, viewport.y2);

        for (let y = y1; y < y2; y++) {
            const rowOffset = y * width;
            for (let x = x1; x < x2; x++) {
                if (this._loadedMask[rowOffset + x] === 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Marks the given region as loaded in the mask.
     * @param {{x1: number, y1: number, x2: number, y2: number}} region
     * @protected
     */
    _markRegionLoaded(region) {
        const width = this.grid.worldWidthCells;
        const height = this.grid.worldHeightCells;
        const pixelCount = width * height;

        // Ensure mask exists
        if (!this._loadedMask || this._loadedMask.length !== pixelCount) {
            this._loadedMask = new Uint8Array(pixelCount);
        }

        const x1 = Math.max(0, region.x1);
        const y1 = Math.max(0, region.y1);
        const x2 = Math.min(width, region.x2);
        const y2 = Math.min(height, region.y2);

        for (let y = y1; y < y2; y++) {
            const rowOffset = y * width;
            for (let x = x1; x < x2; x++) {
                this._loadedMask[rowOffset + x] = 1;
            }
        }
    }

    _getOrganismColor(organismId, energy) {
        return this.grid._getOrganismColor(organismId, energy);
    }
}

/**
 * Renders the environment with full details: cell text, organism markers with direction, etc.
 */
class DetailedRendererStrategy extends BaseRendererStrategy {
    /** Maximum number of cells to keep in memory (LRU eviction budget) */
    static MAX_CELLS = 100_000;

    constructor(grid) {
        super(grid);
        this.cellObjects = new Map();
        this.ipGraphics = new Map();
        this.dpGraphics = new Map();

        this.typeMapping = { 'CODE': 0, 'DATA': 1, 'ENERGY': 2, 'STRUCTURE': 3, 'LABEL': 4 };
        this.cellFont = {
            fontFamily: 'Monospaced, "Courier New"',
            fontSize: this.config.cellSize * 0.4,
            fill: 0xffffff,
            align: 'center',
        };

        // LRU tracking for eviction
        this._cellAccessTime = new Map(); // key -> timestamp
    }

    clear() {
        for (const { background, text } of this.cellObjects.values()) {
            if (background) this.grid.cellContainer.removeChild(background);
            if (text) this.grid.textContainer.removeChild(text);
        }
        this.cellObjects.clear();
        this._cellAccessTime.clear();
        this.clearCache(); // From base class (clears _loadedMask)

        for (const g of this.ipGraphics.values()) {
            this.grid.organismContainer.removeChild(g);
        }
        this.ipGraphics.clear();

        for (const { graphics, text } of this.dpGraphics.values()) {
            if (graphics) this.grid.organismContainer.removeChild(graphics);
            if (text) this.grid.organismContainer.removeChild(text);
        }
        this.dpGraphics.clear();
    }
    
    renderCells(cells, region) {
        const updatedKeys = new Set();
        const now = performance.now();

        // First pass: update or create all cells from response
        for (const cell of cells) {
            const coords = cell.coordinates;
            if (!Array.isArray(coords) || coords.length < 2) continue;

            const key = `${coords[0]},${coords[1]}`;
            updatedKeys.add(key);

            // Convert raw cell to internal format and draw directly
            const cellData = {
                type: this.typeMapping[cell.moleculeType] ?? 0,
                value: cell.moleculeValue,
                ownerId: cell.ownerId,
                opcodeName: cell.opcodeName || null,
                marker: cell.marker || 0
            };
            this.drawCell(cellData, coords);

            // LRU: Update access time
            this._cellAccessTime.set(key, now);
        }

        // Second pass: remove cells in this region that weren't updated
        const { x1, x2, y1, y2 } = region;

        for (const [key, entry] of this.cellObjects.entries()) {
            if (updatedKeys.has(key)) {
                continue; // touched in this tick for this region
            }

            const parts = key.split(",");
            if (parts.length !== 2) continue;

            const cx = Number.parseInt(parts[0], 10);
            const cy = Number.parseInt(parts[1], 10);
            if (Number.isNaN(cx) || Number.isNaN(cy)) continue;

            if (cx >= x1 && cx < x2 && cy >= y1 && cy < y2) {
                const { background, text } = entry;
                if (background) this.grid.cellContainer.removeChild(background);
                if (text) this.grid.textContainer.removeChild(text);
                this.cellObjects.delete(key);
                this._cellAccessTime.delete(key);
            }
        }

        // Mark region as loaded (base class method)
        this._markRegionLoaded(region);

        // LRU Eviction: Remove oldest cells if over budget
        if (this.cellObjects.size > DetailedRendererStrategy.MAX_CELLS) {
            const excess = this.cellObjects.size - DetailedRendererStrategy.MAX_CELLS;
            this._evictOldestCells(excess);
        }
    }
    
    drawCell(cell, pos) {
        const key = `${pos[0]},${pos[1]}`;
        let { background, text } = this.cellObjects.get(key) || {};

        const cellSize = this.grid.getCurrentCellSize();
        const x = pos[0] * cellSize;
        const y = pos[1] * cellSize;

        // Draw background
        if (!background) {
            background = new PIXI.Graphics();
            background.position.set(x, y);
            this.grid.cellContainer.addChild(background);
        }
        background.clear();
        background.rect(0, 0, cellSize, cellSize);
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

            // Split long values (> 4 chars) into two lines for better readability
            if (label.length > 4) {
                const mid = Math.ceil(label.length / 2);
                label = label.slice(0, mid) + '\n' + label.slice(mid);
            }

            if (!text) {
                text = new PIXI.Text({ text: label, style: { ...this.cellFont, fill: this.getTextColorForType(cell.type) }});
                text.anchor.set(0.5);
                text.position.set(x + cellSize / 2, y + cellSize / 2);
                this.grid.textContainer.addChild(text);
            } else {
                text.text = label;
                text.style.fill = this.getTextColorForType(cell.type);
            }
        } else if (text) {
            this.grid.textContainer.removeChild(text);
            text = null;
        }

        this.cellObjects.set(key, { background, text });
    }

    /**
     * Clears the LRU cache (called when tick or run changes).
     * Overrides base class to also clear access time tracking.
     */
    clearCache() {
        super.clearCache();
        this._cellAccessTime.clear();
    }

    /**
     * Evicts the oldest (least recently accessed) cells to stay within budget.
     * Also marks evicted cells as unloaded in _loadedMask.
     * @param {number} count - Number of cells to evict.
     * @private
     */
    _evictOldestCells(count) {
        if (count <= 0) return;

        // Sort by access time, get oldest 'count' cells
        const sorted = [...this._cellAccessTime.entries()]
            .sort((a, b) => a[1] - b[1])
            .slice(0, count);

        const worldWidth = this.grid.worldWidthCells;

        for (const [key] of sorted) {
            const entry = this.cellObjects.get(key);
            if (entry) {
                if (entry.background) this.grid.cellContainer.removeChild(entry.background);
                if (entry.text) this.grid.textContainer.removeChild(entry.text);
                this.cellObjects.delete(key);
            }
            this._cellAccessTime.delete(key);

            // Mark as unloaded in _loadedMask (so it will be re-fetched if scrolled back)
            if (this._loadedMask && worldWidth > 0) {
                const parts = key.split(',');
                const x = Number.parseInt(parts[0], 10);
                const y = Number.parseInt(parts[1], 10);
                if (!Number.isNaN(x) && !Number.isNaN(y)) {
                    this._loadedMask[y * worldWidth + x] = 0;
                }
            }
        }

        console.debug(`[LRU Eviction] Removed ${sorted.length} cells, now at ${this.cellObjects.size}`);
    }

    renderOrganisms(organisms) {
        // This is the logic from the old renderOrganisms
        const self = this; // Explicitly capture the 'this' context of the strategy
        const cellSize = this.grid.getCurrentCellSize();
        const newOrganismIds = new Set(organisms.map(org => org.organismId));

        // Remove old IP graphics
        for (const [orgId, g] of this.ipGraphics.entries()) {
            if (!newOrganismIds.has(orgId)) {
                this.grid.organismContainer.removeChild(g);
                this.ipGraphics.delete(orgId);
            }
        }

        const ensureIpGraphics = (organism) => {
            let graphics = this.ipGraphics.get(organism.organismId);
            if (!graphics) {
                graphics = new PIXI.Graphics();
                this.ipGraphics.set(organism.organismId, graphics);
                this.grid.organismContainer.addChild(graphics);

                // Make it clickable
                graphics.interactive = true;
                graphics.buttonMode = true;
                graphics.on('click', (event) => {
                    event.stopPropagation();
                    self.grid.controller.selectOrganism(organism.organismId);
                });
            }
            return graphics;
        };

        // Draw IPs
        for (const organism of organisms) {
            if (!organism || !Array.isArray(organism.ip) || !Array.isArray(organism.dv)) continue;

            const { organismId, ip, dv, energy } = organism;
            const ipGraphics = ensureIpGraphics(organism);
            ipGraphics.clear();

            const ipColor = this._getOrganismColor(organismId, energy);
            const ipCellX = ip[0] * cellSize;
            const ipCellY = ip[1] * cellSize;

            ipGraphics.beginFill(ipColor, 0.2);
            ipGraphics.drawRect(ipCellX, ipCellY, cellSize, cellSize);
            ipGraphics.endFill();

            if (dv[0] !== 0 || dv[1] !== 0) {
                const length = Math.sqrt(dv[0] * dv[0] + dv[1] * dv[1]) || 1;
                const dirX = dv[0] / length;
                const dirY = dv[1] / length;
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
                const cx = ipCellX + cellSize / 2;
                const cy = ipCellY + cellSize / 2;
                const radius = cellSize * 0.4;
                ipGraphics.beginFill(ipColor, 0.8);
                ipGraphics.circle(cx, cy, radius);
                ipGraphics.endFill();
            }
        }

        // Aggregate DPs
        const aggregatedDps = new Map();
        for (const org of organisms) {
            if (!org || !Array.isArray(org.dataPointers)) continue;
            const orgColor = this._getOrganismColor(org.organismId, org.energy);
            const orgActiveIndex = typeof org.activeDpIndex === "number" ? org.activeDpIndex : 0;
            org.dataPointers.forEach((dp, idx) => {
                if (!Array.isArray(dp) || dp.length < 2) return;
                const cellKey = `${dp[0]},${dp[1]}`;
                let entry = aggregatedDps.get(cellKey);
                if (!entry) {
                    entry = { indices: [], isActive: false, color: orgColor, x: dp[0], y: dp[1], prominentOrganism: org };
                    aggregatedDps.set(cellKey, entry);
                }
                entry.indices.push(idx);
                if (idx === orgActiveIndex) {
                    entry.isActive = true;
                    entry.color = orgColor;
                    entry.prominentOrganism = org;
                }
            });
        }
        
        // Render DPs
        const seenDpKeys = new Set();
        for (const [cellKey, entry] of aggregatedDps.entries()) {
            let dpEntry = this.dpGraphics.get(cellKey);
            if (!dpEntry) {
                const graphics = new PIXI.Graphics();
                const text = new PIXI.Text({ text: "", style: { ...this.cellFont, fontSize: this.config.cellSize * 0.45, fontWeight: "900", fill: entry.color, dropShadow: true, dropShadowColor: "rgba(0,0,0,0.8)", dropShadowBlur: 1, dropShadowAngle: Math.PI / 4, dropShadowDistance: 1 }});
                text.anchor.set(0.5);
                dpEntry = { graphics, text };
                this.dpGraphics.set(cellKey, dpEntry);
                this.grid.organismContainer.addChild(graphics, text);

                // Make it clickable
                graphics.interactive = true;
                graphics.buttonMode = true;
                graphics.on('click', (event) => {
                    event.stopPropagation();
                    // On click, select the most "prominent" organism for this DP
                    const currentEntry = aggregatedDps.get(cellKey);
                    if (currentEntry && currentEntry.prominentOrganism) {
                        self.grid.controller.selectOrganism(currentEntry.prominentOrganism.organismId);
                    }
                });
            }

            const { graphics: g, text: label } = dpEntry;
            g.clear();
            const x = entry.x * cellSize;
            const y = entry.y * cellSize;
            const borderAlpha = entry.isActive ? 1.0 : 0.8;
            const borderWidth = entry.isActive ? 2.0 : 1.0;
            const fillAlpha = entry.isActive ? 0.45 : 0.15;
            g.lineStyle(borderWidth, entry.color, borderAlpha);
            g.beginFill(entry.color, fillAlpha);
            g.drawRect(x, y, cellSize, cellSize);
            g.endFill();
            if (label) {
                label.text = entry.indices.join(",");
                label.style.fill = entry.color;
                label.position.set(x + cellSize / 2, y + cellSize / 2);
            }
            seenDpKeys.add(cellKey);
        }

        // Cleanup unused DP graphics
        for (const [cellKey, dpEntry] of this.dpGraphics.entries()) {
            if (!seenDpKeys.has(cellKey)) {
                if (dpEntry.graphics) this.grid.organismContainer.removeChild(dpEntry.graphics);
                if (dpEntry.text) this.grid.organismContainer.removeChild(dpEntry.text);
                this.dpGraphics.delete(cellKey);
            }
        }
    }
    
    // Helper methods for color, etc.
    getBackgroundColorForType(typeId) {
        const C = this.config;
        switch (typeId) {
            case this.typeMapping['CODE']: return C.colorCodeBg;
            case this.typeMapping['DATA']: return C.colorDataBg;
            case this.typeMapping['ENERGY']: return C.colorEnergyBg;
            case this.typeMapping['STRUCTURE']: return C.colorStructureBg;
            case this.typeMapping['LABEL']: return C.colorLabelBg;
            default: return C.colorEmptyBg;
        }
    }

    getTextColorForType(typeId) {
        const C = this.config;
        switch (typeId) {
            case this.typeMapping['STRUCTURE']: return C.colorStructureText;
            case this.typeMapping['ENERGY']: return C.colorEnergyText;
            case this.typeMapping['DATA']: return C.colorDataText;
            case this.typeMapping['CODE']: return C.colorCodeText;
            case this.typeMapping['LABEL']: return C.colorLabelText;
            default: return C.colorText;
        }
    }
}


/**
 * Renders the environment in a zoomed-out overview mode using direct pixel buffer upload.
 * 
 * Performance: Uses Uint8Array pixel manipulation instead of PIXI.Graphics draw calls.
 * This reduces rendering time from ~3500ms to ~200ms for 1M+ cells by avoiding
 * the overhead of thousands of beginFill/drawRect/endFill operations.
 */
class ZoomedOutRendererStrategy extends BaseRendererStrategy {
    constructor(grid) {
        super(grid);
        this.textureSprite = null;
        this.offscreenCanvas = null;
        this.ipGraphics = new Map(); // Still need separate graphics for organisms
        this.dpGraphics = new Map(); // And DPs

        // Pre-compute color lookup table (hex -> {r,g,b})
        this._colorCache = new Map();

        // Persistent pixel buffer for the current viewport (viewport-based rendering)
        this._pixelBuffer = null;

        // Track the currently rendered region (for viewport-based cache validation)
        this._renderedRegion = null;
    }

    init() {
        // Defer texture creation until it's actually needed and world size is known
    }

    /**
     * Clears the persistent pixel cache and loaded mask.
     * Overrides base class to also clear the pixel buffer and rendered region.
     */
    clearCache() {
        super.clearCache();
        this._pixelBuffer = null;
        this._renderedRegion = null;
    }

    /**
     * For viewport-based rendering, a region is only "fully loaded" if it's
     * completely contained within the currently rendered region.
     * @param {{x1: number, y1: number, x2: number, y2: number}} viewport
     * @returns {boolean}
     */
    isRegionFullyLoaded(viewport) {
        if (!this._renderedRegion) return false;

        const r = this._renderedRegion;
        return viewport.x1 >= r.x1 &&
               viewport.y1 >= r.y1 &&
               viewport.x2 <= r.x2 &&
               viewport.y2 <= r.y2;
    }

    clear() {
        if (this.textureSprite) {
            this.grid.cellContainer.removeChild(this.textureSprite);
            if (this.textureSprite.texture) {
                this.textureSprite.texture.destroy(true);
            }
            this.textureSprite.destroy();
            this.textureSprite = null;
        }
        this.offscreenCanvas = null;
        this.clearCache();

        for (const g of this.ipGraphics.values()) this.grid.organismContainer.removeChild(g);
        this.ipGraphics.clear();
        for (const { graphics } of this.dpGraphics.values()) this.grid.organismContainer.removeChild(graphics);
        this.dpGraphics.clear();
    }
    
    /**
     * Converts a color (hex integer 0xRRGGBB or CSS string '#RRGGBB') to RGB components.
     * Uses caching for performance.
     * @param {number|string} color - The color value.
     * @returns {{r: number, g: number, b: number}}
     * @private
     */
    _hexToRgb(color) {
        let cached = this._colorCache.get(color);
        if (cached) return cached;
        
        let hex;
        if (typeof color === 'string') {
            // CSS string format: '#RRGGBB' or '#RGB'
            hex = parseInt(color.replace('#', ''), 16);
        } else {
            hex = color;
        }
        
        cached = {
            r: (hex >> 16) & 0xFF,
            g: (hex >> 8) & 0xFF,
            b: hex & 0xFF
        };
        this._colorCache.set(color, cached);
        return cached;
    }

    /**
     * Renders cells into a texture sprite.
     * @param {Array} cells - Array of cell data to render.
     * @param {{x1: number, y1: number, x2: number, y2: number}} region - The region to render.
     * @param {{x1: number, y1: number, x2: number, y2: number}} [viewport] - Optional viewport for centering when clamping is needed.
     */
    renderCells(cells, region, viewport) {
        const scale = this.grid.zoomOutScale;
        const { x1, y1, x2, y2 } = region;

        // Clamp region to world bounds
        const worldWidth = this.grid.worldWidthCells;
        const worldHeight = this.grid.worldHeightCells;
        if (worldWidth <= 0 || worldHeight <= 0) return;

        let clampedX1 = Math.max(0, x1);
        let clampedY1 = Math.max(0, y1);
        let clampedX2 = Math.min(worldWidth, x2);
        let clampedY2 = Math.min(worldHeight, y2);

        // WebGL texture size limit (conservative, works on most GPUs)
        const MAX_TEXTURE_SIZE = 4096;

        // Limit region size to prevent exceeding WebGL texture limits
        // Center on viewport if provided, otherwise center on region
        const maxCellsPerDim = Math.floor(MAX_TEXTURE_SIZE / scale);
        const regionWidth = clampedX2 - clampedX1;
        const regionHeight = clampedY2 - clampedY1;

        if (regionWidth > maxCellsPerDim) {
            // Use viewport center if provided, otherwise use region center
            const centerX = viewport
                ? (viewport.x1 + viewport.x2) / 2
                : (clampedX1 + clampedX2) / 2;
            clampedX1 = Math.max(0, Math.floor(centerX - maxCellsPerDim / 2));
            clampedX2 = Math.min(worldWidth, clampedX1 + maxCellsPerDim);
            // Re-adjust x1 if x2 was clamped to world edge
            if (clampedX2 - clampedX1 < maxCellsPerDim) {
                clampedX1 = Math.max(0, clampedX2 - maxCellsPerDim);
            }
        }
        if (regionHeight > maxCellsPerDim) {
            // Use viewport center if provided, otherwise use region center
            const centerY = viewport
                ? (viewport.y1 + viewport.y2) / 2
                : (clampedY1 + clampedY2) / 2;
            clampedY1 = Math.max(0, Math.floor(centerY - maxCellsPerDim / 2));
            clampedY2 = Math.min(worldHeight, clampedY1 + maxCellsPerDim);
            // Re-adjust y1 if y2 was clamped to world edge
            if (clampedY2 - clampedY1 < maxCellsPerDim) {
                clampedY1 = Math.max(0, clampedY2 - maxCellsPerDim);
            }
        }

        // Viewport-based texture dimensions (region size × scale)
        const regionWidthCells = clampedX2 - clampedX1;
        const regionHeightCells = clampedY2 - clampedY1;
        if (regionWidthCells <= 0 || regionHeightCells <= 0) return;

        const textureWidth = regionWidthCells * scale;
        const textureHeight = regionHeightCells * scale;
        const pixelCount = textureWidth * textureHeight;

        // --- Step 1: Create pixel buffer for the current region ---
        // Note: We always recreate the buffer for the current region (viewport-based)
        this._pixelBuffer = new Uint8ClampedArray(pixelCount * 4);

        // Fill buffer with empty cell background color
        const emptyColor = this._hexToRgb(this.config.colorEmptyBg);
        for (let i = 0; i < pixelCount; i++) {
            const idx = i * 4;
            this._pixelBuffer[idx] = emptyColor.r;
            this._pixelBuffer[idx + 1] = emptyColor.g;
            this._pixelBuffer[idx + 2] = emptyColor.b;
            this._pixelBuffer[idx + 3] = 255; // Alpha
        }

        // --- Step 2: Draw cells into pixel buffer ---
        const typeMapping = this.grid.detailedRenderer.typeMapping;
        const getColor = (typeId) => this.grid.detailedRenderer.getBackgroundColorForType(typeId);

        for (let i = 0; i < cells.length; i++) {
            const cell = cells[i];
            const coords = cell.coordinates;
            if (!Array.isArray(coords) || coords.length < 2) continue;

            const cellX = coords[0];
            const cellY = coords[1];

            // Skip cells outside the region
            if (cellX < clampedX1 || cellX >= clampedX2 || cellY < clampedY1 || cellY >= clampedY2) continue;

            const typeId = typeMapping[cell.moleculeType] ?? 0;
            const color = this._hexToRgb(getColor(typeId));

            // Position relative to region origin, scaled
            const localX = (cellX - clampedX1) * scale;
            const localY = (cellY - clampedY1) * scale;

            // Draw scale×scale pixels for this cell
            for (let dy = 0; dy < scale; dy++) {
                for (let dx = 0; dx < scale; dx++) {
                    const px = localX + dx;
                    const py = localY + dy;
                    const idx = (py * textureWidth + px) * 4;
                    this._pixelBuffer[idx] = color.r;
                    this._pixelBuffer[idx + 1] = color.g;
                    this._pixelBuffer[idx + 2] = color.b;
                }
            }
        }

        // --- Step 3: Track rendered region for viewport-based cache validation ---
        this._renderedRegion = { x1: clampedX1, y1: clampedY1, x2: clampedX2, y2: clampedY2 };

        // --- Step 4: Create ImageData and upload to Canvas ---
        if (!this.offscreenCanvas || this.offscreenCanvas.width !== textureWidth || this.offscreenCanvas.height !== textureHeight) {
            this.offscreenCanvas = document.createElement('canvas');
            this.offscreenCanvas.width = textureWidth;
            this.offscreenCanvas.height = textureHeight;
        }

        const ctx = this.offscreenCanvas.getContext('2d');
        const imageData = new ImageData(this._pixelBuffer, textureWidth, textureHeight);
        ctx.putImageData(imageData, 0, 0);

        // --- Step 5: Create/update PIXI texture from canvas ---
        if (this.textureSprite) {
            // Destroy old texture to free GPU memory
            if (this.textureSprite.texture) {
                this.textureSprite.texture.destroy(true);
            }
            this.grid.cellContainer.removeChild(this.textureSprite);
            this.textureSprite.destroy();
        }

        // PIXI v8 uses string scale modes: 'nearest' for pixel-perfect rendering
        const texture = PIXI.Texture.from(this.offscreenCanvas, { scaleMode: 'nearest' });
        this.textureSprite = new PIXI.Sprite(texture);

        // Position sprite at the region's world position (scaled)
        this.textureSprite.x = clampedX1 * scale;
        this.textureSprite.y = clampedY1 * scale;

        this.grid.cellContainer.addChild(this.textureSprite);
    }

    renderOrganisms(organisms) {
        const self = this; // Explicitly capture the 'this' context of the strategy
        const scale = this.grid.zoomOutScale;
        // Marker size: minimum 5px, otherwise 80% of cell size
        const MARKER_SIZE = Math.max(5, scale * 0.8);

        // Clear previous organism markers from their containers
        for (const g of this.ipGraphics.values()) this.grid.organismContainer.removeChild(g);
        this.ipGraphics.clear();
        for (const { graphics } of this.dpGraphics.values()) this.grid.organismContainer.removeChild(graphics);
        this.dpGraphics.clear();

        // --- IPs ---
        for (const organism of organisms) {
            if (!organism || !Array.isArray(organism.ip) || !Array.isArray(organism.dv)) continue;

            const { organismId, ip, dv, energy } = organism;
            let ipGraphics = this.ipGraphics.get(organismId);
            if (!ipGraphics) {
                ipGraphics = new PIXI.Graphics();
                this.ipGraphics.set(organismId, ipGraphics);
                // Make it clickable
                ipGraphics.interactive = true;
                ipGraphics.buttonMode = true;
                ipGraphics.on('click', (event) => {
                    event.stopPropagation();
                    self.grid.controller.selectOrganism(organismId);
                });
            }
            ipGraphics.clear();

            const ipColor = this._getOrganismColor(organismId, energy);
            // Position at cell center, scaled to pixel coordinates
            const centerX = (ip[0] + 0.5) * scale;
            const centerY = (ip[1] + 0.5) * scale;

            ipGraphics.beginFill(ipColor, 1.0);

            const length = Math.sqrt(dv[0] * dv[0] + dv[1] * dv[1]) || 1;
            const dirX = dv[0] / length;
            const dirY = dv[1] / length;

            const half = MARKER_SIZE / 2;
            const tipX = centerX + dirX * half;
            const tipY = centerY + dirY * half;
            const base1X = centerX - dirX * half + (-dirY) * half;
            const base1Y = centerY - dirY * half + dirX * half;
            const base2X = centerX - dirX * half - (-dirY) * half;
            const base2Y = centerY - dirY * half - dirX * half;

            ipGraphics.moveTo(tipX, tipY);
            ipGraphics.lineTo(base1X, base1Y);
            ipGraphics.lineTo(base2X, base2Y);
            ipGraphics.lineTo(tipX, tipY);

            ipGraphics.endFill();
            this.grid.organismContainer.addChild(ipGraphics);
        }

        // --- DPs ---
        const dpPositions = new Map();
        for (const organism of organisms) {
            if (!organism || !Array.isArray(organism.dataPointers)) continue;

            organism.dataPointers.forEach((dp) => {
                if (!Array.isArray(dp) || dp.length < 2) return;
                const cellKey = `${dp[0]},${dp[1]}`;
                if (!dpPositions.has(cellKey)) {
                    dpPositions.set(cellKey, []);
                }
                dpPositions.get(cellKey).push(organism);
            });
        }

        for (const [cellKey, organismsAtPos] of dpPositions.entries()) {
             if (organismsAtPos.length === 0) continue;

            const prominentOrganism = organismsAtPos[0]; // Simple selection: pick the first one
            const orgColor = this._getOrganismColor(prominentOrganism.organismId, prominentOrganism.energy);

            let dpEntry = this.dpGraphics.get(cellKey);
            if (!dpEntry) {
                dpEntry = { graphics: new PIXI.Graphics() };
                this.dpGraphics.set(cellKey, dpEntry);
                 // Make it clickable
                dpEntry.graphics.interactive = true;
                dpEntry.graphics.buttonMode = true;
                dpEntry.graphics.on('click', (event) => {
                    event.stopPropagation();
                    // Re-fetch the list of organisms at this position to select the prominent one
                    const currentOrganisms = dpPositions.get(cellKey);
                    if (currentOrganisms && currentOrganisms.length > 0) {
                        self.grid.controller.selectOrganism(currentOrganisms[0].organismId);
                    }
                });
            }
            dpEntry.graphics.clear();

            const dpCoords = cellKey.split(',').map(Number);
            // Position at cell center, scaled to pixel coordinates
            const centerX = (dpCoords[0] + 0.5) * scale;
            const centerY = (dpCoords[1] + 0.5) * scale;
            const halfSize = MARKER_SIZE / 2;
            dpEntry.graphics.beginFill(orgColor, 0.7);
            dpEntry.graphics.drawRect(centerX - halfSize, centerY - halfSize, MARKER_SIZE, MARKER_SIZE);
            dpEntry.graphics.endFill();
            this.grid.organismContainer.addChild(dpEntry.graphics);
        }
    }
}
