import { EnvironmentApi, setTypeMappings } from './api/EnvironmentApi.js';
import { OrganismApi } from './api/OrganismApi.js';
import { SimulationApi } from './api/SimulationApi.js';
import { EnvironmentGrid } from './EnvironmentGrid.js';
import { buildMarkMap } from './MutationMarks.js';
import { buildGenomeChain, DescentColouring, depthColourInt, LINE_PALETTE_PAIRS } from './DescentColours.js';
import { moleculeTypeName } from './MoleculeTypePalette.js';
import { MinimapView } from './ui/minimap/MinimapView.js';
import { nearestLevelIndex, ZOOM_LEVELS } from './interaction/ZoomLevels.js';
import { OrganismInstructionView } from './ui/organism/OrganismInstructionView.js';
import { OrganismSourceView } from './ui/organism/OrganismSourceView.js';
import { OrganismStateView } from './ui/organism/OrganismStateView.js';
import { LineageStrip } from './ui/organism/LineageStrip.js';
import { ValueFormatter } from './utils/ValueFormatter.js';
import { OrganismPanelManager } from './ui/panels/OrganismPanelManager.js';
import { DescentSection } from './ui/panels/DescentSection.js';
import { TickPanelManager } from './ui/panels/TickPanelManager.js';
import * as TickGrid from './TickGrid.js';
import { loadingManager } from './ui/LoadingManager.js';
import { WaitingOverlay } from './ui/WaitingOverlay.js';
import { dismissClosableNotice, hideNotice, showErrorNotice } from '../../shared/notice/Notice.js';
import {
    RunUnavailableError, chooseInitialRunId, fetchPipelineStatus, isRunStarting,
    showLoadFailedNotice, showRunUnavailableNotice
} from '../../shared/run/RunAvailability.js';

/**
 * The main application controller. It initializes all components, manages the application state,
 * and orchestrates the data flow between the API clients and the UI views.
 *
 * This class is the central hub of the visualizer.
 *
 * @class AppController
 */
export class AppController {
    /**
     * Initializes the AppController, creating instances of all APIs, views, and
     * setting up the initial state and event listeners.
     */
    constructor() {
        // APIs
        this.simulationApi = new SimulationApi();
        this.environmentApi = new EnvironmentApi();
        this.organismApi = new OrganismApi();

        // Request controllers for cancellation
        this.simulationRequestController = null;
        this.organismSummaryRequestController = null;
        this.organismDetailsRequestController = null;
        this.organismMutationsRequestController = null;
        
        // State
        // Zoom level in pixels per cell (persisted); the smallest level when none is stored, the
        // nearest level to a size that is no longer one
        const storedZoomSize = Number(localStorage.getItem('evochora-zoom-size'));
        const initialZoomSize = storedZoomSize > 0
            ? ZOOM_LEVELS[nearestLevelIndex(ZOOM_LEVELS, storedZoomSize)]
            : ZOOM_LEVELS[0];
        
        // Counts the tick-range requests sent, so a late answer to an earlier one is recognised
        this._tickRangeRequest = 0;
        this.state = {
            currentTick: 0,
            maxTick: null,
            ranges: [],
            worldShape: null,
            runId: null,
            selectedOrganismId: null, // Track selected organism across tick changes
            previousTick: null, // For change detection
            previousOrganisms: null, // For change detection
            previousOrganismDetails: null, // For change detection in details
            organisms: [], // Current organisms for the tick
            metadata: null, // Simulation metadata (includes organism config)
            // Root of descent the organisms are coloured against: an organism id or 'all' for the
            // virtual root above the founders, both held until changed; or 'auto', which every
            // load resolves anew as the common ancestor of the living at the loaded tick
            root: 'auto',
            descent: null, // Descent of the loaded tick's organisms from the root, as the server answered
        };
        this.programArtifactCache = new Map(); // Cache for program artifacts
        // Colours of the loaded tick's organisms, built from the descent of the same answer
        this.descentColouring = new DescentColouring(null);
        this._palettePair = 0;         // pair of the palette the shown root's lines start at
        this._colouredRootId = null;   // id of the root coloured last, null before the first
        this._pairByRoot = new Map();  // root id -> the pair it was given in this session
        this._lastPairHandedOut = 0;   // the pair given to the root that was new last
        // Tells the user, once the next tick has loaded, that the root asked for was not indexed
        this._rootNotice = null;              // { title, text, detail } | null
        // The organism the mutations of the selected lineage were fetched for
        this._mutationsOrganismId = null;     // int | null
        // How many generations each ancestor lies back from the organism whose details were loaded
        this._lineageDistances = null;        // { organismId: int, distances: Map<int, int> } | null
        // Genome changes along the ancestry of the organism whose details were loaded
        this._genomeChain = null;             // result of buildGenomeChain | null
        // Marks of the selected lineage's mutations, and the marks handed to the grid: none while
        // the selected organism is dead at the shown tick
        this._lineageMarkMap = null;          // Map<string, object> | null
        this._marksShown = null;              // Map<string, object> | null
        // The ancestry strip in the organism info line
        this._lineageStrip = null;            // LineageStrip | null

        // Config for renderer
        const defaultConfig = {
            worldSize: [100, 30],
            cellSize: 22,
            backgroundColor: '#1a1a28' // Border area visible when scrolling beyond grid
        };
        
        // Components
        this.worldContainer = document.querySelector('.world-container');
        this.renderer = new EnvironmentGrid(this, this.worldContainer, defaultConfig, this.environmentApi);

        // Minimap is initialized in init() after renderer.init() completes
        // (renderer.init() clears the container, so we must add minimap after)
        this.minimapView = null;
        this.lastMinimapTick = null;
        // How the shown minimap was asked for (a size, or true for the default), null before any
        this._minimapShownRequest = null;
        // Counts the minimap reloads sent, so that only the answer to the latest one is drawn
        this._minimapReloadRequest = 0;
        // Loads of the viewport under way; a minimap reload waits until none is
        this._viewportLoadsInFlight = 0;

        // Apply the initial zoom level (persisted from localStorage)
        this.renderer.applyZoom(initialZoomSize, { x: 0, y: 0 });

        // Initialize panel managers
        this.initPanelManagers();

        // Wire loading infrastructure to timeline canvas
        loadingManager.setTickPanelManager(this.tickPanelManager);
        this.waitingOverlay = new WaitingOverlay({
            environmentApi: this.environmentApi,
            organismApi: this.organismApi
        });
        /** Run shown before a run change that failed, offered as the way back. */
        this._runBeforeFailedChange = null;

        // Organism details views (render into organism-details container in the panel)
        const detailsRoot = document.getElementById('organism-details');
        if (!detailsRoot) {
            console.warn('Organism details root element not found');
        }
        this.instructionView = new OrganismInstructionView(detailsRoot);
        this.stateView = new OrganismStateView(detailsRoot);
        this.sourceView = new OrganismSourceView(detailsRoot);

        // Load initial state (runId, tick, organism, root) from URL if present
        this.loadFromUrl();

        // Setup viewport change handler (environment only, organisms are cached per tick)
        this.renderer.onViewportChange = () => {
            this.loadEnvironmentForCurrentViewport();
        };

        // Setup camera moved handler (immediate visual feedback, not debounced)
        this.renderer.onCameraMoved = () => {
            this.updateMinimapViewport();
        };

        // A zoom gesture shows the picture drawn so far at another size; the slider follows it, the
        // minimap keeps its visibility, and the zoom comes to rest on a level when the gesture ends
        this.renderer.onZoomGestureStart = () => {
            this.minimapView?.setGestureActive(true);
        };
        this.renderer.onZoomPreview = (position) => {
            this.minimapView?.showZoomPosition(position);
        };
        this.renderer.onZoomCommit = (size, anchor) => {
            this.applyZoomSize(size, anchor);
        };

        // Keep run selector display in sync when state changes externally
        window.addEventListener('tickChanged', () => window.runSelectorPanel?.updateCurrent?.());
    }
    
