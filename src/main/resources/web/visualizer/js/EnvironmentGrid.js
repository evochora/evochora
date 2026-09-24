import { loadingManager } from './ui/LoadingManager.js';
import { isMarkPresent } from './MutationMarks.js';
import { moleculeTypeEntry, moleculeTypeName, NO_DATA_COLOR, VALUE_FORMAT } from './MoleculeTypePalette.js';
import { AnnotationUtils } from './annotator/AnnotationUtils.js';
import { ValueFormatter } from './utils/ValueFormatter.js';
import { clampCamera, zoomCamera } from './interaction/ViewportMath.js';
import { ViewportInteraction } from './interaction/ViewportInteraction.js';
import { isOverviewSize } from './interaction/ZoomLevels.js';
import { circularDistance, splitRegion, wrap } from './interaction/TorusView.js';

/**
 * Writes a molecule in the short form of the cell tooltip: an instruction as its opcode name, a
 * register operand as its register name, and any other molecule as the abbreviation of its type
 * with its value. An instruction whose opcode has no name keeps the abbreviated form, so that its
 * number stays visible.
 *
 * @param {string|null} typeName - The type name of the molecule, null for a type the run does not name.
 * @param {number} value - The value of the molecule.
 * @param {string|null} opcodeName - The opcode name of an instruction, null or '??' when it has none.
 * @returns {string} The molecule as the tooltip shows it.
 */
function formatTooltipMolecule(typeName, value, opcodeName) {
    if (typeName === 'CODE' && opcodeName && opcodeName !== '??') {
        return opcodeName;
    }
    if (typeName === 'REGISTER') {
        return AnnotationUtils.formatRegisterName(value);
    }
    return `${moleculeTypeEntry(typeName).abbr}:${ValueFormatter.formatMoleculeValue(typeName, value)}`;
}

/**
 * Manages the PIXI.js-based rendering of the simulation environment grid.
 * This class acts as a context for different rendering strategies (e.g., detailed vs. zoomed-out)
 * and handles all shared logic like camera control, user interaction, and API communication.
 *
 * @class EnvironmentGrid
 */
export class EnvironmentGrid {

    /** How light the youngest mutation of a selection is drawn. */
    static MARK_LIGHTEST = 0.78;

    /** How dark the oldest one is. */
    static MARK_DARKEST = 0.22;

    /** The least saturation a mark is drawn with, so that a greyish clade colour still reads as one. */
    static MARK_MIN_SATURATION = 0.45;
    static MARGIN = 50;
    static BOTTOM_MARGIN = 90; // Extra space for footer panels

    /** Colour of the seam of a toroidal world: the blue the visualizer marks things with. */
    static SEAM_COLOR = 0x4a9eff;

    /** Opacity of the seam in the detail view, where it lies over few, large cells. */
    static SEAM_ALPHA_DETAIL = 0.3;

    /** Opacity of the seam in the overview, where it lies over the dense picture of a whole world. */
    static SEAM_ALPHA_OVERVIEW = 0.6;

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
        // The world's seam, drawn on a toroidal world only
        this.seamLines = new PIXI.Graphics();
        // Covers what lies outside the one world shown, where a toroidal world is smaller than the view
        this.torusCover = new PIXI.Graphics();

        // --- World & Camera State ---
        // A toroidal world has no edge: the camera moves on without bound and the world is shown
        // across its seam; a bounded world keeps the camera within its edges
        this.torus = false;
        this.worldWidthCells = this.config.worldSize?.[0] ?? null;
        this.worldHeightCells = this.config.worldSize?.[1] ?? null;
        this.cameraX = 0;
        this.cameraY = 0;
        this.viewportWidth = 0;
        this.viewportHeight = 0;
        
        // --- Zoom & Rendering Strategy ---
        this.isZoomedOut = false;
        this.zoomOutScale = 1;  // 1-10 px per cell in zoomed-out mode
        this.detailCellSize = this.config.cellSize;  // px per cell in the detail view
        // Scale of the drawn picture while a zoom gesture runs; 1 when the zoom is at rest
        this.previewFactor = 1;
        this.zoomPreviewActive = false;
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
        this._selectedOrganismId = null;
        this._selectionRing = new PIXI.Graphics();
        this._selectionAnimBound = null;
        this._selectionAnimStart = 0;
        this.cellData = new Map(); // key: "x,y" -> {type,value,ownerId,opcodeName}
        this._rawCells = null;      // Raw cells from API (for async cellData building)
        this._cellDataReady = false; // Flag: true when cellData map is fully built
        this._buildId = 0;          // Monotonic counter to cancel stale async builds

        // --- Mutation marks of the selected organism's lineage ---
        this.mutationMarks = null;   // key "x,y" -> the mutation that decides that cell's mark
        this._markBounds = null;     // smallest rectangle the marked cells lie in
        this._markColorOf = null;    // the colour the selected organism is drawn in
        this._markDepth = 0;         // how many generations the marks of the selection span
        this._markGenerationsBackOf = null; // organism id -> generations it lies back from the selected organism
        this._markOpcodeNameOf = null;      // opcode id -> opcode name

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
        this.viewportLoadTimeout = null;
        this._stageUpdatePending = false; // RAF throttle flag for stage position updates
        this._lastClickPos = null;       // "x,y" string for organism click cycling
        this._clickCycleIndex = 0;       // Current index in organism cycle list
        this.tapBlocked = false;         // Set while a press is a drag, pinch or long press, not a tap

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

        this.app.stage.addChild(this.gridBackground, this.cellContainer, this.textContainer, this.seamLines, this.organismContainer);
        this.organismContainer.addChild(this._selectionRing);
        this._stageEventMode = this.app.stage.eventMode;
        this._renderTorusBound = () => this._renderTorus();
        this._applyTopology();

        this.detailedRenderer.init();
        this.zoomedOutRenderer.init();
        
        this.setupTooltipEvents();
        this.interaction = new ViewportInteraction(this, canvas);
        this.setupResizeListener();

        this.clampCameraToWorld();
        this.updateStagePosition();
        this.updateScrollbars();
        this.setupScrollbarInteraction();
    }

    /**
     * Returns the size in pixels per cell the cells are drawn at.
     * @returns {number}
     */
    getCurrentCellSize() {
        return this.isZoomedOut ? this.zoomOutScale : this.detailCellSize;
    }

    /**
     * Returns the size in pixels per cell the cells appear at on screen: the size they are drawn at,
     * scaled while a zoom gesture shows the drawn picture at another size.
     * @returns {number}
     */
    getDisplayCellSize() {
        return this.getCurrentCellSize() * this.previewFactor;
    }

    /**
     * Returns the centre of the viewport, the point a zoom without a pointer keeps in place.
     * @returns {{x: number, y: number}}
     */
    viewportCenter() {
        return { x: this.viewportWidth / 2, y: this.viewportHeight / 2 };
    }

    /**
     * Shows the picture drawn so far at another size while a zoom gesture runs. Nothing is drawn or
     * loaded until {@link applyZoom} ends the gesture: the stage is scaled, and the world point under
     * the anchor stays where it is on screen.
     * @param {number} size - Pixels per cell to show.
     * @param {{x: number, y: number}} anchor - Point of the viewport that stays in place.
     */
    previewZoom(size, anchor) {
        const camera = zoomCamera({ x: this.cameraX, y: this.cameraY }, anchor, this.getDisplayCellSize(), size);
        this.cameraX = camera.x;
        this.cameraY = camera.y;
        this.previewFactor = size / this.getCurrentCellSize();
        this.zoomPreviewActive = true;
        this._scheduleStageUpdate();
    }

    /**
     * Sets the size the cells are drawn at and ends a zoom preview. The world point under the anchor
     * stays where it is on screen. A size of the overview selects the overview at that scale, a
     * larger size the detail view at that cell size. The caller loads and draws the viewport afterwards.
     * @param {number} size - Pixels per cell, one of the zoom levels.
     * @param {{x: number, y: number}} anchor - Point of the viewport that stays in place.
     * @returns {boolean} Whether the size the cells are drawn at changed.
     */
    applyZoom(size, anchor) {
        const oldSize = this.getCurrentCellSize();
        const camera = zoomCamera({ x: this.cameraX, y: this.cameraY }, anchor, this.getDisplayCellSize(), size);
        const zoomedOut = isOverviewSize(size);
        const modeChanged = zoomedOut !== this.isZoomedOut;

        this.isZoomedOut = zoomedOut;
        if (zoomedOut) {
            this.zoomOutScale = size;
        } else {
            this.detailCellSize = size;
        }
        this.activeRenderer = zoomedOut ? this.zoomedOutRenderer : this.detailedRenderer;
        this.previewFactor = 1;
        this.zoomPreviewActive = false;
        this.cameraX = camera.x;
        this.cameraY = camera.y;

        if (modeChanged) {
            // Every cell drawn so far is at the wrong size in the wrong renderer
            this.clear();
        } else if (size !== oldSize) {
            // The overview draws its buffer anew; the detail view builds its cells at the new size
            if (zoomedOut) {
                this.zoomedOutRenderer.clearCache();
            } else {
                this.detailedRenderer.clear();
            }
        }
        this.updateGridBackground();
        this.clampCameraToWorld();
        this.updateStagePosition();
        return size !== oldSize;
    }

    /**
     * Sets whether the world is a torus. A toroidal world is drawn across its seam, its camera is
     * not held at an edge, and pointer hits wrap around; a bounded world keeps every behaviour it
     * had.
     * @param {boolean} isTorus - True for a toroidal world.
     */
    setTopology(isTorus) {
        if (this.torus === isTorus) return;
        this.torus = isTorus;
        this.clear();
        this._applyTopology();
        this.updateGridBackground();
        this.clampCameraToWorld();
        this.updateStagePosition();
    }

    /**
     * Brings the drawing in line with the topology. A torus is drawn by {@link _renderTorus} in
     * place of the application's own render, which draws the stage once; PIXI's pointer events on
     * the stage are off, since after several passes they would hit one copy of the world only, and
     * a tap selects through {@link tapAt} instead.
     * @private
     */
    _applyTopology() {
        if (!this.app?.ticker) return;
        this.app.ticker.remove(this.app.render, this.app);
        this.app.ticker.remove(this._renderTorusBound);
        if (this.torus) {
            this.app.ticker.add(this._renderTorusBound, null, PIXI.UPDATE_PRIORITY.LOW);
            this.app.stage.eventMode = 'none';
        } else {
            this.app.ticker.add(this.app.render, this.app, PIXI.UPDATE_PRIORITY.LOW);
            this.app.stage.eventMode = this._stageEventMode;
        }
        this.seamLines.visible = this.torus;
    }

    /**
     * Draws a toroidal world: the stage once for every copy of the world the view reaches into —
     * one, or two across a seam, four at a corner — and then, where the world is smaller than the
     * view, a cover over everything outside the one world shown around the view's centre.
     * @private
     */
    _renderTorus() {
        const renderer = this.app.renderer;
        const stage = this.app.stage;
        const size = this.getDisplayCellSize();
        if (this.worldWidthCells == null || this.worldHeightCells == null) {
            renderer.render({ container: stage });
            return;
        }
        const worldWidthPx = this.worldWidthCells * size;
        const worldHeightPx = this.worldHeightCells * size;
        const lastX = Math.floor((this.cameraX + this.viewportWidth) / worldWidthPx);
        const lastY = Math.floor((this.cameraY + this.viewportHeight) / worldHeightPx);

        let first = true;
        for (let copyX = Math.floor(this.cameraX / worldWidthPx); copyX <= lastX; copyX++) {
            for (let copyY = Math.floor(this.cameraY / worldHeightPx); copyY <= lastY; copyY++) {
                stage.position.set(copyX * worldWidthPx - this.cameraX, copyY * worldHeightPx - this.cameraY);
                renderer.render({ container: stage, clear: first });
                first = false;
            }
        }
        stage.position.set(-this.cameraX, -this.cameraY);

        // The one world shown lies centred on the view
        const left = (this.viewportWidth - worldWidthPx) / 2;
        const top = (this.viewportHeight - worldHeightPx) / 2;
        if (left > 0 || top > 0) {
            const right = left + worldWidthPx;
            const bottom = top + worldHeightPx;
            const w = this.viewportWidth;
            const h = this.viewportHeight;
            const cover = this.torusCover.clear();
            if (left > 0) cover.rect(0, 0, left, h).rect(right, 0, w - right, h);
            if (top > 0) cover.rect(Math.max(0, left), 0, Math.min(w, right) - Math.max(0, left), top)
                .rect(Math.max(0, left), bottom, Math.min(w, right) - Math.max(0, left), h - bottom);
            cover.fill(this.config.backgroundColor);
            renderer.render({ container: cover, clear: false });
        }
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
        this.gridBackground.fill(NO_DATA_COLOR);

        // The seam runs along the world's first column and first row; every copy draws it where it
        // meets the copy before. The overview packs a world into few pixels, where a faint line is
        // lost, so it is drawn stronger there than in the detail view.
        this.seamLines.clear();
        if (this.torus) {
            this.seamLines
                .moveTo(0, 0).lineTo(0, worldHeightPx)
                .moveTo(0, 0).lineTo(worldWidthPx, 0)
                .stroke({
                    width: 1,
                    color: EnvironmentGrid.SEAM_COLOR,
                    alpha: this.isZoomedOut ? EnvironmentGrid.SEAM_ALPHA_OVERVIEW : EnvironmentGrid.SEAM_ALPHA_DETAIL,
                });
        }
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
        if (this.torus) {
            // No edge to stop at: the camera is kept within one world, which shows the same
            const size = this.getDisplayCellSize();
            this.cameraX = wrap(this.cameraX, this.worldWidthCells * size);
            this.cameraY = wrap(this.cameraY, this.worldHeightCells * size);
            return;
        }
        const margin = EnvironmentGrid.MARGIN;
        const camera = clampCamera(
            { x: this.cameraX, y: this.cameraY },
            { width: this.worldWidthCells, height: this.worldHeightCells },
            this.getDisplayCellSize(),
            { width: this.viewportWidth, height: this.viewportHeight },
            { left: margin, top: margin, right: margin, bottom: EnvironmentGrid.BOTTOM_MARGIN });
        this.cameraX = camera.x;
        this.cameraY = camera.y;
    }

    /**
     * Updates PIXI stage position based on camera position.
     */
    updateStagePosition() {
        if (!this.app || !this.app.stage) return;
        this.app.stage.scale.set(this.previewFactor);
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
     * Triggers a debounced request to load data for the current viewport.
     * This is called after camera movements to avoid excessive API calls.
     * @private
     */
    requestViewportLoad() {
        // A zoom gesture shows the picture drawn so far; the viewport is loaded once it has ended
        if (!this.onViewportChange || this.zoomPreviewActive) {
            return;
        }
        if (this.viewportLoadTimeout) {
            clearTimeout(this.viewportLoadTimeout);
        }
        this.viewportLoadTimeout = setTimeout(() => {
            // A zoom gesture that began in the meantime loads when it ends
            if (this.zoomPreviewActive) return;
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

        if (this.torus) {
            return this._loadTorusViewport(tick, runId, viewport, includeMinimap, renderer);
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

        // Yield to browser before synchronous rendering to allow CSS animation frames
        await new Promise(resolve => requestAnimationFrame(resolve));

        // --- Timing: Render cells ---
        loadingManager.update('Rendering environment');
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
     * Loads the view of a toroidal world, which can span the seam: the view region is split into
     * the regions of the world it covers — up to four at a corner — and those not yet loaded are
     * fetched together and drawn each in place.
     * @param {number} tick - The tick to load.
     * @param {string|null} runId - The run.
     * @param {{x1: number, x2: number, y1: number, y2: number}} viewport - View region, unwrapped.
     * @param {boolean} includeMinimap - Whether the minimap is wanted with the first request.
     * @param {BaseRendererStrategy} renderer - The active renderer.
     * @returns {Promise<{minimap?: object}>}
     * @private
     */
    async _loadTorusViewport(tick, runId, viewport, includeMinimap, renderer) {
        const pieces = splitRegion(viewport, this.worldWidthCells, this.worldHeightCells);
        const missing = pieces.filter(piece => !renderer.isRegionFullyLoaded(piece));
        if (missing.length === 0 && !includeMinimap) {
            this._triggerPrefetch(tick, runId, viewport);
            return {};
        }

        if (this.currentAbortController) {
            this.currentAbortController.abort();
        }
        const controller = new AbortController();
        this.currentAbortController = controller;

        const wanted = missing.length > 0 ? missing : [pieces[0]];
        const results = await Promise.all(wanted.map((piece, i) =>
            this.environmentApi.fetchEnvironmentData(tick, piece, {
                runId,
                signal: controller.signal,
                includeMinimap: includeMinimap && i === 0
            })));

        if (missing.length > 0) {
            // Tooltips read the cells of the latest load, here all of its regions
            this._rawCells = results.flatMap(data => data.cells);
            this._buildCellDataAsync();

            await new Promise(resolve => requestAnimationFrame(resolve));
            loadingManager.update('Rendering environment');
            missing.forEach((piece, i) => this.renderCellsWithCleanup(results[i].cells, piece));
        }

        if (this.currentAbortController === controller) {
            this.currentAbortController = null;
        }
        this._triggerPrefetch(tick, runId, viewport);
        return { minimap: results[0].minimap };
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

        // Calculate viewport dimensions
        const viewportWidth = viewport.x2 - viewport.x1;
        const viewportHeight = viewport.y2 - viewport.y1;

        // Skip prefetch if viewport is already larger than maxSize in ANY dimension.
        // Prefetch is meant to EXPAND coverage, not shrink it.
        // If viewport exceeds maxSize, the prefetch region would be smaller and would
        // overwrite the texture with less data.
        if (viewportWidth > maxSize || viewportHeight > maxSize) {
            return;
        }

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

        // Ensure prefetch region always covers the current viewport (clamped to world bounds).
        // Without this, the centered cap region can miss viewport edges when the viewport
        // center differs from the world center (e.g., world fits on screen vertically).
        expandedRegion.x1 = Math.min(expandedRegion.x1, Math.max(0, viewport.x1));
        expandedRegion.y1 = Math.min(expandedRegion.y1, Math.max(0, viewport.y1));
        expandedRegion.x2 = Math.max(expandedRegion.x2, Math.min(this.worldWidthCells, viewport.x2));
        expandedRegion.y2 = Math.max(expandedRegion.y2, Math.min(this.worldHeightCells, viewport.y2));

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

                    // IMPORTANT: Re-check if prefetch region would still expand coverage.
                    // Between fetch and render, the user may have panned and loaded a larger region.
                    // Without this check, prefetch could overwrite a larger viewport with smaller data.
                    if (this.zoomedOutRenderer.isRegionFullyLoaded(expandedRegion)) {
                        return; // Skip - current region already covers this, don't shrink it
                    }

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

                    // IMPORTANT: Re-check if prefetch region would still expand coverage.
                    // Between fetch and render, the user may have panned and loaded a different region.
                    if (this.detailedRenderer.isRegionFullyLoaded(expandedRegion)) {
                        return; // Skip - current region already covers this
                    }

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
        this.activeRenderer.renderCells(cells, region);
    }

    /**
     * Centers the camera on a specific world coordinate.
     * 
     * @param {number} cellX - The target X coordinate in cells.
     * @param {number} cellY - The target Y coordinate in cells.
     */
    centerOn(cellX, cellY) {
        const cellSize = this.getDisplayCellSize();

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
        const cellSize = this.getDisplayCellSize();
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
        // Reset cycle state only on actual data change (new tick), not on re-render from selection
        if (organismsForTick !== this.currentOrganisms) {
            this._lastClickPos = null;
        }
        this.currentOrganisms = organismsForTick;

        this.activeRenderer.renderOrganisms(organismsForTick);
    }

    /**
     * Takes the mutation marks of the selected organism's lineage, or drops them.
     *
     * Every cell that is marked before or after the change is drawn again, so a mark appears,
     * changes its colour or disappears with the selection while the rest of what is on screen
     * stays untouched. The zoomed-out renderer paints a whole region into one texture and keeps no
     * cell data to draw from, so it drops its cache instead and paints the region again from the
     * data of the next load.
     *
     * @param {Map<string, object>|null} marks - Cell key "x,y" to the mutation deciding that cell,
     *                                          null when no organism is selected.
     * @param {object} [lookups={}] - What the marks are drawn and described through.
     * @param {function(): number|null} [lookups.colorOf] - The colour the selected organism is
     *                                          drawn in, whose hue the marks take.
     * @param {function(number): number|null} [lookups.generationsBackOf] - How many generations the
     *                                          organism with the given id lies back from the selected
     *                                          one, null while that is not known.
     * @param {function(number): string|null} [lookups.opcodeNameOf] - Name of an opcode id, null for
     *                                          an id without a name.
     */
    /**
     * Draws the marks again for a colour or a depth that has changed under them.
     * <p>
     * The marks of a selection arrive before its ancestry does — one request answers births, the
     * other the organism — so when they are first drawn nothing is known about how far back they
     * lie. And the hue they take is the selected organism's, which changes with the clade level.
     */
    refreshMutationMarks() {
        if (!this.mutationMarks) {
            return;
        }
        this._markDepth = this._depthOfMarks();
        this.detailedRenderer.refreshMarks(new Set(this.mutationMarks.keys()));
        this.zoomedOutRenderer.clearCache();
        if (this.isZoomedOut) {
            this.requestViewportLoad();
        }
    }

    /**
     * How many generations the marks of the current selection reach back.
     * <p>
     * The gradient spans exactly that, so a short ancestry uses the whole range of it rather than
     * a sliver.
     * @private
     */
    _depthOfMarks() {
        let depth = 0;
        if (this.mutationMarks && this._markGenerationsBackOf) {
            for (const mark of this.mutationMarks.values()) {
                const back = this._markGenerationsBackOf(mark.originOrganismId);
                if (typeof back === 'number' && back > depth) {
                    depth = back;
                }
            }
        }
        return depth;
    }

    setMutationMarks(marks, { colorOf = null, generationsBackOf = null, opcodeNameOf = null } = {}) {
        const affected = new Set();
        if (this.mutationMarks) {
            for (const key of this.mutationMarks.keys()) affected.add(key);
        }
        if (marks) {
            for (const key of marks.keys()) affected.add(key);
        }

        this.mutationMarks = (marks && marks.size > 0) ? marks : null;
        this._markColorOf = colorOf;
        this._markGenerationsBackOf = generationsBackOf;
        this._markDepth = this._depthOfMarks();
        this._markOpcodeNameOf = opcodeNameOf;
        this._markBounds = this._computeMarkBounds();

        if (affected.size === 0) return;

        this.detailedRenderer.refreshMarks(affected);
        this.zoomedOutRenderer.clearCache();
        if (this.isZoomedOut) {
            this.requestViewportLoad();
        }
    }

    /**
     * Computes the smallest rectangle the marked cells lie in.
     *
     * The rectangle is what makes the marks affordable for the zoomed-out renderer, which walks
     * millions of cells: a cell outside it is settled by four comparisons and never builds a key.
     *
     * @returns {{x1: number, y1: number, x2: number, y2: number}|null} The bounds, null without marks.
     * @private
     */
    _computeMarkBounds() {
        if (!this.mutationMarks) return null;
        let x1 = Infinity, y1 = Infinity, x2 = -Infinity, y2 = -Infinity;
        for (const mark of this.mutationMarks.values()) {
            if (mark.x < x1) x1 = mark.x;
            if (mark.x > x2) x2 = mark.x;
            if (mark.y < y1) y1 = mark.y;
            if (mark.y > y2) y2 = mark.y;
        }
        return { x1, y1, x2, y2 };
    }

    /**
     * Tells whether a cell can carry a mark at all.
     *
     * @param {number} x - The cell's X coordinate.
     * @param {number} y - The cell's Y coordinate.
     * @returns {boolean} True if marks exist and the cell lies within their bounds.
     */
    isInMarkBounds(x, y) {
        const bounds = this._markBounds;
        return bounds !== null && x >= bounds.x1 && x <= bounds.x2 && y >= bounds.y1 && y <= bounds.y2;
    }

    /**
     * Returns the mark a cell carries with the molecule it currently holds.
     *
     * @param {number} x - The cell's X coordinate.
     * @param {number} y - The cell's Y coordinate.
     * @param {string|null} typeName - The type name of its molecule, null when the cell holds nothing.
     * @param {number} value - The value of its molecule.
     * @param {boolean} isEmptyCell - Whether the cell is empty.
     * @returns {object|null} The mark, or null when the cell carries none.
     */
    markAt(x, y, typeName, value, isEmptyCell) {
        if (!this.isInMarkBounds(x, y)) return null;
        const mark = this.mutationMarks.get(`${x},${y}`);
        if (!mark) return null;
        return isMarkPresent(mark, typeName, value, isEmptyCell) ? mark : null;
    }

    /**
     * Returns the colour a mark is drawn in: the hue of the selected organism's clade, at a
     * brightness that says how far back the birth it arose at lies.
     * <p>
     * The hue says whose mutations these are — the marks belong to the organism one selected, and
     * carry its colour as its body does. The brightness says when: the youngest mutation is light,
     * the oldest dark. It spans the whole range rather than starting at the organism's own
     * brightness, so that the young ones stand out even where the organism itself is drawn in the
     * dark tone of everything outside the opened clade.
     *
     * @param {object} mark - The mark to colour.
     * @returns {number} A packed RGB integer.
     */
    markColor(mark) {
        const base = this._markColorOf ? this._markColorOf() : null;
        const hue = EnvironmentGrid._toHsl(typeof base === 'number' ? base : 0xffffff);
        const back = this._markGenerationsBackOf
            ? this._markGenerationsBackOf(mark.originOrganismId)
            : null;
        const far = typeof back === 'number' && this._markDepth > 0
            ? Math.min(1, Math.max(0, back) / this._markDepth)
            : 0;
        const light = EnvironmentGrid.MARK_LIGHTEST
            + (EnvironmentGrid.MARK_DARKEST - EnvironmentGrid.MARK_LIGHTEST) * far;
        // A colourless base stays colourless: lifting its saturation would turn white into red
        const saturation = hue.s === 0 ? 0 : Math.max(hue.s, EnvironmentGrid.MARK_MIN_SATURATION);
        return EnvironmentGrid._fromHsl(hue.h, saturation, light);
    }

    /** A packed RGB integer as hue, saturation and lightness, each in [0, 1] but the hue in turns. @private */
    static _toHsl(color) {
        const r = ((color >> 16) & 0xff) / 255;
        const g = ((color >> 8) & 0xff) / 255;
        const b = (color & 0xff) / 255;
        const max = Math.max(r, g, b);
        const min = Math.min(r, g, b);
        const l = (max + min) / 2;
        if (max === min) {
            return { h: 0, s: 0, l };
        }
        const d = max - min;
        const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
        let h;
        if (max === r) {
            h = ((g - b) / d + (g < b ? 6 : 0)) / 6;
        } else if (max === g) {
            h = ((b - r) / d + 2) / 6;
        } else {
            h = ((r - g) / d + 4) / 6;
        }
        return { h, s, l };
    }

    /** Hue, saturation and lightness as a packed RGB integer. @private */
    static _fromHsl(h, s, l) {
        if (s === 0) {
            const grey = Math.round(l * 255);
            return (grey << 16) | (grey << 8) | grey;
        }
        const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        const p = 2 * l - q;
        const channel = (t) => {
            let value = t;
            if (value < 0) value += 1;
            if (value > 1) value -= 1;
            if (value < 1 / 6) return p + (q - p) * 6 * value;
            if (value < 1 / 2) return q;
            if (value < 2 / 3) return p + (q - p) * (2 / 3 - value) * 6;
            return p;
        };
        const r = Math.round(channel(h + 1 / 3) * 255);
        const g = Math.round(channel(h) * 255);
        const b = Math.round(channel(h - 1 / 3) * 255);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Sets or clears the selected organism, starting/stopping the pulse ring animation.
     * @param {string|null} organismId - The selected organism ID, or null to deselect
     */
    setSelectedOrganism(organismId) {
        this._selectedOrganismId = organismId;

        if (organismId) {
            if (!this._selectionAnimBound) {
                this._selectionAnimStart = performance.now();
                this._selectionAnimBound = this._animateSelectionRing.bind(this);
                this.app.ticker.add(this._selectionAnimBound);
            } else {
                this._selectionAnimStart = performance.now();
            }
        } else {
            if (this._selectionAnimBound) {
                this.app.ticker.remove(this._selectionAnimBound);
                this._selectionAnimBound = null;
            }
            this._selectionRing.clear();
        }
    }

    /**
     * PIXI ticker callback that draws pulsing selection rings at the selected organism's
     * IP and DP positions. The ring expands from small to large radius while fading out.
     * @private
     */
    _animateSelectionRing() {
        this._selectionRing.clear();

        if (!this._selectedOrganismId || !this.currentOrganisms) return;

        const org = this.currentOrganisms.find(
            o => String(o.organismId) === this._selectedOrganismId
        );
        if (!org || !Array.isArray(org.ip)) return;

        const elapsed = performance.now() - this._selectionAnimStart;
        const phase = (elapsed % 1500) / 1500;

        const scale = this.getCurrentCellSize();
        const minRadius = Math.max(scale * 0.8, 6);
        const maxRadius = Math.max(scale * 3.5, 20);
        const radius = minRadius + (maxRadius - minRadius) * phase;
        const alpha = 1.0 - phase;

        const positions = [];
        positions.push([(org.ip[0] + 0.5) * scale, (org.ip[1] + 0.5) * scale]);

        if (org.dataPointers && Array.isArray(org.dataPointers)) {
            for (const dp of org.dataPointers) {
                if (Array.isArray(dp) && dp.length >= 2) {
                    positions.push([(dp[0] + 0.5) * scale, (dp[1] + 0.5) * scale]);
                }
            }
        }

        for (const [px, py] of positions) {
            this._selectionRing.lineStyle(2, 0xffffff, alpha);
            this._selectionRing.beginFill(0xffffff, 0.12 * alpha);
            this._selectionRing.drawCircle(px, py, radius);
            this._selectionRing.endFill();
        }
    }

    /**
     * Moves the camera by a distance in screen pixels and loads what comes into view.
     * @param {number} dx - Pixels to the right.
     * @param {number} dy - Pixels down.
     */
    panBy(dx, dy) {
        this.moveCameraTo(this.cameraX + dx, this.cameraY + dy);
    }

    /**
     * Moves the camera to a position in screen pixels, kept inside the world.
     * @param {number} x - Camera x.
     * @param {number} y - Camera y.
     * @param {boolean} [load=true] - Whether to load what comes into view once the camera rests;
     *        false leaves loading to the caller.
     */
    moveCameraTo(x, y, load = true) {
        this.cameraX = x;
        this.cameraY = y;
        this.clampCameraToWorld();
        this._scheduleStageUpdate();
        if (load) this.requestViewportLoad();
    }

    /**
     * Sets up event listeners to make the virtual scrollbars interactive.
     * Allows users to drag the scrollbar thumbs to pan the camera.
     * @private
     */
    setupScrollbarInteraction() {
        if (!this.hScrollThumb || !this.vScrollThumb) return;

        const margin = EnvironmentGrid.MARGIN;
        const bottomMargin = EnvironmentGrid.BOTTOM_MARGIN;

        // On a toroidal world the part of a thumb that runs past the end of its track shows at the
        // start of it, as a second thumb dragged like the first
        this.hScrollThumbWrapped = this._createWrappedThumb(this.hScrollThumb);
        this.vScrollThumbWrapped = this._createWrappedThumb(this.vScrollThumb);

        // --- Horizontal Scrollbar Interaction ---
        const dragHorizontal = (e) => {
            const thumb = e.currentTarget;
            e.preventDefault();
            // The thumb keeps the pointer while it is dragged, also when it leaves the thumb
            thumb.setPointerCapture(e.pointerId);
            const startX = e.clientX;
            const startCameraX = this.cameraX;
            const trackWidth = this.hScrollTrack.clientWidth;
            const worldWidthPx = this.worldWidthCells * this.getDisplayCellSize();
            const scrollableWidth = this.torus ? worldWidthPx : worldWidthPx + 2 * margin;

            const onMouseMove = (moveEvent) => {
                const dx = moveEvent.clientX - startX;
                // Convert pixel delta on scrollbar to pixel delta in world
                const cameraDeltaX = (dx / trackWidth) * scrollableWidth;
                this.cameraX = startCameraX + cameraDeltaX;

                this._scheduleStageUpdate();
                this.requestViewportLoad(); // Debounced load
            };

            const onMouseUp = () => {
                thumb.removeEventListener('pointermove', onMouseMove);
                thumb.removeEventListener('pointerup', onMouseUp);
                thumb.removeEventListener('pointercancel', onMouseUp);
            };

            thumb.addEventListener('pointermove', onMouseMove);
            thumb.addEventListener('pointerup', onMouseUp);
            thumb.addEventListener('pointercancel', onMouseUp);
        };
        this.hScrollThumb.addEventListener('pointerdown', dragHorizontal);
        this.hScrollThumbWrapped.addEventListener('pointerdown', dragHorizontal);

        // --- Vertical Scrollbar Interaction ---
        const dragVertical = (e) => {
            const thumb = e.currentTarget;
            e.preventDefault();
            // The thumb keeps the pointer while it is dragged, also when it leaves the thumb
            thumb.setPointerCapture(e.pointerId);
            const startY = e.clientY;
            const startCameraY = this.cameraY;
            const trackHeight = this.vScrollTrack.clientHeight;
            const worldHeightPx = this.worldHeightCells * this.getDisplayCellSize();
            const scrollableHeight = this.torus ? worldHeightPx : worldHeightPx + margin + bottomMargin;

            const onMouseMove = (moveEvent) => {
                const dy = moveEvent.clientY - startY;
                // Convert pixel delta on scrollbar to pixel delta in world
                const cameraDeltaY = (dy / trackHeight) * scrollableHeight;
                this.cameraY = startCameraY + cameraDeltaY;

                this._scheduleStageUpdate();
                this.requestViewportLoad(); // Debounced load
            };

            const onMouseUp = () => {
                thumb.removeEventListener('pointermove', onMouseMove);
                thumb.removeEventListener('pointerup', onMouseUp);
                thumb.removeEventListener('pointercancel', onMouseUp);
            };

            thumb.addEventListener('pointermove', onMouseMove);
            thumb.addEventListener('pointerup', onMouseUp);
            thumb.addEventListener('pointercancel', onMouseUp);
        };
        this.vScrollThumb.addEventListener('pointerdown', dragVertical);
        this.vScrollThumbWrapped.addEventListener('pointerdown', dragVertical);

        // --- Horizontal Track Click (jump to position) ---
        this.hScrollTrack.addEventListener('click', (e) => {
            // Ignore if clicking on thumb
            if (e.target === this.hScrollThumb || e.target === this.hScrollThumbWrapped) return;

            const trackRect = this.hScrollTrack.getBoundingClientRect();
            const clickX = e.clientX - trackRect.left;
            const trackWidth = this.hScrollTrack.clientWidth;
            const worldWidthPx = this.worldWidthCells * this.getDisplayCellSize();
            const scrollableWidth = worldWidthPx + 2 * margin;

            // Calculate camera position from click position; a torus has no margin to scroll into
            this.cameraX = this.torus
                ? (clickX / trackWidth) * worldWidthPx
                : (clickX / trackWidth) * scrollableWidth - margin;
            this.clampCameraToWorld();
            this.updateStagePosition();
            this.requestViewportLoad();
        });

        // --- Vertical Track Click (jump to position) ---
        this.vScrollTrack.addEventListener('click', (e) => {
            // Ignore if clicking on thumb
            if (e.target === this.vScrollThumb || e.target === this.vScrollThumbWrapped) return;

            const trackRect = this.vScrollTrack.getBoundingClientRect();
            const clickY = e.clientY - trackRect.top;
            const trackHeight = this.vScrollTrack.clientHeight;
            const worldHeightPx = this.worldHeightCells * this.getDisplayCellSize();
            const scrollableHeight = worldHeightPx + margin + bottomMargin;

            // Calculate camera position from click position; a torus has no margin to scroll into
            this.cameraY = this.torus
                ? (clickY / trackHeight) * worldHeightPx
                : (clickY / trackHeight) * scrollableHeight - margin;
            this.clampCameraToWorld();
            this.updateStagePosition();
            this.requestViewportLoad();
        });
    }

    /**
     * Creates the second thumb of a scrollbar, hidden until a toroidal world needs it.
     * @param {HTMLElement} thumb - The scrollbar's thumb.
     * @returns {HTMLElement}
     * @private
     */
    _createWrappedThumb(thumb) {
        const wrapped = thumb.cloneNode(false);
        wrapped.removeAttribute('id');
        wrapped.style.display = 'none';
        thumb.parentElement.appendChild(wrapped);
        return wrapped;
    }

    /**
     * Places the scrollbar thumbs on a toroidal world. A thumb shows where the view lies in one
     * turn of the world; the part that runs past the end of the track shows at its start.
     * @private
     */
    _updateTorusScrollbars() {
        const cellSize = this.getDisplayCellSize();
        const place = (track, thumb, wrapped, worldPx, viewPx, camera, horizontal) => {
            if (worldPx <= viewPx) {
                track.style.display = 'none';
                return;
            }
            track.style.display = 'block';
            const trackPx = horizontal ? track.clientWidth : track.clientHeight;
            const length = Math.max((viewPx / worldPx) * trackPx, 10);
            const start = (camera / worldPx) * trackPx;
            const [offset, size] = horizontal ? ['left', 'width'] : ['top', 'height'];
            thumb.style[offset] = `${start}px`;
            thumb.style[size] = `${Math.min(length, trackPx - start)}px`;
            const overrun = start + length - trackPx;
            wrapped.style.display = overrun > 0 ? 'block' : 'none';
            if (overrun > 0) {
                wrapped.style[offset] = '0px';
                wrapped.style[size] = `${overrun}px`;
            }
        };
        place(this.hScrollTrack, this.hScrollThumb, this.hScrollThumbWrapped,
            this.worldWidthCells * cellSize, this.viewportWidth, this.cameraX, true);
        place(this.vScrollTrack, this.vScrollThumb, this.vScrollThumbWrapped,
            this.worldHeightCells * cellSize, this.viewportHeight, this.cameraY, false);
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

            // Notify camera moved (updates minimap viewport rectangle)
            if (this.onCameraMoved) {
                this.onCameraMoved();
            }

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
        if (this.torus) {
            if (this.hScrollThumbWrapped) this._updateTorusScrollbars();
            return;
        }
        if (this.hScrollThumbWrapped) {
            this.hScrollThumbWrapped.style.display = 'none';
            this.vScrollThumbWrapped.style.display = 'none';
        }

        const cellSize = this.getDisplayCellSize();
        const worldWidthPx = this.worldWidthCells * cellSize;
        const worldHeightPx = this.worldHeightCells * cellSize;
        
        const margin = EnvironmentGrid.MARGIN;
        const bottomMargin = EnvironmentGrid.BOTTOM_MARGIN;

        const scrollableWidth = worldWidthPx + 2 * margin;
        const scrollableHeight = worldHeightPx + margin + bottomMargin;

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
        // A finger brings the tooltip up by resting, which ViewportInteraction handles
        this.app.view.addEventListener('pointermove', (event) => {
            if (event.pointerType !== 'touch') this.handleMouseMove(event);
        });
        // A finger leaves the canvas whenever it is lifted; its tooltip stays until the next touch
        this.app.view.addEventListener('pointerleave', (event) => {
            if (event.pointerType === 'touch') return;
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
        const target = this._gridCellAt(event.clientX, event.clientY);
        if (!target) {
            this.hideTooltip();
            return;
        }
        const { gridX, gridY } = target;

        const currentPos = `${gridX},${gridY}`;

        if (this.lastMousePosition === currentPos) return;

        this.hideTooltip();
        this.lastMousePosition = currentPos;

        if (this.tooltipTimeout) clearTimeout(this.tooltipTimeout);

        const cell = this.findCellAt(gridX, gridY);
        const nearbyOrganisms = this.findAllOrganismsNear(gridX, gridY);

        // Change cursor when over clickable organism
        this.container.style.cursor = nearbyOrganisms.length > 0 ? 'pointer' : 'default';

        if (cell || nearbyOrganisms.length > 0) {
            this.tooltipTimeout = setTimeout(() => this.showTooltip(event, cell, gridX, gridY, nearbyOrganisms), this.tooltipDelay);
        }
    }

    /**
     * Returns the world cell under a point of the page, or null when the point lies off the canvas
     * or off the world.
     * @param {number} clientX - Page x in client pixels.
     * @param {number} clientY - Page y in client pixels.
     * @returns {{gridX: number, gridY: number}|null}
     * @private
     */
    _gridCellAt(clientX, clientY) {
        const rect = this.app.view.getBoundingClientRect();
        const x = clientX - rect.left;
        const y = clientY - rect.top;
        if (x < 0 || y < 0 || x >= rect.width || y >= rect.height) return null;

        const cellSize = this.getDisplayCellSize();
        if (this.torus && this.worldWidthCells !== null && this.worldHeightCells !== null) {
            // Outside the one world shown, where the world is smaller than the view, lies nothing
            const halfWidth = this.worldWidthCells * cellSize / 2;
            const halfHeight = this.worldHeightCells * cellSize / 2;
            if (Math.abs(x - this.viewportWidth / 2) >= halfWidth || Math.abs(y - this.viewportHeight / 2) >= halfHeight) {
                return null;
            }
            return {
                gridX: wrap(Math.floor((x + this.cameraX) / cellSize), this.worldWidthCells),
                gridY: wrap(Math.floor((y + this.cameraY) / cellSize), this.worldHeightCells),
            };
        }
        const gridX = Math.floor((x + this.cameraX) / cellSize);
        const gridY = Math.floor((y + this.cameraY) / cellSize);
        if (
            this.worldWidthCells === null || this.worldHeightCells === null ||
            gridX < 0 || gridX >= this.worldWidthCells ||
            gridY < 0 || gridY >= this.worldHeightCells
        ) {
            return null;
        }
        return { gridX, gridY };
    }

    /**
     * Shows the tooltip of the cell under a point at once, lifted above it. Used for a finger,
     * which would cover a tooltip shown at the point itself.
     * @param {number} clientX - Page x in client pixels.
     * @param {number} clientY - Page y in client pixels.
     * @param {number} lift - Pixels the tooltip is raised above the point.
     */
    showTooltipAtPoint(clientX, clientY, lift) {
        const target = this._gridCellAt(clientX, clientY);
        const cell = target ? this.findCellAt(target.gridX, target.gridY) : null;
        const nearbyOrganisms = target ? this.findAllOrganismsNear(target.gridX, target.gridY) : [];
        if (!cell && nearbyOrganisms.length === 0) {
            this.hideTooltip();
            return;
        }
        this.showTooltip({ clientX, clientY: clientY - lift }, cell, target.gridX, target.gridY, nearbyOrganisms);
    }

    /**
     * Finds all organisms (IP or DP) near the given grid coordinates.
     * Uses a minimum hit radius for small organisms.
     *
     * @param {number} gridX - The X coordinate in cells.
     * @param {number} gridY - The Y coordinate in cells.
     * @returns {Array<{organism: object, type: string, position: number[]}>} All nearby organisms (empty if none).
     * @private
     */
    findAllOrganismsNear(gridX, gridY) {
        if (!this.currentOrganisms || this.currentOrganisms.length === 0) return [];

        const cellSize = this.getDisplayCellSize();
        // Minimum hit radius in pixels, converted to cells
        const MIN_HIT_RADIUS_PX = 15;
        const hitRadiusCells = Math.max(0.5, MIN_HIT_RADIUS_PX / cellSize / 2);

        // On a torus a pointer just past the seam is next to the cells just before it
        const distance = this.torus
            ? (x1, y1, x2, y2) => Math.max(
                circularDistance(x1, x2, this.worldWidthCells), circularDistance(y1, y2, this.worldHeightCells))
            : (x1, y1, x2, y2) => Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));

        const results = [];
        for (const organism of this.currentOrganisms) {
            if (!organism) continue;

            // Check IP position
            if (Array.isArray(organism.ip) && organism.ip.length >= 2) {
                const ipX = organism.ip[0];
                const ipY = organism.ip[1];
                const dist = distance(gridX, gridY, ipX, ipY);
                if (dist <= hitRadiusCells) {
                    results.push({ organism, type: 'IP', position: [ipX, ipY] });
                }
            }

            // Check DP positions
            if (Array.isArray(organism.dataPointers)) {
                for (let i = 0; i < organism.dataPointers.length; i++) {
                    const dp = organism.dataPointers[i];
                    if (!Array.isArray(dp) || dp.length < 2) continue;
                    const dpX = dp[0];
                    const dpY = dp[1];
                    const dist = distance(gridX, gridY, dpX, dpY);
                    if (dist <= hitRadiusCells) {
                        results.push({ organism, type: `DP${i}`, position: [dpX, dpY] });
                    }
                }
            }
        }

        return results;
    }

    /**
     * Selects the organism under a tap on a toroidal world, where PIXI's pointer events are off.
     * @param {number} clientX - Page x in client pixels.
     * @param {number} clientY - Page y in client pixels.
     */
    tapAt(clientX, clientY) {
        const target = this._gridCellAt(clientX, clientY);
        if (target) this.cycleOrganismAtPosition(target.gridX, target.gridY);
    }

    /**
     * Handles organism selection at a grid position with cycling support.
     * Repeated clicks at the same position cycle through all organisms there.
     *
     * @param {number} gridX - The X coordinate in cells.
     * @param {number} gridY - The Y coordinate in cells.
     */
    cycleOrganismAtPosition(gridX, gridY) {
        // A press that dragged, pinched or brought up a tooltip selects nothing when it ends
        if (this.tapBlocked) return;
        const allMatches = this.findAllOrganismsNear(gridX, gridY);
        // Deduplicate by organismId
        const unique = [...new Map(allMatches.map(m => [m.organism.organismId, m.organism])).values()];
        if (unique.length === 0) return;

        const posKey = `${gridX},${gridY}`;
        if (this._lastClickPos === posKey && unique.length > 1) {
            this._clickCycleIndex = (this._clickCycleIndex + 1) % unique.length;
        } else {
            this._lastClickPos = posKey;
            this._clickCycleIndex = 0;
        }
        this.controller.selectOrganism(unique[this._clickCycleIndex].organismId);
    }

    /**
     * Builds the cellData map asynchronously in chunks to avoid blocking the UI.
     * Tooltips are unavailable until the map is fully built.
     * @private
     */
    async _buildCellDataAsync() {
        if (!this._rawCells) return;

        const buildId = ++this._buildId;
        this._cellDataReady = false;
        this.cellData.clear();

        const CHUNK_SIZE = 10000;
        const cells = this._rawCells;

        for (let i = 0; i < cells.length; i += CHUNK_SIZE) {
            const end = Math.min(i + CHUNK_SIZE, cells.length);

            for (let j = i; j < end; j++) {
                const cell = cells[j];
                const coords = cell.coordinates;
                if (!Array.isArray(coords) || coords.length < 2) continue;
                const key = `${coords[0]},${coords[1]}`;
                this.cellData.set(key, {
                    type: moleculeTypeName(cell.moleculeType),
                    value: cell.moleculeValue,
                    ownerId: cell.ownerId,
                    opcodeName: cell.opcodeName || null,
                    opcodeId: cell.opcodeId,
                    marker: cell.marker || 0
                });
            }

            // Yield to browser between chunks to keep UI responsive
            await new Promise(resolve => setTimeout(resolve, 0));

            // Abort if a newer build has started during the yield
            if (this._buildId !== buildId) return;
        }

        this._cellDataReady = true;
    }
    
    /**
     * Finds the cell data at a specific grid coordinate.
     *
     * @param {number} gridX - The grid X coordinate.
     * @param {number} gridY - The grid Y coordinate.
     * @returns {object|null} The cell data object, or null if not available.
     * @private
     */
    findCellAt(gridX, gridY) {
        if (!this._cellDataReady) {
            return null;
        }

        const key = `${gridX},${gridY}`;
        return this.cellData.get(key) || null;
    }

    /**
     * Displays the tooltip with formatted information about a cell.
     *
     * @param {MouseEvent} event - The mouse event, used for positioning.
     * @param {object} cell - The cell data object (may be null).
     * @param {number} gridX - The cell's X coordinate.
     * @param {number} gridY - The cell's Y coordinate.
     * @param {Array<{organism: object, type: string, position: number[]}>} nearbyOrganisms - Nearby organisms.
     * @private
     */
    showTooltip(event, cell, gridX, gridY, nearbyOrganisms) {
        if (!this.tooltip) return;

        let cellInfo = `<span class="tooltip-coords">[${gridX}|${gridY}]</span>`;
        if (cell) {
            cellInfo += ` <span class="tooltip-type">${formatTooltipMolecule(cell.type, cell.value, cell.opcodeName)}</span>`;
            // Owner and marker are named only where they are set; one left out is zero
            const ownership = [
                cell.ownerId ? `#${cell.ownerId}` : '',
                cell.marker ? `M${cell.marker}` : ''
            ].filter(Boolean).join(' ');
            if (ownership) {
                cellInfo += ` <span class="tooltip-owner">${ownership}</span>`;
            }
        }

        cellInfo += this._mutationTooltipLine(cell, gridX, gridY);

        let organismInfo = '';
        for (const { organism, type, position } of nearbyOrganisms) {
            // A pointer's coordinates are named only where they differ from the hovered cell
            const coordinates = (position[0] === gridX && position[1] === gridY)
                ? ''
                : `<span class="tooltip-coords">[${position[0]}|${position[1]}]</span> `;
            organismInfo += `<div class="tooltip-organism">${coordinates}<span class="tooltip-org-info">#${organism.organismId} ${type}</span></div>`;
        }

        this.tooltip.innerHTML = cellInfo + organismInfo;

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
     * Builds the tooltip line of a cell that carries a mutation mark.
     *
     * @param {object|null} cell - The cell's data, null when the cell holds nothing.
     * @param {number} gridX - The cell's X coordinate.
     * @param {number} gridY - The cell's Y coordinate.
     * @returns {string} The line as HTML, empty when the cell carries no mark.
     * @private
     */
    _mutationTooltipLine(cell, gridX, gridY) {
        const isEmpty = !cell
            || (cell.type === 'CODE' && cell.value === 0 && cell.ownerId === 0);
        const mark = this.markAt(gridX, gridY, cell ? cell.type : null, cell ? cell.value : 0, isEmpty);
        if (!mark) return '';

        const opcodeName = (typeName, value) =>
            (typeName === 'CODE' && this._markOpcodeNameOf) ? this._markOpcodeNameOf(value) : null;
        const before = formatTooltipMolecule(mark.beforeTypeName, mark.beforeValue,
            opcodeName(mark.beforeTypeName, mark.beforeValue));
        const after = formatTooltipMolecule(mark.afterTypeName, mark.afterValue,
            opcodeName(mark.afterTypeName, mark.afterValue));
        // The kind is a name the reporting plugin chose and reaches the page as data
        const kind = String(mark.kind ?? '').replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' })[c]);
        // G0 is the selected organism's own birth, G-n a birth n generations back; the distance
        // is left out while it is not known
        const back = this._markGenerationsBackOf ? this._markGenerationsBackOf(mark.originOrganismId) : null;
        const generation = back === null ? '' : (back === 0 ? ' G0' : ` G-${back}`);
        // The genome the mutation arose in is written in the colour its mark is drawn in
        const color = `#${this.markColor(mark).toString(16).padStart(6, '0')}`;
        const genome = ValueFormatter.formatGenomeHash(mark.genomeHash);
        return `<span class="tooltip-mutation">${kind} ${before}\u2192${after} <span style="color:${color}">${genome}</span>${generation}</span>`;
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

    _getOrganismColor(organismId, energy, genomeHash, isDead) {
        // Selected organism is always white
        if (this.controller && String(organismId) === this.controller.state.selectedOrganismId) {
            return 0xffffff;
        }

        // Dead organisms are dimmed gray
        if (isDead) {
            return 0x555555;
        }

        return this.controller
            ? this.controller._genomeHashToLineageColor(genomeHash)
            : 0x808080;
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
            const rowStart = y * width + x1;
            if (this._loadedMask.subarray(rowStart, rowStart + (x2 - x1)).indexOf(0) !== -1) {
                return false;
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
            const rowStart = y * width + x1;
            this._loadedMask.fill(1, rowStart, rowStart + (x2 - x1));
        }
    }

    _getOrganismColor(organismId, energy, genomeHash, isDead) {
        return this.grid._getOrganismColor(organismId, energy, genomeHash, isDead);
    }
}

/**
 * Renders the environment with full details: cell text, organism markers with direction, etc.
 */
class DetailedRendererStrategy extends BaseRendererStrategy {
    /** Maximum number of cells to keep in memory (LRU eviction budget) */
    static MAX_CELLS = 100_000;

    /** Width in pixels of the border that marks a mutated cell. */
    static MARK_BORDER_WIDTH = 2;

    constructor(grid) {
        super(grid);
        this.cellObjects = new Map();
        this.ipGraphics = new Map();
        this.dpGraphics = new Map();

        // What a cell holds that the loaded region does not name: nothing at all
        this.emptyCell = { type: 'CODE', value: 0, ownerId: 0, opcodeName: null, marker: 0 };
        // The font size follows the cell size the text is drawn at
        this.cellFont = {
            fontFamily: 'Monospaced, "Courier New"',
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
                type: moleculeTypeName(cell.moleculeType),
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
        // Iterate region coordinates (small) instead of all cellObjects (up to 100K)
        const { x1, x2, y1, y2 } = region;

        for (let cy = y1; cy < y2; cy++) {
            for (let cx = x1; cx < x2; cx++) {
                const key = `${cx},${cy}`;
                if (updatedKeys.has(key)) continue;

                // A cell the response does not name is empty, and an empty cell a deletion of the
                // lineage cleared keeps its mark on the empty background.
                if (this.grid.markAt(cx, cy, null, 0, true)) {
                    this.drawCell(this.emptyCell, [cx, cy]);
                    this._cellAccessTime.set(key, now);
                    continue;
                }

                const entry = this.cellObjects.get(key);
                if (entry) {
                    if (entry.background) this.grid.cellContainer.removeChild(entry.background);
                    if (entry.text) this.grid.textContainer.removeChild(entry.text);
                    this.cellObjects.delete(key);
                    this._cellAccessTime.delete(key);
                }
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
        const isEmpty = cell.type === 'CODE' && cell.value === 0 && cell.ownerId === 0;
        background.fill(isEmpty ? NO_DATA_COLOR : this.getBackgroundColorForType(cell.type));

        // Draw text
        const shouldHaveText = !isEmpty && ((cell.type === 'CODE' && (cell.value !== 0 || cell.ownerId !== 0)) || cell.type !== 'CODE');
        if (shouldHaveText) {
            let label;
            if (cell.type === 'CODE') {
                label = (cell.opcodeName && typeof cell.opcodeName === 'string') ? cell.opcodeName : String(cell.value);
            } else if (moleculeTypeEntry(cell.type).valueFormat === VALUE_FORMAT.HEX) {
                label = ValueFormatter.formatHexValueOnTwoLines(cell.value);
            } else {
                label = cell.value.toString();
            }

            // Split long values (> 4 chars) into two lines for better readability
            if (label.length > 4 && !label.includes('\n')) {
                const mid = Math.ceil(label.length / 2);
                label = label.slice(0, mid) + '\n' + label.slice(mid);
            }

            if (!text) {
                text = new PIXI.Text({ text: label, style: { ...this.cellFont, fontSize: cellSize * 0.4, fill: this.getTextColorForType(cell.type) }});
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

        // A mutation of the selected organism's lineage that this cell still holds is drawn as a
        // border inside the cell's edge, over the background and under the text.
        const mark = this.grid.markAt(pos[0], pos[1], cell.type, cell.value, isEmpty);
        if (mark) {
            const width = DetailedRendererStrategy.MARK_BORDER_WIDTH;
            background.rect(width / 2, width / 2, cellSize - width, cellSize - width);
            background.stroke({ width, color: this.grid.markColor(mark) });
        }

        // The cell's molecule stays with its graphics so that a changed selection can draw the
        // cell again without fetching the region a second time.
        this.cellObjects.set(key, { background, text, cell });
    }

    /**
     * Draws the given cells again after the mutation marks have changed.
     *
     * A cell whose graphics are still on screen is drawn from the molecule they were drawn with. A
     * marked cell without graphics inside a loaded region holds nothing — the response named it
     * nowhere — and is drawn as an empty cell so that the mark of a deletion appears on the empty
     * background.
     *
     * @param {Iterable<string>} keys - The cell keys to draw again.
     */
    refreshMarks(keys) {
        const now = performance.now();
        const worldWidth = this.grid.worldWidthCells;

        for (const key of keys) {
            const entry = this.cellObjects.get(key);
            if (entry) {
                this.drawCell(entry.cell, key.split(',').map(Number));
                continue;
            }

            if (!this._loadedMask || !worldWidth) continue;
            const [x, y] = key.split(',').map(Number);
            if (Number.isNaN(x) || Number.isNaN(y)) continue;
            if (this._loadedMask[y * worldWidth + x] !== 1) continue;
            if (!this.grid.markAt(x, y, null, 0, true)) continue;

            this.drawCell(this.emptyCell, [x, y]);
            this._cellAccessTime.set(key, now);
        }
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
                graphics.on('pointertap', (event) => {
                    event.stopPropagation();
                    const current = self.grid.currentOrganisms?.find(o => o.organismId === organism.organismId);
                    if (current && Array.isArray(current.ip)) {
                        self.grid.cycleOrganismAtPosition(current.ip[0], current.ip[1]);
                    }
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

            const ipColor = this._getOrganismColor(organismId, energy, organism.genomeHash, organism.isDead);
            const ipCellX = ip[0] * cellSize;
            const ipCellY = ip[1] * cellSize;
            const cx = ipCellX + cellSize / 2;
            const cy = ipCellY + cellSize / 2;

            // Triangle size
            const markerSize = cellSize * 0.6;
            const half = markerSize;

            // Rectangle same size as triangle (2 * half), centered on cell
            const rectSize = half * 2;
            const rectOffset = (rectSize - cellSize) / 2;
            ipGraphics.lineStyle(1.5, ipColor, 1.0);
            ipGraphics.beginFill(ipColor, 0.2);
            ipGraphics.drawRect(ipCellX - rectOffset, ipCellY - rectOffset, rectSize, rectSize);
            ipGraphics.endFill();

            // Triangle (filled, no outline)
            const length = Math.sqrt(dv[0] * dv[0] + dv[1] * dv[1]) || 1;
            const dirX = dv[0] / length;
            const dirY = dv[1] / length;

            const tipX = cx + dirX * half;
            const tipY = cy + dirY * half;
            const base1X = cx - dirX * half + (-dirY) * half;
            const base1Y = cy - dirY * half + dirX * half;
            const base2X = cx - dirX * half - (-dirY) * half;
            const base2Y = cy - dirY * half - dirX * half;

            ipGraphics.lineStyle(0);
            ipGraphics.beginFill(ipColor, 1.0);
            ipGraphics.moveTo(tipX, tipY);
            ipGraphics.lineTo(base1X, base1Y);
            ipGraphics.lineTo(base2X, base2Y);
            ipGraphics.closePath();
            ipGraphics.endFill();
        }

        // Aggregate DPs
        const aggregatedDps = new Map();
        for (const org of organisms) {
            if (!org || !Array.isArray(org.dataPointers)) continue;
            const orgColor = this._getOrganismColor(org.organismId, org.energy, org.genomeHash, org.isDead);
            const orgActiveIndex = typeof org.activeDpIndex === "number" ? org.activeDpIndex : 0;
            org.dataPointers.forEach((dp, idx) => {
                if (!Array.isArray(dp) || dp.length < 2) return;
                const cellKey = `${dp[0]},${dp[1]}`;
                let entry = aggregatedDps.get(cellKey);
                if (!entry) {
                    entry = { indices: [], isActive: false, color: orgColor, x: dp[0], y: dp[1], prominentOrganism: org };
                    aggregatedDps.set(cellKey, entry);
                }
                if (!entry.indices.includes(idx)) entry.indices.push(idx);
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
                const text = new PIXI.Text({ text: "", style: { ...this.cellFont, fontSize: this.grid.getCurrentCellSize() * 0.45, fontWeight: "900", fill: entry.color, dropShadow: true, dropShadowColor: "rgba(0,0,0,0.8)", dropShadowBlur: 1, dropShadowAngle: Math.PI / 4, dropShadowDistance: 1 }});
                text.anchor.set(0.5);
                dpEntry = { graphics, text };
                this.dpGraphics.set(cellKey, dpEntry);
                this.grid.organismContainer.addChild(graphics, text);

                // Make it clickable
                graphics.interactive = true;
                graphics.buttonMode = true;
                graphics.on('pointertap', (event) => {
                    event.stopPropagation();
                    const [cx, cy] = cellKey.split(',').map(Number);
                    self.grid.cycleOrganismAtPosition(cx, cy);
                });
            }

            const { graphics: g, text: label } = dpEntry;
            g.clear();
            // DP marker slightly larger than cell for visibility
            const dpSize = cellSize + 4;
            const dpOffset = (dpSize - cellSize) / 2;
            const x = entry.x * cellSize - dpOffset;
            const y = entry.y * cellSize - dpOffset;

            const borderAlpha = entry.isActive ? 1.0 : 0.8;
            const borderWidth = entry.isActive ? 2.0 : 1.0;
            const fillAlpha = entry.isActive ? 0.45 : 0.15;
            g.lineStyle(borderWidth, entry.color, borderAlpha);
            g.beginFill(entry.color, fillAlpha);
            g.drawRect(x, y, dpSize, dpSize);
            g.endFill();
            if (label) {
                label.text = entry.indices.join(",");
                label.style.fill = entry.color;
                // Center text in the larger DP marker
                label.position.set(x + dpSize / 2, y + dpSize / 2);
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
    
    /**
     * Returns the cell background colour of a molecule type.
     *
     * @param {string} typeName - The molecule type name, e.g. 'CODE'.
     * @returns {number} The 0xRRGGBB background colour, the UNKNOWN colour for an unknown type.
     */
    getBackgroundColorForType(typeName) {
        return moleculeTypeEntry(typeName).bg;
    }

    /**
     * Returns the colour the value of a molecule type is written in.
     *
     * @param {string} typeName - The molecule type name, e.g. 'CODE'.
     * @returns {number} The 0xRRGGBB text colour, the UNKNOWN colour for an unknown type.
     */
    getTextColorForType(typeName) {
        return moleculeTypeEntry(typeName).text;
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

        // On a toroidal world the view can span the seam: each region drawn keeps a texture of its
        // own, newest last, so that the regions on both sides of the seam are shown together
        this._torusPieces = [];
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
        this._removeTorusPieces(this._torusPieces.length);
    }

    /**
     * Removes the oldest regions kept for a toroidal world.
     * @param {number} count - How many to remove.
     * @private
     */
    _removeTorusPieces(count) {
        for (const { sprite } of this._torusPieces.splice(0, count)) {
            this.grid.cellContainer.removeChild(sprite);
            sprite.destroy({ texture: true, textureSource: true });
        }
    }

    /**
     * For viewport-based rendering, a region is only "fully loaded" if it's
     * completely contained within the currently rendered region.
     * @param {{x1: number, y1: number, x2: number, y2: number}} viewport
     * @returns {boolean}
     */
    isRegionFullyLoaded(viewport) {
        if (this.grid.torus) {
            return this._torusPieces.some(({ region: r }) =>
                viewport.x1 >= r.x1 && viewport.y1 >= r.y1 && viewport.x2 <= r.x2 && viewport.y2 <= r.y2);
        }
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

    /** Regions kept at most for a toroidal world: four around a corner of the seam, and the prefetched ones. */
    static MAX_TORUS_PIECES = 8;

    /**
     * Keeps the pixels just drawn as a region of a toroidal world, beside the regions drawn
     * before; a region seen again replaces its older texture.
     * @param {{x1: number, y1: number, x2: number, y2: number}} region - Region drawn, in cells.
     * @param {number} width - Texture width in pixels.
     * @param {number} height - Texture height in pixels.
     * @param {number} scale - Pixels per cell.
     * @private
     */
    _keepTorusPiece(region, width, height, scale) {
        const same = this._torusPieces.findIndex(({ region: r }) =>
            r.x1 === region.x1 && r.y1 === region.y1 && r.x2 === region.x2 && r.y2 === region.y2);
        if (same !== -1) {
            const [old] = this._torusPieces.splice(same, 1);
            this.grid.cellContainer.removeChild(old.sprite);
            old.sprite.destroy({ texture: true, textureSource: true });
        }
        // Each region needs a canvas of its own: a texture shows whatever its canvas holds now
        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        canvas.getContext('2d').putImageData(new ImageData(this._pixelBuffer, width, height), 0, 0);
        const sprite = new PIXI.Sprite(PIXI.Texture.from(canvas, { scaleMode: 'nearest' }));
        sprite.x = region.x1 * scale;
        sprite.y = region.y1 * scale;
        this.grid.cellContainer.addChild(sprite);
        this._torusPieces.push({ region, sprite });
        if (this._torusPieces.length > ZoomedOutRendererStrategy.MAX_TORUS_PIECES) {
            this._removeTorusPieces(this._torusPieces.length - ZoomedOutRendererStrategy.MAX_TORUS_PIECES);
        }
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

        // Use Uint32Array view for bulk pixel operations (single fill call vs. millions of byte writes)
        const uint32View = new Uint32Array(this._pixelBuffer.buffer);

        // Fill buffer with empty cell background color
        const emptyColor = this._hexToRgb(NO_DATA_COLOR);
        const emptyPixel = (255 << 24) | (emptyColor.b << 16) | (emptyColor.g << 8) | emptyColor.r;
        uint32View.fill(emptyPixel);

        // --- Step 2: Draw cells into pixel buffer ---
        const getColor = (typeName) => this.grid.detailedRenderer.getBackgroundColorForType(typeName);

        const fillCell = (cellX, cellY, colorPixel) => {
            // Position relative to region origin, scaled
            const localX = (cellX - clampedX1) * scale;
            const localY = (cellY - clampedY1) * scale;

            // Draw scale×scale pixels for this cell using bulk row fills
            for (let dy = 0; dy < scale; dy++) {
                const rowStart = (localY + dy) * textureWidth + localX;
                uint32View.fill(colorPixel, rowStart, rowStart + scale);
            }
        };
        const asPixel = (color) => {
            const rgb = this._hexToRgb(color);
            return (255 << 24) | (rgb.b << 16) | (rgb.g << 8) | rgb.r;
        };

        // A border cannot be seen at one to four pixels per cell, so a marked cell is filled with
        // the mark's colour instead of its own. Cells that the response names are collected while
        // they are drawn, so that a marked cell it does not name can be recognized as empty below.
        const named = this.grid.mutationMarks ? new Set() : null;



        for (let i = 0; i < cells.length; i++) {
            const cell = cells[i];
            const coords = cell.coordinates;
            if (!Array.isArray(coords) || coords.length < 2) continue;

            const cellX = coords[0];
            const cellY = coords[1];

            // Skip cells outside the region
            if (cellX < clampedX1 || cellX >= clampedX2 || cellY < clampedY1 || cellY >= clampedY2) continue;

            const typeName = moleculeTypeName(cell.moleculeType);
            const isEmpty = typeName === 'CODE' && cell.moleculeValue === 0 && cell.ownerId === 0;

            let mark = null;
            if (named !== null && this.grid.isInMarkBounds(cellX, cellY)) {
                named.add(cellY * worldWidth + cellX);
                mark = this.grid.markAt(cellX, cellY, typeName, cell.moleculeValue, isEmpty);
            }

            if (mark) {
                fillCell(cellX, cellY, asPixel(this.grid.markColor(mark)));
                continue;
            }
            if (isEmpty) continue; // Already filled with empty color

            fillCell(cellX, cellY, asPixel(getColor(typeName)));
        }

        // A cell the response does not name is empty, and an empty cell a deletion of the lineage
        // cleared carries its mark there as much as anywhere else.
        if (named !== null) {
            for (const mark of this.grid.mutationMarks.values()) {
                if (!mark.afterEmpty) continue;
                if (mark.x < clampedX1 || mark.x >= clampedX2 || mark.y < clampedY1 || mark.y >= clampedY2) continue;
                if (named.has(mark.y * worldWidth + mark.x)) continue;
                fillCell(mark.x, mark.y, asPixel(this.grid.markColor(mark)));
            }
        }

        if (this.grid.torus) {
            this._keepTorusPiece({ x1: clampedX1, y1: clampedY1, x2: clampedX2, y2: clampedY2 }, textureWidth, textureHeight, scale);
            return;
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
        // Marker size: always slightly larger than the cell for visibility
        const MARKER_SIZE = Math.max(6, scale + 3);

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
                ipGraphics.on('pointertap', (event) => {
                    event.stopPropagation();
                    const current = self.grid.currentOrganisms?.find(o => o.organismId === organismId);
                    if (current && Array.isArray(current.ip)) {
                        self.grid.cycleOrganismAtPosition(current.ip[0], current.ip[1]);
                    }
                });
            }
            ipGraphics.clear();

            const ipColor = this._getOrganismColor(organismId, energy, organism.genomeHash, organism.isDead);
            // Position at cell center, scaled to pixel coordinates
            const centerX = (ip[0] + 0.5) * scale;
            const centerY = (ip[1] + 0.5) * scale;

            // Dark outline for contrast against any background
            ipGraphics.lineStyle(1, 0x000000, 0.5);
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
            const orgColor = this._getOrganismColor(prominentOrganism.organismId, prominentOrganism.energy, prominentOrganism.genomeHash, prominentOrganism.isDead);

            let dpEntry = this.dpGraphics.get(cellKey);
            if (!dpEntry) {
                dpEntry = { graphics: new PIXI.Graphics() };
                this.dpGraphics.set(cellKey, dpEntry);
                 // Make it clickable
                dpEntry.graphics.interactive = true;
                dpEntry.graphics.buttonMode = true;
                dpEntry.graphics.on('pointertap', (event) => {
                    event.stopPropagation();
                    const [cx, cy] = cellKey.split(',').map(Number);
                    self.grid.cycleOrganismAtPosition(cx, cy);
                });
            }
            dpEntry.graphics.clear();

            const dpCoords = cellKey.split(',').map(Number);
            // Position at cell center, scaled to pixel coordinates
            const centerX = (dpCoords[0] + 0.5) * scale;
            const centerY = (dpCoords[1] + 0.5) * scale;
            const halfSize = MARKER_SIZE / 2;
            // Dark outline for contrast against any background
            dpEntry.graphics.lineStyle(1, 0x000000, 0.5);
            dpEntry.graphics.beginFill(orgColor, 0.7);
            dpEntry.graphics.drawRect(centerX - halfSize, centerY - halfSize, MARKER_SIZE, MARKER_SIZE);
            dpEntry.graphics.endFill();
            this.grid.organismContainer.addChild(dpEntry.graphics);
        }
    }
}