    /**
     * Changes the active run and reloads all dependent data.
     * @param {string} runId - The run identifier to load.
     * @param {object} [options]
     * @param {boolean} [options.retry=false] - Loads the run even though it is already the current
     *        one, as when a failed change is tried again.
     */
    async changeRun(runId, { retry = false } = {}) {
        const trimmed = runId ? runId.trim() : null;
        if (!trimmed || (trimmed === this.state.runId && !retry)) {
            return;
        }
        // The URL still names the run shown before; it is rewritten once the new run is loaded
        const previousRunId = retry ? this._runBeforeFailedChange : this.state.runId;

        try {
            dismissClosableNotice();

            // Stop polling and cancel ongoing requests
            this._stopMaxTickPolling();
            this.waitingOverlay.cancel();
            if (this.simulationRequestController) this.simulationRequestController.abort();
            if (this.organismSummaryRequestController) this.organismSummaryRequestController.abort();
            if (this.organismDetailsRequestController) this.organismDetailsRequestController.abort();

            // Reset state
            this.state.runId = trimmed;
            this.state.currentTick = 0;
            this.state.selectedOrganismId = null;
            this.renderer?.setSelectedOrganism(null);
            this.minimapView?.setSelectedOrganism(null);
            this._clearLineageMutations();
            this.state.previousTick = null;
            this.state.previousOrganisms = null;
            this.state.previousOrganismDetails = null;
            this._resetDescent();
            this.minimapView?.organismOverlay?.clearSpriteCache();
            this.state.maxTick = null;
            this.state.ranges = [];
            this.state.organisms = [];
            this.programArtifactCache.clear();

            // Reset organism panel
            this.organismPanelManager?.updateInfo(0, 0);
            this.organismPanelManager?.updateList([], null);
            this.clearOrganismDetails();

            // Reset minimap state
            this.lastMinimapTick = null;
            this.minimapView?.clear();
            
            this.tickPanelManager.updateTickDisplay(this.state.currentTick, this.state.maxTick);

            await this._loadRun(null, null);

            // Load initial tick for new run
            await this.navigateToTick(this.state.currentTick, true);
            window.runSelectorPanel?.updateCurrent?.();
            this._startMaxTickPolling();
        } catch (error) {
            if (error.name === 'AbortError') {
                return;
            }
            if (error instanceof RunUnavailableError) {
                // The page now belongs to the run without data, so a reload asks for it again
                this.updateUrlState();
                await showRunUnavailableNotice(error);
                return;
            }
            console.error('Failed to change run:', error);
            this._runBeforeFailedChange = previousRunId;
            const actions = [];
            if (previousRunId) {
                actions.push({ label: 'Back to previous run', onClick: () => window.location.reload() });
            }
            actions.push({
                label: 'Retry',
                primary: true,
                onClick: () => {
                    hideNotice();
                    this.changeRun(trimmed, { retry: true });
                }
            });
            showErrorNotice({ title: 'Could not switch the run', detail: error.message, actions });
        }
    }
    
    /**
     * Handles the selection of an organism, either from the UI or programmatically.
     * This method updates the application state, refreshes UI elements, and loads
     * the detailed data for the selected organism.
     *
     * @param {string|number|null} organismId - The ID of the organism to select, or null to deselect.
     */
    async selectOrganism(organismId) {
        const numericId = organismId ? parseInt(organismId, 10) : null;

        if (organismId && isNaN(numericId)) {
            console.error(`Invalid organismId provided to selectOrganism: ${organismId}`);
            return;
        }

        // Update state and URL
        this.state.selectedOrganismId = numericId ? String(numericId) : null;
        this.updateUrlState();

        // Update organism list selection in panel
        this.updateOrganismListSelection();

        // Re-render organism markers so selected organism turns white
        if (this.renderer && this.renderer.currentOrganisms) {
            this.renderer.renderOrganisms(this.renderer.currentOrganisms);
        }

        // Update pulse animations on environment grid and minimap
        this.renderer?.setSelectedOrganism(this.state.selectedOrganismId);
        this.minimapView?.setSelectedOrganism(this.state.selectedOrganismId);

        if (this.state.selectedOrganismId) {
            // An organism is selected - load its lineage's mutations and its details
            await this._ensureLineageMutations(numericId);
            await this.loadOrganismDetails(numericId);
        } else {
            // No organism is selected (deselection) - clear details and marks
            this._clearLineageMutations();
            this.clearOrganismDetails();
        }
    }
    
    /**
     * Clears the organism details section and resets the view state.
     * @private
     */
    clearOrganismDetails() {
        const detailsRoot = document.getElementById('organism-details');
        if (detailsRoot) {
            // Clear each section
            const sections = detailsRoot.querySelectorAll('[data-section]');
            sections.forEach(section => {
                section.innerHTML = '';
            });
        }
        
        this._lineageStrip?.destroy();
        this._lineageStrip = null;

        // Reset view states so they re-render on next selection
        this.sourceView.setProgram(null);
        this.stateView.setProgram(null);
        this.instructionView.setProgram(null);
        this.instructionView.setLabelNamespaceMask(0);
        this.state.previousOrganismDetails = null;
    }
    
    /**
     * Updates the selection state in the organism list panel.
     * @private
     */
    updateOrganismListSelection() {
        this.organismPanelManager?.updateSelection(this.state.selectedOrganismId);
    }
    
    /**
     * Brings the zoom to rest at a size and draws the viewport at it. Ends a zoom gesture: the
     * slider and the minimap show the level reached, and the viewport is drawn anew only when the
     * size the cells are drawn at changed.
     * @param {number} size - Pixels per cell, one of the zoom levels.
     * @param {{x: number, y: number}} anchor - Point of the viewport that stays in place.
     */
    async applyZoomSize(size, anchor) {
        const changed = this.renderer.applyZoom(size, anchor);
        localStorage.setItem('evochora-zoom-size', String(size));
        this.minimapView?.updateZoomButton(size);
        this.minimapView?.setGestureActive(false);
        this.updateMinimapViewport();

        if (changed) {
            await this.navigateToTick(this.state.currentTick, true);
        } else {
            this.renderer.requestViewportLoad();
        }
    }

    /**
     * Initializes the tick and organism panel managers.
     * @private
     */
    initPanelManagers() {
        // Timeline panel (tick input, interactive timeline track, multiplier, keyboard shortcuts)
        this.tickPanelManager = new TickPanelManager({
            panel: document.getElementById('timeline-panel'),
            tickInput: document.getElementById('tick-input'),
            tickSuffix: document.getElementById('tick-total-suffix'),
            prevLargeBtn: document.getElementById('btn-prev-large'),
            prevSmallBtn: document.getElementById('btn-prev-small'),
            nextSmallBtn: document.getElementById('btn-next-small'),
            nextLargeBtn: document.getElementById('btn-next-large'),
            trackContainer: document.getElementById('timeline-track-container'),
            trackCanvas: document.getElementById('timeline-track'),
            tooltip: document.getElementById('timeline-tooltip'),
            rootMark: document.getElementById('timeline-root-mark'),
            multiplierInput: document.getElementById('large-step-multiplier'),
            multiplierWrapper: document.getElementById('multiplier-wrapper'),
            multiplierSuffix: document.getElementById('multiplier-suffix'),
            onNavigate: (targetTick) => this.navigateToTick(targetTick),
            getState: () => ({
                currentTick: this.state.currentTick,
                maxTick: this.state.maxTick,
                runId: this.state.runId,
                ranges: this.state.ranges,
                samplingInterval: this.state.metadata?.samplingInterval || 1
            })
        });

        // Organism panel
        this.organismPanelManager = new OrganismPanelManager({
            panel: document.getElementById('organism-panel'),
            panelHeader: document.getElementById('organism-panel-header'),
            listContainer: document.getElementById('organism-list-container'),
            listCollapseBtn: document.getElementById('organism-list-collapse'),
            organismCount: document.getElementById('organism-count'),
            organismList: document.getElementById('organism-list'),
            selectedDisplay: document.getElementById('organism-selected-display'),
            filterInput: document.getElementById('organism-filter'),
            filterClear: document.getElementById('organism-filter-clear'),
            onOrganismSelect: (organismId) => this.selectOrganism(organismId),
            onPositionClick: (x, y) => this.renderer?.centerOn(x, y),
            onTickClick: (tick) => this.navigateToTick(tick)
        });

        // Descent section above the organism panel
        this.descentSection = new DescentSection({
            container: document.getElementById('descent-section'),
            onRootChange: (root) => this.setRoot(root),
            onHoldRoot: () => this.holdRoot(),
            onPositionClick: (x, y) => this.renderer?.centerOn(x, y)
        });
    }
    
    /**
     * Fetches and displays detailed information for a specific organism in the panel.
     * This includes static info, runtime state, instructions, and source code with annotations.
     * 
     * @param {number} organismId - The ID of the organism to load.
     * @param {boolean} [isForwardStep=false] - True if navigating forward, used for change highlighting.
     * @returns {Promise<void>} A promise that resolves when the details are loaded and displayed.
     */
    async loadOrganismDetails(organismId, isForwardStep = false) {
        // Abort previous request if it's still running
        if (this.organismDetailsRequestController) {
            this.organismDetailsRequestController.abort();
        }
        this.organismDetailsRequestController = new AbortController();
        const signal = this.organismDetailsRequestController.signal;

        try {
            dismissClosableNotice();
            const details = await this.organismApi.fetchOrganismDetails(
                this.state.currentTick,
                organismId,
                this.state.runId,
                { signal }
            );
            
            // API returns "static" not "staticInfo"
            const staticInfo = details.static || details.staticInfo;
            const state = details.state;

            if (details && staticInfo) {
                // Resolve the program artifact once and give every view its context before any of
                // them renders. Views read it while updating — the state view resolves procedure
                // parameters through it, the instruction view resolves label names — so setting it
                // afterwards would leave the first render of a newly selected organism incomplete.
                const artifact = staticInfo.programId
                    ? (this.programArtifactCache.get(staticInfo.programId) || null)
                    : null;
                this.stateView.setProgram(artifact);
                this.instructionView.setProgram(artifact);
                this.instructionView.setLabelNamespaceMask(details.labelNamespaceMask);
                this.sourceView.setProgram(artifact);

                // The ancestry chain names the parent first, so an ancestor's place in it is how
                // many generations it lies back; the tooltip of a mutated cell shows that distance.
                this._lineageDistances = {
                    organismId,
                    distances: new Map([
                        [organismId, 0],
                        ...(details.lineage || []).map((entry, index) => [entry.organismId, index + 1])
                    ])
                };

                // The genome depth of every ancestor colours the strip and the mutation marks; the
                // marks are drawn again once the depths of a newly selected organism are known
                const previousChain = this._genomeChain;
                this._genomeChain = buildGenomeChain(
                    { organismId, genomeHash: staticInfo.genomeHash }, details.lineage || []);
                if (previousChain?.organismId !== organismId && this._marksShown) {
                    this.renderer?.refreshMutationMarks();
                }

                // Update instruction view with last and next instructions
                if (state && state.instructions) {
                    this.instructionView.update(state.instructions, this.state.currentTick);
                } else {
                    this.instructionView.update(null, this.state.currentTick);
                }
                
                // Update info section (Birth, MR, Lineage)
                const infoEl = document.querySelector('[data-section="info"]');
                if (infoEl && staticInfo && state) {
                    const birthTick = staticInfo.birthTick;
                    const mrValue = state.moleculeMarkerRegister != null ? state.moleculeMarkerRegister : 0;

                    // Look up isDead/deathTick from already-loaded organism summary data
                    const summaryOrg = (this.state.organisms || []).find(o => String(o.organismId) === String(organismId));
                    const isDead = summaryOrg?.isDead || false;
                    const deathTick = summaryOrg?.deathTick;

                    // Birth is clickable (navigates to tick)
                    const birthDisplay = birthTick != null
                        ? `<span class="clickable-tick" data-tick="${birthTick}">${ValueFormatter.formatGroupedHtml(birthTick)}</span>`
                        : '-';

                    // Show Birth/Death when dead, otherwise just Birth
                    let birthDeathLabel;
                    if (isDead && deathTick != null && deathTick >= 0) {
                        const deathDisplay = `<span class="clickable-tick" data-tick="${deathTick}">${ValueFormatter.formatGroupedHtml(deathTick)}</span>`;
                        birthDeathLabel = `Birth/Death: ${birthDisplay}/${deathDisplay}`;
                    } else {
                        birthDeathLabel = `Birth: ${birthDisplay}`;
                    }

                    infoEl.innerHTML = `<div class="organism-info-line lineage-line">`
                        + `<span class="lineage-line-facts">${birthDeathLabel}  MR: ${mrValue}</span>`
                        + `<span class="lineage-strip-label">Lineage:</span>`
                        + `<div class="lineage-strip"></div>`
                        + `<span class="lineage-strip-summary">${this._genomeChain.entries.length - 1} gen · `
                        + `depth ${this._genomeChain.changes}</span></div>`;

                    // The ancestry strip: founder left, direct parent right
                    this._lineageStrip?.destroy();
                    this._lineageStrip = new LineageStrip(infoEl.querySelector('.lineage-strip'), {
                        onSelect: (ancestorId) => this.selectOrganism(ancestorId)
                    });
                    const aliveIds = new Set((this.state.organisms || [])
                        .filter(o => !o.isDead).map(o => o.organismId));
                    this._lineageStrip.render(this._genomeChain, aliveIds);

                    // Bind click handlers
                    infoEl.querySelectorAll('.clickable-tick').forEach(el => {
                        el.addEventListener('click', (e) => {
                            e.stopPropagation();
                            const tick = parseInt(el.dataset.tick, 10);
                            if (!isNaN(tick)) {
                                this.navigateToTick(tick);
                            }
                        });
                    });
                }
                
                // Update state view with runtime data (starts with DP, no IP/DV/ER)
                if (state) {
                    const previousState = (isForwardStep && this.state.previousOrganismDetails && 
                                          this.state.previousOrganismDetails.organismId === organismId) 
                                         ? this.state.previousOrganismDetails.state 
                                         : null;
                    this.stateView.update(state, isForwardStep, previousState, staticInfo);
                }
                
                // Update Source View with the current execution position
                if (artifact) {
                    this.sourceView.updateExecutionState(state, staticInfo, details.labelNamespaceMask);
                }
                
                // Save current details for next comparison
                this.state.previousOrganismDetails = details;
            } else {
                console.warn('No static info in details:', details);
            }
        } catch (error) {
            // Ignore AbortError, as it's an expected cancellation
            if (error.name === 'AbortError') {
                // Request aborted by user navigation - expected
                return;
            }
            console.error('Failed to load organism details:', error);
            this.clearOrganismDetails();
            showErrorNotice({ title: 'Could not load the organism details', detail: error.message, closable: true });
        }
    }
    
    /**
     * Initializes the entire application.
     * It initializes the renderer, fetches initial metadata (like world shape and program artifacts),
     * gets the available tick range, and loads the data for the initial tick.
     * @returns {Promise<void>} A promise that resolves when the application is fully initialized.
     */
    async init() {
        try {
            dismissClosableNotice();

            // Ensure we have an initial runId (the starting run, else the latest, if none provided)
            await this.ensureInitialRunId();
            if (!this.state.runId) {
                throw new RunUnavailableError(null);
            }

            // Initialize renderer
            this._initInProgress = true;
            loadingManager.show('Initializing renderer');
            await this.renderer.init();

            // Create minimap panel (positioned fixed, appended to body)
            this.minimapView = new MinimapView(
                (worldX, worldY) => {
                    this.renderer.centerOn(worldX, worldY);
                },
                (size) => {
                    this.applyZoomSize(size, this.renderer.viewportCenter());
                },
                () => this._reloadMinimap()
            );
            this.minimapView.restoreState(); // Restore expanded/collapsed state
            this.minimapView.setTorus(this.renderer.torus);
            this.minimapView.updateZoomButton(this.renderer.getCurrentCellSize());
            this.minimapView.setOwnershipColorResolver(this._minimapOwnershipColorResolver());

            // Abort previous request if it's still running
            this.waitingOverlay.cancel();
            if (this.simulationRequestController) {
                this.simulationRequestController.abort();
            }
            this.simulationRequestController = new AbortController();
            const signal = this.simulationRequestController.signal;

            await this._loadRun(signal, (label, percent) => loadingManager.update(label, percent));

            // Wait for layout to be calculated before loading initial viewport
            // This ensures correct viewport size calculation on first load,
            // especially when browser window is on a high-DPI monitor.
            // Use triple RAF to ensure layout is fully calculated, especially on first load
            await new Promise(resolve => {
                requestAnimationFrame(() => {
                    requestAnimationFrame(() => {
                        requestAnimationFrame(resolve);
                    });
                });
            });

            // Additional small delay to ensure container dimensions are stable
            // This helps with monitor-specific timing issues
            await new Promise(resolve => setTimeout(resolve, 50));

            // Load initial tick, force reload to bypass optimization on first load
            loadingManager.update('Fetching environment', 45);
            await this.navigateToTick(this.state.currentTick, true);
            this._initInProgress = false;
            loadingManager.hide();
            window.runSelectorPanel?.updateCurrent?.();
            this._startMaxTickPolling();

        } catch (error) {
            this._initInProgress = false;
            loadingManager.hide();
            // Ignore AbortError, as it's an expected cancellation
            if (error.name === 'AbortError') {
                // Request aborted by user navigation - expected
                return;
            }
            if (error instanceof RunUnavailableError) {
                await showRunUnavailableNotice(error);
                return;
            }
            console.error('Failed to initialize application:', error);
            showLoadFailedNotice('Could not start the visualizer', error);
        }
    }
    
    /**
     * Periodically fetches and updates the maximum tick value from the server.
     * This method is designed to fail silently to avoid interrupting user navigation.
     *
     * @returns {Promise<void>} A promise that resolves when the update is attempted.
     * @private
     */
    async updateMaxTick() {
        try {
            if (await this._refreshTickRanges()) {
                this.tickPanelManager.updateTickDisplay(this.state.currentTick, this.state.maxTick);
            }
        } catch (error) {
            // Silently fail - don't interrupt navigation if update fails
            console.debug('Failed to update maxTick:', error);
        }
    }

    /**
     * Loads what the current run needs before its first tick can be shown: the metadata and the
     * range of recorded ticks. While the run is starting and either is not indexed yet, the
     * waiting overlay stays up until it is; a run that is not starting and lacks either fails with
     * a RunUnavailableError.
     *
     * @param {AbortSignal|null} signal - Cancels the metadata request.
     * @param {function(string, number): void|null} onStep - Receives the loading steps; given only
     *        while the initial loading indicator is shown, which is restored after a wait.
     * @returns {Promise<void>}
     * @private
     */
    async _loadRun(signal, onStep) {
        const runId = this.state.runId;

        onStep?.('Loading metadata', 15);
        let metadata = await this._fetchIndexedMetadata(runId, signal);
        if (!metadata) {
            if (!isRunStarting(await fetchPipelineStatus(), runId)) {
                throw new RunUnavailableError(runId);
            }
            metadata = await this._waitForRun(runId, () => this._fetchIndexedMetadata(runId, signal), onStep);
        }
        await this._applyMetadata(metadata);

        onStep?.('Loading tick range', 30);
        await this._refreshTickRanges();
        this.tickPanelManager.updateTickDisplay(this.state.currentTick, this.state.maxTick);
        if (this.state.maxTick === null) {
            this.state.maxTick = await this._waitForRun(runId, null, onStep);
            this.tickPanelManager.updateTickDisplay(this.state.currentTick, this.state.maxTick);
        }
    }

    /**
     * Shows the waiting overlay until the check yields a value; without a check, until the run
     * has tick data.
     * @private
     */
    async _waitForRun(runId, check, onStep) {
        if (onStep) loadingManager.hide();
        const value = check
            ? await this.waitingOverlay.waitFor(runId, check)
            : await this.waitingOverlay.waitForData(runId);
        if (onStep) loadingManager.show('Loading');
        return value;
    }

    /**
     * Fetches the metadata of a run, or null while the index does not hold it.
     * @private
     */
    async _fetchIndexedMetadata(runId, signal) {
        try {
            return await this.simulationApi.fetchMetadata(runId, { signal });
        } catch (error) {
            if (error?.status === 404) return null;
            throw error;
        }
    }

    /**
     * Hands the metadata of the current run to every component that depends on it.
     * @private
     */
    async _applyMetadata(metadata) {
        this.state.metadata = metadata;

        // Set type mappings for Protobuf ID resolution in EnvironmentApi
        setTypeMappings(metadata);
        this.minimapView?.setMoleculeTypes(metadata?.moleculeTypes, metadata?.moleculeTypeShift);

        // Update sampling info in the UI
        this._refreshStepInfo();
        this.tickPanelManager?.loadMultiplierForRun(this.state.runId);
        this.tickPanelManager?.updateTooltips();

        // A toroidal world is shown across its seam; a bounded one as it always was
        const isTorus = metadata?.environment?.topology?.toUpperCase() === 'TORUS';
        this.renderer.setTopology(isTorus);
        this.minimapView?.setTorus(isTorus);

        if (metadata?.environment?.shape) {
            this.state.worldShape = Array.from(metadata.environment.shape);
            // Wait a frame before updating world shape to ensure devicePixelRatio is stable
            // This helps with monitor-specific initialization issues
            await new Promise(resolve => requestAnimationFrame(resolve));
            this.renderer.updateWorldShape(this.state.worldShape);
        }

        // Cache program artifacts with environment info for toroidal calculations
        if (Array.isArray(metadata?.programs)) {
            const envInfo = metadata?.environment;
            for (const program of metadata.programs) {
                if (program && program.programId && program.sources) {
                    if (envInfo) {
                        // Topology is configured as a string ("TORUS"), not per dimension
                        const isTorus = envInfo.topology?.toUpperCase() === 'TORUS';
                        const shape = envInfo.shape ? Array.from(envInfo.shape) : null;
                        program.envProps = {
                            worldShape: shape,
                            toroidal: shape ? shape.map(() => isTorus) : null
                        };
                    }
                    this.programArtifactCache.set(program.programId, program);
                }
            }
        }

        // Update organism panel manager with metadata
        this.organismPanelManager?.setMetadata(metadata);
    }

    /**
     * Reloads which ticks the run holds: the ranges the environment index reports, cut down to
     * the last tick the organism index has reached as well, so that navigation never asks for a
     * tick one of the indexes has not seen. When the environment index cannot be asked, what was
     * known before is kept.
     *
     * @returns {Promise<boolean>} Whether maxTick or the ranges changed.
     * @private
     */
    async _refreshTickRanges() {
        // An answer is applied only if it is for the run still shown and no later request was
        // sent meanwhile: a run change or a faster later poll makes it stale
        const runId = this.state.runId;
        const request = ++this._tickRangeRequest;
        const [envTicks, orgTickRange] = await Promise.all([
            this.environmentApi.fetchTickRange(runId).catch(e => this._tickRangeMiss('environment', e)),
            this.organismApi.fetchTickRange(runId).catch(e => this._tickRangeMiss('organism', e))
        ]);
        if (runId !== this.state.runId || request !== this._tickRangeRequest) {
            return false;
        }
        if (!envTicks) {
            return false;
        }
        if (!Array.isArray(envTicks.ranges)) {
            console.warn('The environment ticks endpoint answered without ranges; keeping the known ones', envTicks);
            return false;
        }
        let maxTick = envTicks.maxTick;
        if (orgTickRange?.maxTick !== undefined) {
            maxTick = Math.min(maxTick, orgTickRange.maxTick);
        }
        const ranges = TickGrid.clip(envTicks.ranges, maxTick);
        const changed = maxTick !== this.state.maxTick
            || JSON.stringify(ranges) !== JSON.stringify(this.state.ranges);
        this.state.maxTick = maxTick;
        this.state.ranges = ranges;
        if (changed) {
            this._refreshStepInfo();
        }
        return changed;
    }

    /**
     * Turns a failed tick-range fetch into "nothing known". A 404 means the index holds nothing
     * yet, which is a state and not worth a word; anything else is a fault of the server or the
     * connection and is written to the console, where the node's own log has the cause as well.
     * @param {string} index - Which index was asked.
     * @param {Error} error - What the fetch threw.
     * @returns {null}
     * @private
     */
    _tickRangeMiss(index, error) {
        if (error?.status !== 404) {
            console.warn(`Fetching the ${index} ticks of run ${this.state.runId} failed; keeping the known ranges:`, error);
        }
        return null;
    }

    /**
     * Shows the step the run is recorded at around the current tick. Before any tick is
     * recorded the step comes from the run's configured sampling interval.
     * @private
     */
    _refreshStepInfo() {
        const step = TickGrid.stepAt(this.state.ranges, this.state.currentTick)
            ?? this.state.metadata?.samplingInterval ?? 1;
        this.tickPanelManager?.updateStepInfo(step);
        this.tickPanelManager?.updateTooltips();
    }

    /**
     * Starts periodic polling for maxTick updates (every 5 seconds). While the run's ancestry is
     * still being read, or reading it failed, the same poll asks again for the organisms of the
     * shown tick. Stops any existing polling first.
     * @private
     */
    _startMaxTickPolling() {
        this._stopMaxTickPolling();
        this._maxTickPollTimer = setInterval(() => {
            this.updateMaxTick();
            this._refreshPendingDescent();
        }, 5000);
    }

    /**
     * Stops periodic maxTick polling.
     * @private
     */
    _stopMaxTickPolling() {
        if (this._maxTickPollTimer) {
            clearInterval(this._maxTickPollTimer);
            this._maxTickPollTimer = null;
        }
    }

    /**
     * Navigates the application to a specific tick.
     * This is the primary method for changing the current time point of the visualization.
     * It updates the state, refreshes the UI, and triggers the loading of all data for the new tick.
     * 
     * @param {number} tick - The target tick number to navigate to.
     * @param {boolean} [forceReload=false] - If true, reloads data even if the tick is the same.
     */
    async navigateToTick(tick, forceReload = false) {
        // First, always update maxTick from the server to get the latest value.
        await this.updateMaxTick();

        // Land on a recorded tick: the nearest one the run holds. Before anything is recorded
        // there is nothing to land on, and the tick is only kept from going below zero.
        let target = TickGrid.snap(this.state.ranges, tick) ?? Math.max(0, tick);

        // If maxTick is known, clamp the target to the new maximum.
        if (this.state.maxTick !== null) {
            target = Math.min(target, this.state.maxTick);
        }

        // If we're already on the target tick and not forcing a reload, do nothing.
        if (this.state.currentTick === target && !forceReload) {
            // Even if we bail, ensure the header bar reflects the clamped value,
            // giving feedback to the user if their input was out of bounds.
            this.tickPanelManager.updateTickDisplay(target, this.state.maxTick);
            return;
        }

        const previousTick = this.state.currentTick;
        
        // Check if this is a forward step (x -> x+1)
        const isForwardStep = (target === previousTick + 1);
        
        // Update state
        this.state.currentTick = target;
        
        // Update headerbar with current values
        this.tickPanelManager.updateTickDisplay(this.state.currentTick, this.state.maxTick);
        this._refreshStepInfo();

        // Update URL state
        this.updateUrlState();

        // Load environment and organisms for new tick
        await this.loadViewport(isForwardStep, previousTick);
    }
    
    /**
     * Updates the browser URL with the current application state (runId, tick, organism, root).
     * This enables deep linking and state persistence across page reloads. The root is written
     * as held, an organism id or 'all', or as 'auto'.
     * @private
     */
    updateUrlState() {
        try {
            const url = new URL(window.location.href);

            // Rebuild params in desired order: tick, organism, root, runId
            url.searchParams.delete('tick');
            url.searchParams.delete('organism');
            url.searchParams.delete('root');
            url.searchParams.delete('runId');

            if (this.state.currentTick !== null && this.state.currentTick !== undefined) {
                url.searchParams.set('tick', this.state.currentTick);
            }
            if (this.state.selectedOrganismId) {
                url.searchParams.set('organism', this.state.selectedOrganismId);
            }
            url.searchParams.set('root', String(this.state.root));
            if (this.state.runId) {
                url.searchParams.set('runId', this.state.runId);
            }

            // Use replaceState to avoid cluttering the browser history with every tick change
            window.history.replaceState({}, '', url);
        } catch (error) {
            console.warn('Failed to update URL state:', error);
        }
    }
    
    /**
     * Loads all necessary data for the current tick and viewport.
     * This includes both the environment cells and the organism summaries. It then
     * triggers updates for the renderer and the organism panel.
     * 
     * @param {boolean} [isForwardStep=false] - True if navigating forward, for change highlighting.
     * @param {number|null} [previousTick=null] - The previous tick number, for change detection.
     * @param {object} [options={}]
     * @param {boolean} [options.autoRoot=false] - Asks for the common ancestor of the living as the
     *        root, whatever root is held; the answer's root is held only once it has arrived.
     * @returns {Promise<void>} A promise that resolves when the viewport data is loaded.
     * @private
     */
    async loadViewport(isForwardStep = false, previousTick = null, { autoRoot = false } = {}) {
        // Abort previous organism summary request
        if (this.organismSummaryRequestController) {
            this.organismSummaryRequestController.abort();
        }
        this.organismSummaryRequestController = new AbortController();
        const organismSignal = this.organismSummaryRequestController.signal;

        // Track load generation so aborted loads can clean up correctly
        this._loadGeneration = (this._loadGeneration || 0) + 1;
        const myGeneration = this._loadGeneration;

        // The root this load asks for, as sent: an id, 'all', or 'auto'
        const requestedRoot = autoRoot ? 'auto' : this._rootToken();
        // Whether the organisms of this load were shown, and with them its root taken over
        let organismsShown = false;
        this._viewportLoadsInFlight++;

        // If init() is orchestrating progress, use its percentages; otherwise manage our own
        const managedExternally = loadingManager.isActive && this._initInProgress;
        if (!managedExternally) {
            loadingManager.show('Fetching environment');
        }

        try {
            dismissClosableNotice();

            // Request minimap only on tick change (not on panning)
            const needMinimap = this.state.currentTick !== this.lastMinimapTick;

            // Fire both requests simultaneously — organisms are fast, environment is slow
            const minimapRequest = needMinimap ? this._minimapRequest() : false;
            const environmentPromise = this.renderer.loadViewport(
                this.state.currentTick,
                this.state.runId,
                minimapRequest
            );
            // The environment is awaited only after the organisms. When the organisms fail or a
            // newer load aborts this one first, the environment is never awaited; its failure is
            // then no news, and the browser must not report it as unhandled. Awaiting it below
            // still throws as before.
            environmentPromise.catch(() => {});
            const organismPromise = this.organismApi.fetchOrganismsAtTick(
                this.state.currentTick,
                this.state.runId,
                { signal: organismSignal, root: requestedRoot }
            );

            // Process organisms as soon as they arrive (don't wait for environment)
            const organismResult = await organismPromise;
            if (!autoRoot && requestedRoot !== this._rootToken()) {
                // The resolved root was held while this load was on its way, and this answer
                // resolved its own: the tick is asked for again against the held root
                if (!managedExternally) {
                    loadingManager.hide();
                }
                await this.loadViewport(isForwardStep, previousTick);
                return;
            }
            const organisms = organismResult.organisms;
            this.state.totalOrganismCount = organismResult.totalOrganismCount;
            this._showOrganisms(organisms, organismResult.descent, requestedRoot, isForwardStep);
            organismsShown = true;

            // Reload organism details if one is selected
            if (this.state.selectedOrganismId) {
                const organismId = parseInt(this.state.selectedOrganismId, 10);
                if (!isNaN(organismId)) {
                    const stillExists = organisms.some(o => String(o.organismId) === this.state.selectedOrganismId);
                    if (stillExists) {
                        await this._ensureLineageMutations(organismId);
                        this._showLineageMarks();
                        await this.loadOrganismDetails(organismId, isForwardStep);
                    } else {
                        this.state.selectedOrganismId = null;
                        this._clearLineageMutations();
                        this.clearOrganismDetails();
                        this.updateOrganismListSelection();
                        this.renderer?.setSelectedOrganism(null);
                        this.minimapView?.setSelectedOrganism(null);
                    }
                }
            }

            // Wait for environment, then render grid + organisms on top
            loadingManager.update('Loading environment', managedExternally ? 75 : 66);
            const result = await environmentPromise;
            if (result?.minimap && this.state.worldShape) {
                this.minimapView.update(result.minimap, this.state.worldShape);
                this.lastMinimapTick = this.state.currentTick;
                this._minimapShownRequest = minimapRequest;
            }
            this.updateMinimapViewport();
            this.renderer.renderOrganisms(organisms);

            // Save current organisms for next comparison
            this.state.previousOrganisms = organisms;
            this.state.previousTick = this.state.currentTick;

            if (!managedExternally) {
                loadingManager.hide();
            }

            // Shown last: loading the details of the selected organism dismisses closable notices
            if (this._rootNotice) {
                showErrorNotice({ ...this._rootNotice, closable: true });
                this._rootNotice = null;
            }
        } catch (error) {
            if (error.name === 'AbortError') {
                // Only hide if no newer load has started (otherwise the new load manages the panel)
                if (!managedExternally && this._loadGeneration === myGeneration) {
                    loadingManager.hide();
                }
                return;
            }
            if (!managedExternally) {
                loadingManager.hide();
            }
            if (!organismsShown && error.status === 404 && AppController._isOrganismRoot(requestedRoot)) {
                // Only the organisms request speaks about the root: once its answer is shown, a 404
                // is another request's. The route answers 404 for a root that is not indexed, as
                // from a link to another run, but also for a missing tick or run. The tick is asked
                // for again against the common ancestor of the living; the held root and the URL
                // change only when that answer arrives, and the notice is shown once the load has
                // succeeded. Should the second request fail as well, the error is not the root's
                // and is reported as such.
                console.warn(`Tick ${this.state.currentTick} with root ${requestedRoot} was not found:`, error.message);
                this._rootNotice = {
                    title: 'The root is not in this run',
                    text: `Organism #${requestedRoot} is not indexed in this run. The root is now the `
                        + 'common ancestor of the organisms alive at this tick.',
                    detail: error.message
                };
                await this.loadViewport(isForwardStep, previousTick, { autoRoot: true });
                return;
            }
            // A notice waiting for a successful load would describe a root that was never changed
            if (!organismsShown) {
                this._rootNotice = null;
            }
            console.error('Failed to load viewport:', error);
            showErrorNotice({ title: 'Could not load this tick', detail: error.message, closable: true });
            // Update panel with empty list on error
            this.updateOrganismPanel([]);
        } finally {
            this._viewportLoadsInFlight--;
            // The minimap panel changed its size while a load was on its way
            if (this._viewportLoadsInFlight === 0 && this._minimapShownRequest !== null
                && this._minimapShownRequest !== this._minimapRequest()) {
                this._reloadMinimap();
            }
        }
    }

    /**
     * Returns how the minimap is asked for with an environment request, at the size the minimap
     * panel shows it.
     * @returns {boolean|number} A size in pixels of the longer edge, or true for the server's default.
     * @private
     */
    _minimapRequest() {
        return this.minimapView?.requestedSize() ?? true;
    }

    /**
     * Loads the minimap of the shown tick again, at the size the minimap panel now shows it; the
     * environment cells of the viewport come from the grid's cache.
     * @returns {Promise<void>} A promise that resolves when the minimap is drawn.
     * @private
     */
    async _reloadMinimap() {
        if (!this.state.previousOrganisms || !this.state.worldShape || this._viewportLoadsInFlight > 0) {
            return; // A load under way reloads the minimap at the current size when it ends
        }
        const tick = this.state.currentTick;
        const runId = this.state.runId;
        // Only the answer to the latest reload is drawn, whatever order the answers arrive in
        const request = ++this._minimapReloadRequest;
        const minimapRequest = this._minimapRequest();
        try {
            const result = await this.renderer.loadViewport(tick, runId, minimapRequest);
            if (result?.minimap && request === this._minimapReloadRequest
                && tick === this.state.currentTick && runId === this.state.runId) {
                this.minimapView.update(result.minimap, this.state.worldShape);
                this.lastMinimapTick = tick;
                this._minimapShownRequest = minimapRequest;
                this.updateMinimapViewport();
            }
        } catch (error) {
            if (error.name !== 'AbortError') {
                console.warn('Failed to load the minimap at its new size:', error);
            }
        }
    }

    /**
     * Shows the organisms of the loaded tick with their descent: the colouring, the organism
     * list, the descent section and the minimap overlay. The environment grid draws them once
     * its cells are there.
     *
     * @param {Array<object>} organisms - The organisms of the tick.
     * @param {object|null} descent - The `descent` object of the same answer.
     * @param {string} requestedRoot - The root as the request sent it.
     * @param {boolean} isForwardStep - True if navigating forward, for change highlighting.
     * @private
     */
    _showOrganisms(organisms, descent, requestedRoot, isForwardStep) {
        this._applyDescent(descent, requestedRoot);
        this.updateOrganismPanel(organisms, isForwardStep);
        this._renderDescentSection();
        this.minimapView?.setOwnershipColorResolver(this._minimapOwnershipColorResolver(organisms));
        this.minimapView?.updateOrganisms(organisms, this._minimapColorOf());
    }

    /**
     * Draws the descent section from the descent and the organisms of the shown tick.
     * @private
     */
    _renderDescentSection() {
        this.descentSection?.render({
            descent: this.state.descent,
            colouring: this.descentColouring,
            organisms: this.state.organisms,
            tick: this.state.currentTick,
            auto: this.state.root === 'auto'
        });
    }

    /**
     * Asks again for the organisms of the shown tick while the run's ancestry is still being read
     * or reading it failed, so that the progress, and then the colours, arrive by themselves. The
     * environment is not asked for. An answer is dropped when a load of a tick or a root has begun
     * since the question was sent; a failed question is left to the next poll.
     *
     * @returns {Promise<void>} A promise that resolves when the answer is shown or dropped.
     * @private
     */
    async _refreshPendingDescent() {
        const descentState = this.state.descent?.state;
        if (descentState !== 'loading' && descentState !== 'failed') {
            return;
        }
        if (!this.state.previousOrganisms || this.state.previousTick !== this.state.currentTick) {
            return; // The shown tick is still being loaded
        }

        const generation = this._loadGeneration;
        const tick = this.state.currentTick;
        const runId = this.state.runId;
        const requestedRoot = this._rootToken();
        try {
            // Silent: the descent section shows the progress, the loading indicator stays off
            const result = await this.organismApi.fetchOrganismsAtTick(tick, runId,
                { root: requestedRoot, showLoading: false });
            if (generation !== this._loadGeneration || tick !== this.state.currentTick
                || runId !== this.state.runId || requestedRoot !== this._rootToken()) {
                return;
            }
            this.state.totalOrganismCount = result.totalOrganismCount;
            this._showOrganisms(result.organisms, result.descent, requestedRoot, false);
            this.renderer.renderOrganisms(result.organisms);
            this.state.previousOrganisms = result.organisms;
        } catch (error) {
            console.debug('Failed to refresh the descent of the shown tick:', error);
        }
    }

    /**
     * Loads only the environment data for the current viewport, without re-fetching organisms.
     * This is used for performance optimization when panning the camera, as it reuses the
     * already-loaded organism data for the current tick to redraw markers.
     * @returns {Promise<void>}
     * @private
     */
    async loadEnvironmentForCurrentViewport() {
        // Skip if init hasn't completed yet — the resize observer can fire during init,
        // which would trigger a concurrent viewport load without organism data or minimap.
        if (!this.state.previousOrganisms) return;

        loadingManager.show('Fetching environment');
        try {
            dismissClosableNotice();
            await this.renderer.loadViewport(this.state.currentTick, this.state.runId);
            // Re-render organism markers for the new viewport using cached data
            loadingManager.update('Rendering organisms', 90);
            this.renderer.renderOrganisms(this.renderer.currentOrganisms || []);
            loadingManager.hide();
        } catch (error) {
            loadingManager.hide();
            // Ignore AbortError, as it's an expected cancellation
            if (error.name === 'AbortError') {
                // Request aborted by user navigation - expected
                return;
            }
            console.error('Failed to load environment for viewport:', error);
            showErrorNotice({ title: 'Could not load the environment', detail: error.message, closable: true });
        }
    }

    /**
     * Makes a new root of descent and loads the shown tick again against it.
     *
     * @param {number|string} root - An organism id or 'all' (also 0) for the virtual root above
     *     the founders, held from now on; anything else sets 'auto', the common ancestor of the
     *     organisms alive at every shown tick.
     * @returns {Promise<void>} A promise that resolves when the tick is loaded again.
     */
    async setRoot(root) {
        if (root === 0 || root === 'all') {
            this.state.root = 'all';
        } else if (Number.isInteger(root) && root > 0) {
            this.state.root = root;
        } else {
            this.state.root = 'auto';
        }
        this.updateUrlState();
        await this.loadViewport();
    }

    /**
     * Holds the root that 'auto' resolved for the shown tick: it stays the root when the tick
     * changes. The shown tick is not loaded again, its colours stay as they are. Does nothing
     * while the root is not auto or not resolved yet.
     */
    holdRoot() {
        const root = this.state.descent?.root;
        if (this.state.root !== 'auto' || !root) {
            return;
        }
        this.state.root = root.id === 0 ? 'all' : root.id;
        this.updateUrlState();
        this._renderDescentSection();
    }

    /**
     * Returns the root as a tick request sends it.
     * @returns {string} The organism id, 'all', or 'auto'.
     * @private
     */
    _rootToken() {
        return String(this.state.root);
    }

    /**
     * Tells whether a root, as sent, names an organism.
     * @param {string|null} token - The root as a tick request sent it.
     * @returns {boolean} True for an organism id, false for 'all', 'auto' or none.
     * @private
     */
    static _isOrganismRoot(token) {
        return typeof token === 'string' && /^[0-9]+$/.test(token);
    }

    /**
     * Returns the pair of the palette a root's lines start at.
     * <p>
     * A root shown before in this session keeps its pair, so that going back over a change of the
     * root shows the colours seen there before. A root shown for the first time takes the pair
     * after the one handed out last, and one further if that is the pair of the root shown just
     * now, so that a change of the root always shows in the colours.
     *
     * @param {number} rootId - Id of the root, 0 for `all`.
     * @returns {number} The pair, 0 to {@link LINE_PALETTE_PAIRS} - 1.
     * @private
     */
    _pairOfRoot(rootId) {
        const known = this._pairByRoot.get(rootId);
        if (known !== undefined) {
            return known;
        }
        let pair = this._pairByRoot.size === 0 ? 0 : (this._lastPairHandedOut + 1) % LINE_PALETTE_PAIRS;
        if (this._colouredRootId !== null && pair === this._palettePair) {
            pair = (pair + 1) % LINE_PALETTE_PAIRS;
        }
        this._pairByRoot.set(rootId, pair);
        this._lastPairHandedOut = pair;
        return pair;
    }

    /**
     * Takes over the descent of a loaded tick: builds the colouring and places the root's birth on
     * the timeline. While the root is 'auto', the resolved root is shown but not held. A load that
     * asked for 'auto' in place of a held root, after that root was not found, holds the root it
     * resolved; while the ancestry is still being read and nothing is resolved, the root becomes
     * 'auto'.
     *
     * @param {object|null} descent - The `descent` object of the answer.
     * @param {string} requestedRoot - The root as the request sent it.
     * @private
     */
    _applyDescent(descent, requestedRoot) {
        this.state.descent = descent;
        if (requestedRoot === 'auto' && this.state.root !== 'auto') {
            this.state.root = descent?.root ? (descent.root.id === 0 ? 'all' : descent.root.id) : 'auto';
            this.updateUrlState();
        }
        const rootId = descent?.root ? descent.root.id : null;
        if (rootId !== null && rootId !== this._colouredRootId) {
            this._palettePair = this._pairOfRoot(rootId);
            this._colouredRootId = rootId;
        }
        this.descentColouring = new DescentColouring(descent, this._palettePair);
        const root = descent?.root;
        this.tickPanelManager?.setRootBirthTick(root && root.id !== 0 ? (root.birthTick ?? null) : null);
    }

    /**
     * Forgets the root and the descent, as for a run that is opened anew: the root becomes 'auto'.
     * @private
     */
    _resetDescent() {
        this.state.root = 'auto';
        this.state.descent = null;
        this._rootNotice = null;
        this.descentColouring = new DescentColouring(null);
        this._palettePair = 0;
        this._colouredRootId = null;
        this._pairByRoot = new Map();
        this._lastPairHandedOut = 0;
        this.descentSection?.clear();
        this.tickPanelManager?.setRootBirthTick(null);
    }

    /**
     * Updates the organism panel with the list of organisms for the current tick.
     * It preserves the user's selection if the organism still exists and updates the summary counts.
     * 
     * @param {Array<object>} organisms - An array of organism summary objects for the current tick.
     * @param {boolean} [isForwardStep=false] - True if navigating forward.
     * @private
     */
    updateOrganismPanel(organisms, isForwardStep = false) {
        if (!Array.isArray(organisms)) {
            organisms = [];
        }
        
        // Store in state for reference
        this.state.organisms = organisms;
        
        // Calculate organism counts (exclude dead organisms from alive count)
        const aliveCount = organisms.filter(o => !o.isDead).length;
        const totalCount = this.state.totalOrganismCount;
        
        // Update panel info (alive/total display)
        this.organismPanelManager?.updateInfo(aliveCount, totalCount);
        
        // Build organism list data with all available info from summary
        const listData = organisms.map(organism => {
            if (!organism || typeof organism.organismId !== 'number') {
                return null;
            }
            return {
                id: String(organism.organismId),
                energy: organism.energy || 0,
                entropyRegister: organism.entropyRegister || 0,
                color: this.descentColouring.colourOf(organism.organismId, organism.isDead || false),
                ip: organism.ip,
                dv: organism.dv,
                dataPointers: organism.dataPointers,
                activeDpIndex: organism.activeDpIndex,
                parentId: organism.parentId,
                birthTick: organism.birthTick,
                genomeHash: organism.genomeHash,
                isDead: organism.isDead || false,
                deathTick: organism.deathTick
            };
        }).filter(Boolean);
        
        // Update organism list in panel
        this.organismPanelManager?.updateList(listData, this.state.selectedOrganismId);
    }
    
    /**
     * Loads the mutations of an organism's lineage once and hands their marks to the grid.
     *
     * The answer describes births, not ticks: it is the same at every tick of the run, so it is
     * fetched when the selection changes and kept until it changes again. The events themselves are
     * not kept — what outlives the call is the map of marked cells, which is handed to the grid
     * while the selected organism is alive at the shown tick. A mark is coloured by the genome
     * depth of the ancestor that received the mutation. A request that fails is not repeated for the same
     * organism either, so a run whose route answers with an error costs one request per selection.
     *
     * @param {number} organismId - The selected organism.
     * @returns {Promise<void>} A promise that resolves once the marks are handed over.
     * @private
     */
    async _ensureLineageMutations(organismId) {
        if (this._mutationsOrganismId === organismId) {
            return;
        }

        this._clearLineageMutations();
        this._mutationsOrganismId = organismId;
        this.organismMutationsRequestController = new AbortController();
        const signal = this.organismMutationsRequestController.signal;

        try {
            const answer = await this.organismApi.fetchOrganismMutations(
                this.state.currentTick,
                organismId,
                this.state.runId,
                { signal }
            );

            if (this.state.selectedOrganismId !== String(organismId)) {
                return; // The selection moved on while the answer was on its way
            }

            const events = answer?.events || [];
            this._lineageMarkMap = buildMarkMap(events, {
                resolveTypeName: (moleculeType) => this._resolveMoleculeTypeName(moleculeType)
            });
            this._showLineageMarks();
        } catch (error) {
            if (error.name === 'AbortError') {
                return;
            }
            console.warn(`Failed to load the mutations of organism ${organismId}:`, error.message);
            // Forget the organism so that selecting it again asks again, unless a newer request
            // has taken the place of this one in the meantime.
            if (this._mutationsOrganismId === organismId) {
                this._mutationsOrganismId = null;
            }
        }
    }

    /**
     * Drops the mutations of the lineage, the marks drawn from them and the generation distances
     * their tooltips name.
     * @private
     */
    _clearLineageMutations() {
        if (this.organismMutationsRequestController) {
            this.organismMutationsRequestController.abort();
            this.organismMutationsRequestController = null;
        }
        this._mutationsOrganismId = null;
        this._lineageDistances = null;
        this._genomeChain = null;
        this._lineageMarkMap = null;
        this._marksShown = null;
        this.renderer?.setMutationMarks(null);
    }

    /**
     * Hands the marks of the selected lineage to the grid, or takes them away while the selected
     * organism is dead at the shown tick. The grid is only told when that changes, since handing
     * the marks over draws every marked cell again.
     * @private
     */
    _showLineageMarks() {
        const selected = (this.state.organisms || [])
            .find(o => String(o.organismId) === this.state.selectedOrganismId);
        const marks = selected?.isDead ? null : this._lineageMarkMap;
        if (marks === this._marksShown) {
            return;
        }
        this._marksShown = marks;
        this.renderer?.setMutationMarks(marks, {
            colorOf: (mark) => this._markColour(mark),
            generationsBackOf: (originOrganismId) => this._generationsBack(originOrganismId),
            opcodeNameOf: (opcodeId) => this.state.metadata?.opcodes?.[String(opcodeId)] ?? null
        });
    }

    /**
     * Returns the colour of a mark: the genome depth of the ancestor that received the mutation.
     *
     * @param {object} mark - A mark of the selected lineage.
     * @returns {number|null} The colour as 0xRRGGBB; null while the depths of the organism the
     *     marks belong to are not known, or when the ancestor that received the mutation carries
     *     no genome change.
     * @private
     */
    _markColour(mark) {
        const chain = this._genomeChain;
        if (!chain || chain.organismId !== this._mutationsOrganismId) {
            return null;
        }
        const depth = chain.byOrganism.get(mark.originOrganismId);
        return depth === undefined ? null : depthColourInt(depth, chain.changes);
    }

    /**
     * Resolves a packed molecule type to the type id the grid holds in its cell data.
     *
     * A cell of the environment reaches the grid with its type as a name; a mutation reaches it as
     * the type constant at its place in the packed molecule. The run's metadata names those
     * constants, which is what joins the two.
     *
     * The name is the one the grid gives a cell of that type, so that a mark and the cell it sits
     * on compare by the same name - also for a type the palette does not know.
     *
     * @param {number} moleculeType - The molecule type as the mutations endpoint reports it.
     * @returns {string|null} The type name the grid displays for that type, null for a type this
     *     run does not name.
     * @private
     */
    _resolveMoleculeTypeName(moleculeType) {
        const names = this.state.metadata?.moleculeTypes;
        const name = names ? names[String(moleculeType)] : null;
        return name ? moleculeTypeName(name) : null;
    }

    /**
     * Tells how many generations back an organism of the selected lineage lies.
     *
     * The distances come with the organism details, whose ancestry chain is read the same way as
     * the lineage's mutations. They count only while they belong to the organism the marks were
     * built for.
     *
     * @param {number} organismId - An organism of the lineage, the selected one included.
     * @returns {number|null} Zero for the selected organism, one for its parent and so on; null
     *     while the details of the organism the marks belong to have not arrived.
     * @private
     */
    _generationsBack(organismId) {
        const lineage = this._lineageDistances;
        if (!lineage || lineage.organismId !== this._mutationsOrganismId) {
            return null;
        }
        return lineage.distances.get(organismId) ?? null;
    }

    /**
     * Returns the colour of an organism on the minimap organism overlay: its colour of descent,
     * the dead tone for an organism dead at the tick.
     * @returns {function(object): string} Hex colour of an organism
     * @private
     */
    _minimapColorOf() {
        const colouring = this.descentColouring;
        return (org) => colouring.colourOf(org.organismId, org.isDead || false);
    }

    /**
     * Returns a color resolver for minimap ownership mode.
     * Maps ownerId (int) to its colour of descent (0xRRGGBB int), the dead tone for an organism
     * dead at the tick. Only organisms of the tick are coloured; returns -1 for unknown owners
     * (renderer uses background color for -1).
     * @param {Array|null} [organisms=null] - Organisms to build the mapping from. Falls back to previousOrganisms.
     * @returns {function(number): number}
     * @private
     */
    _minimapOwnershipColorResolver(organisms = null) {
        const orgs = organisms || this.state.previousOrganisms || [];
        const colouring = this.descentColouring;
        const colours = new Map();
        for (const org of orgs) {
            colours.set(org.organismId, colouring.colourIntOf(org.organismId, org.isDead || false));
        }
        return (ownerId) => colours.get(ownerId) ?? -1;
    }

    /**
     * Loads the initial state (runId, tick, organism, root) from the URL query parameters on page load.
     * This allows for direct linking to a specific point in a specific simulation.
     * @private
     */
    loadFromUrl() {
        try {
            const urlParams = new URLSearchParams(window.location.search);
            
            const runId = urlParams.get('runId');
            if (runId !== null && runId.trim() !== '') {
                this.state.runId = runId.trim();
            }
            
            const tick = urlParams.get('tick');
            if (tick !== null) {
                const tickNumber = parseInt(tick, 10);
                if (!Number.isNaN(tickNumber) && tickNumber >= 0) {
                    this.state.currentTick = tickNumber;
                }
            }

            const organism = urlParams.get('organism');
            if (organism !== null) {
                const organismNumber = parseInt(organism, 10);
                if (!Number.isNaN(organismNumber) && organismNumber > 0) {
                    this.state.selectedOrganismId = String(organismNumber);
                }
            }

            // A root that is neither 'auto', 'all' nor an organism id is 'auto', as without one
            const root = urlParams.get('root');
            if (root !== null) {
                const trimmed = root.trim();
                if (trimmed === 'auto') {
                    this.state.root = 'auto';
                } else if (trimmed === 'all') {
                    this.state.root = 'all';
                } else if (AppController._isOrganismRoot(trimmed) && parseInt(trimmed, 10) > 0) {
                    this.state.root = parseInt(trimmed, 10);
                } else {
                    console.warn(`Ignoring the root '${root}' of the URL: neither 'auto', 'all' nor an organism id`);
                }
            }
        } catch (error) {
            console.debug('Failed to parse URL parameters for visualizer state:', error);
        }
    }

    /**
     * Ensures there is an initial runId if none is set: the starting run, otherwise the newest run
     * with data. Leaves it unset if there is neither.
     * @private
     */
    async ensureInitialRunId() {
        if (this.state.runId) return;
        const [runs, pipeline] = await Promise.all([
            fetch('/analyzer/api/runs')
                .then(response => response.ok ? response.json() : [])
                .catch(error => {
                    console.error('Failed to fetch runs:', error);
                    return [];
                }),
            fetchPipelineStatus()
        ]);
        this.state.runId = chooseInitialRunId(Array.isArray(runs) ? runs : [], pipeline);
    }

    /**
     * Updates the minimap viewport rectangle to show the current visible area.
     * Called on viewport changes (pan, zoom).
     * @private
     */
    updateMinimapViewport() {
        if (this.minimapView && this.renderer && this.state.worldShape) {
            const bounds = this.renderer.getViewportBounds();
            this.minimapView.updateViewport(bounds);
        }
    }
}
