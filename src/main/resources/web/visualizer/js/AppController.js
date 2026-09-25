import { EnvironmentApi, setTypeMappings } from './api/EnvironmentApi.js';
import { OrganismApi } from './api/OrganismApi.js';
import { SimulationApi } from './api/SimulationApi.js';
import { EnvironmentGrid } from './EnvironmentGrid.js';
import { buildMarkMap } from './MutationMarks.js';
import { moleculeTypeName } from './MoleculeTypePalette.js';
import { MinimapView } from './ui/minimap/MinimapView.js';
import { nearestLevelIndex, ZOOM_LEVELS } from './interaction/ZoomLevels.js';
import { CladeModel } from './CladeModel.js';
import { CladePanel } from './ui/CladePanel.js';
import { OrganismInstructionView } from './ui/organism/OrganismInstructionView.js';
import { OrganismSourceView } from './ui/organism/OrganismSourceView.js';
import { OrganismStateView } from './ui/organism/OrganismStateView.js';
import { OrganismPanelManager } from './ui/panels/OrganismPanelManager.js';
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
    /** Organism palette (hex) — keep in sync with ExactFrameRenderer and MinimapFrameRenderer. */

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
        };
        this.programArtifactCache = new Map(); // Cache for program artifacts
        // Lineage-based color tracking (genome mode)
        /** The clade tree of the current run, null until it has been fetched. */
        this._cladeModel = null;
        /** The last sampled tick the tree was built from; the same answer needs no new tree. */
        this._cladeSamplesAt = null;
        this._cladeRequestController = null;

        this._genomeParent = new Map();       // String(genomeHash) → String(parentGenomeHash) | null
        this._genomeColorCache = new Map();   // String(genomeHash) → int 0xRRGGBB
        // The organism the mutations of the selected lineage were fetched for
        this._mutationsOrganismId = null;     // int | null
        // How many generations each ancestor lies back from the organism whose details were loaded
        this._lineageDistances = null;        // { organismId: int, distances: Map<int, int> } | null

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

        // Load initial state (runId, tick) from URL if present
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
            this._genomeParent.clear();
            this._genomeColorCache.clear();
            this._cladeRequestController?.abort();
            this._cladeModel = null;
            this._cladeSamplesAt = null;
            this.cladePanel?.setModel(null);
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
            this._loadClades();

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
            onGenomeClick: (label) => this.cladePanel?.enterByLabel(label),
            onPositionClick: (x, y) => this.renderer?.centerOn(x, y),
            onTickClick: (tick) => this.navigateToTick(tick)
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

            // The ancestry chain is coloured by genome, and its ancestors need not appear at the
            // current tick, so their parent links arrive with this response and are added before
            // anything is drawn.
            this._mergeGenomeAncestors(details.genomeAncestors);
            
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
                // The marks were drawn before this arrived and are all in the youngest colour
                this.renderer?.refreshMutationMarks();

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
                        ? `<span class="clickable-tick" data-tick="${birthTick}">${birthTick}</span>`
                        : '-';

                    // Show Birth/Death when dead, otherwise just Birth
                    let birthDeathLabel;
                    if (isDead && deathTick != null && deathTick >= 0) {
                        const deathDisplay = `<span class="clickable-tick" data-tick="${deathTick}">${deathTick}</span>`;
                        birthDeathLabel = `Birth/Death: ${birthDisplay}/${deathDisplay}`;
                    } else {
                        birthDeathLabel = `Birth: ${birthDisplay}`;
                    }

                    // Build lineage display (direct parent first, oldest ancestor last)
                    const lineageDisplay = this._buildLineageDisplay(details.lineage || [], organismId, isDead);

                    infoEl.innerHTML = `<div class="organism-info-line">${birthDeathLabel}  MR: ${mrValue}  Lineage: <span class="lineage-chain">${lineageDisplay}</span></div>`;

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
                    infoEl.querySelectorAll('.lineage-ancestor').forEach(el => {
                        el.addEventListener('click', (e) => {
                            e.stopPropagation();
                            const aId = el.dataset.organismId;
                            if (aId) {
                                this.selectOrganism(aId);
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
                }
            );
            this.minimapView.restoreState(); // Restore expanded/collapsed state
            this.minimapView.setTorus(this.renderer.torus);
            this.minimapView.updateZoomButton(this.renderer.getCurrentCellSize());
            this.minimapView.setOwnershipColorResolver(this._minimapOwnershipColorResolver());

            this.cladePanel = new CladePanel({
                getRanges: () => this.state.ranges,
                getCurrentTick: () => this.state.currentTick,
                getOrganisms: () => this.state.previousOrganisms,
                onLevelChanged: () => this.repaintOrganismColors()
            });

            // Abort previous request if it's still running
            this.waitingOverlay.cancel();
            if (this.simulationRequestController) {
                this.simulationRequestController.abort();
            }
            this.simulationRequestController = new AbortController();
            const signal = this.simulationRequestController.signal;

            await this._loadRun(signal, (label, percent) => loadingManager.update(label, percent));

            // The clade tree is fetched while the first tick is still being drawn: nobody waits
            // for it, and by the time the switch beside the timeline is used it is usually there
            this._loadClades();

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
     * Fetches the clade tree of the current run and hands it to the panel.
     * <p>
     * Nobody waits for this: it is started when a run is opened and again, while the panel is
     * open, whenever the run may have grown past the next step of the sample grid. The endpoint
     * answers "not modified" until it has, so asking costs a validation and no transfer.
     *
     * @returns {Promise<void>} Resolves once the tree has arrived or the attempt has failed.
     * @private
     */
    async _loadClades() {
        const runId = this.state.runId;
        this._cladeRequestController?.abort();
        this._cladeRequestController = new AbortController();
        const signal = this._cladeRequestController.signal;

        try {
            const answer = await this.organismApi.fetchClades(runId, { signal });
            if (runId !== this.state.runId) {
                return;
            }
            // The samples move once in many minutes while this is asked every few seconds, and
            // the answer is the same until they do. Rebuilding it regardless would throw away the
            // clade the viewer entered, and pay for the shares of a level that has not changed.
            const samples = answer?.samples ?? [];
            const lastSample = samples.length ? samples[samples.length - 1].tick : null;
            if (this._cladeModel && lastSample === this._cladeSamplesAt) {
                return;
            }
            this._cladeSamplesAt = lastSample;

            // A genome that arose between two sampled ticks is not in the tree; its ancestry is
            // in the map the organism responses fill, which covers the tick on screen
            const model = new CladeModel(answer, (genome) => this._genomeParent.get(genome));
            model.followPath(this._cladeModel?.path);
            this._cladeModel = model;
            this.cladePanel?.setModel(model);
            this.repaintOrganismColors();
        } catch (error) {
            if (error.name === 'AbortError') {
                return;
            }
            console.error(`Could not load the clades of run ${runId}:`, error);
            showErrorNotice({
                title: 'Could not load the clades of this run',
                detail: error.message,
                closable: true
            });
        }
    }

    /**
     * Fetches the clade tree again if the run may have grown past the next sample.
     * @private
     */
    _refreshCladesIfShown() {
        if (this._cladeModel) {
            this._loadClades();
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
                this._refreshCladesIfShown();
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
     * Starts periodic polling for maxTick updates (every 5 seconds).
     * Stops any existing polling first.
     * @private
     */
    _startMaxTickPolling() {
        this._stopMaxTickPolling();
        this._maxTickPollTimer = setInterval(() => this.updateMaxTick(), 5000);
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
        // The chart carries the same mark of the current tick as the track below it, and the
        // strip shows what each step of the path carries there
        this.cladePanel?.refresh();
        this._refreshStepInfo();

        // Update URL state
        this.updateUrlState();

        // Load environment and organisms for new tick
        await this.loadViewport(isForwardStep, previousTick);
    }
    
    /**
     * Updates the browser URL with the current application state (runId, tick).
     * This enables deep linking and state persistence across page reloads.
     * @private
     */
    updateUrlState() {
        try {
            const url = new URL(window.location.href);

            // Rebuild params in desired order: tick, organism, runId
            url.searchParams.delete('tick');
            url.searchParams.delete('organism');
            url.searchParams.delete('runId');

            if (this.state.currentTick !== null && this.state.currentTick !== undefined) {
                url.searchParams.set('tick', this.state.currentTick);
            }
            if (this.state.selectedOrganismId) {
                url.searchParams.set('organism', this.state.selectedOrganismId);
            }
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
     * @returns {Promise<void>} A promise that resolves when the viewport data is loaded.
     * @private
     */
    async loadViewport(isForwardStep = false, previousTick = null) {
        // Abort previous organism summary request
        if (this.organismSummaryRequestController) {
            this.organismSummaryRequestController.abort();
        }
        this.organismSummaryRequestController = new AbortController();
        const organismSignal = this.organismSummaryRequestController.signal;

        // Track load generation so aborted loads can clean up correctly
        this._loadGeneration = (this._loadGeneration || 0) + 1;
        const myGeneration = this._loadGeneration;

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
            const environmentPromise = this.renderer.loadViewport(
                this.state.currentTick,
                this.state.runId,
                needMinimap
            );
            // The environment is awaited only after the organisms. When the organisms fail or a
            // newer load aborts this one first, the environment is never awaited; its failure is
            // then no news, and the browser must not report it as unhandled. Awaiting it below
            // still throws as before.
            environmentPromise.catch(() => {});
            const organismPromise = this.organismApi.fetchOrganismsAtTick(
                this.state.currentTick,
                this.state.runId,
                { signal: organismSignal }
            );

            // Process organisms as soon as they arrive (don't wait for environment)
            const organismResult = await organismPromise;
            const organisms = organismResult.organisms;
            this.state.totalOrganismCount = organismResult.totalOrganismCount;
            this._applyGenomeAncestors(organismResult.genomeAncestors);
            this.updateOrganismPanel(organisms, isForwardStep);
            this.minimapView?.setOwnershipColorResolver(this._minimapOwnershipColorResolver(organisms));
            this.minimapView?.updateOrganisms(
                organisms,
                this._minimapColorResolver(),
                this._minimapGroupKeyFn()
            );

            // Reload organism details if one is selected
            if (this.state.selectedOrganismId) {
                const organismId = parseInt(this.state.selectedOrganismId, 10);
                if (!isNaN(organismId)) {
                    const stillExists = organisms.some(o => String(o.organismId) === this.state.selectedOrganismId);
                    if (stillExists) {
                        await this._ensureLineageMutations(organismId);
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
            }
            this.updateMinimapViewport();
            this.renderer.renderOrganisms(organisms);

            // Save current organisms for next comparison
            this.state.previousOrganisms = organisms;
            this.state.previousTick = this.state.currentTick;

            if (!managedExternally) {
                loadingManager.hide();
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
            console.error('Failed to load viewport:', error);
            showErrorNotice({ title: 'Could not load this tick', detail: error.message, closable: true });
            // Update panel with empty list on error
            this.updateOrganismPanel([]);
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
                color: this.getOrganismColor(organism.organismId, organism.genomeHash),
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
     * Gets a deterministic color for an organism based on its ID.
     * Returns a hex color string suitable for CSS.
     *
     * @param {number} organismId - The ID of the organism.
     * @param {number} genomeHash - The genome hash for genome-based coloring.
     * @returns {string} A hex color string (e.g., "#32cd32").
     * @private
     */
    getOrganismColor(organismId, genomeHash) {
        if (typeof organismId !== 'number' || organismId < 1) {
            return '#ffffff'; // Default white for invalid IDs
        }

        return this._genomeHashToLineageHex(genomeHash);
    }

    /**
     * Loads the mutations of an organism's lineage once and hands their marks to the grid.
     *
     * The answer describes births, not ticks: it is the same at every tick of the run, so it is
     * fetched when the selection changes and kept until it changes again. The events themselves are
     * not kept — what outlives the call is the map of marked cells the grid holds and the ancestry
     * edges of the genomes the events name. A request that fails is not repeated for the same
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
            this.renderer?.setMutationMarks(
                buildMarkMap(events, {
                    resolveTypeName: (moleculeType) => this._resolveMoleculeTypeName(moleculeType)
                }),
                {
                    colorOf: () => this._selectedOrganismColor(),
                    generationsBackOf: (originOrganismId) => this._generationsBack(originOrganismId),
                    opcodeNameOf: (opcodeId) => this.state.metadata?.opcodes?.[String(opcodeId)] ?? null
                }
            );
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
        this.renderer?.setMutationMarks(null);
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
     * The colour the selected organism is drawn in, whose hue its mutation marks take.
     *
     * @returns {number|null} A packed RGB integer, null while nothing is selected or the organism
     *     is not among those of the tick shown.
     * @private
     */
    _selectedOrganismColor() {
        const selected = this.state.selectedOrganismId;
        if (!selected) {
            return null;
        }
        const organism = (this.state.previousOrganisms || [])
            .find(candidate => String(candidate.organismId) === String(selected));
        return organism ? this._genomeHashToLineageColor(organism.genomeHash) : null;
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
     * Applies the genome ancestor closure from a backend API response.
     * Replaces the genome→parentGenome map and clears derived color caches.
     * <p>
     * The map is replaced rather than merged: while a run is still being indexed, a genome whose
     * parent organism has not been written yet reads as a root, and that answer corrects itself
     * once the missing rows arrive. Keeping earlier answers would make such a colour last for the
     * whole session.
     * @param {Object} ancestors - Map of genomeHash → parentGenomeHash (null for roots), covering
     *                             every genome the response displays and all of their ancestors.
     * @private
     */
    _applyGenomeAncestors(ancestors) {
        this._genomeParent.clear();
        this._genomeColorCache.clear();
        this._mergeGenomeAncestors(ancestors);
    }

    /**
     * Adds a genome ancestor closure to the current one without discarding it.
     * <p>
     * A view is served by more than one response: the organisms of a tick and, when one is
     * selected, its ancestry chain. Their closures overlap but neither contains the other, and
     * both come from the same relation, so a genome present in both carries the same parent.
     * Adding is therefore safe, while replacing would drop what the other response delivered.
     * @param {Object} ancestors - Map of genomeHash → parentGenomeHash (null for roots).
     * @private
     */
    _mergeGenomeAncestors(ancestors) {
        for (const [genomeHash, parentGenomeHash] of Object.entries(ancestors)) {
            this._genomeParent.set(String(genomeHash), parentGenomeHash ? String(parentGenomeHash) : null);
        }
    }

    /**
     * Returns a lineage-derived color for a genome hash as a packed RGB integer.
     * Root genomes get hues from a golden-ratio sequence. Derived genomes get a hue
     * shifted from their parent's, creating visual continuity along lineages.
     * @param {number|bigint|string} genomeHash - The genome hash value.
     * @returns {number} Packed RGB integer (0xRRGGBB), or 0x808080 for null/zero.
     * @private
     */
    _genomeHashToLineageColor(genomeHash) {
        if (genomeHash == null || genomeHash === 0 || genomeHash === '0') return 0x808080;
        if (!this._cladeModel) {
            // Until the tree is there no genome has a clade; the tone outside the opened one says
            // that as well as anything, and nothing pretends to a kinship it cannot know
            return CladeModel.OUTSIDE;
        }
        const key = String(genomeHash);
        if (!this._genomeColorCache.has(key)) {
            // Placing a genome walks its ancestry; every organism of every frame would walk it
            // again without this. The cache is cleared when the level or the tick changes.
            this._genomeColorCache.set(key, this._cladeModel.colourOf(key));
        }
        return this._genomeColorCache.get(key);
    }

    /**
     * Returns a lineage-derived color as a CSS hex string (e.g., '#1e90ff').
     * @param {number|bigint|string} genomeHash - The genome hash value.
     * @returns {string} Hex color string.
     * @private
     */
    _genomeHashToLineageHex(genomeHash) {
        const rgb = this._genomeHashToLineageColor(genomeHash);
        return '#' + rgb.toString(16).padStart(6, '0');
    }

    /**
     * Builds the HTML for the lineage chain display.
     * Direct parent first (left), oldest ancestor last (right). Truncated with (+N) if too long.
     * @param {Array} lineage - Array of {organismId, genomeHash} entries (parent first).
     * @param {string|number} currentOrganismId - The currently selected organism's ID.
     * @returns {string} HTML string for the lineage chain.
     * @private
     */
    _buildLineageDisplay(lineage, currentOrganismId, isDead = false) {
        if (!lineage || lineage.length === 0) {
            return '<span class="lineage-none">-</span>';
        }

        const maxVisible = isDead ? 5 : 6;
        const aliveIds = new Set(
            (this.organismPanelManager?.currentOrganisms || []).map(o => String(o.id))
        );

        // Truncate: show first maxVisible-1 entries, then (+N) for the rest
        let displayEntries = lineage;
        let overflowCount = 0;
        if (lineage.length > maxVisible) {
            displayEntries = lineage.slice(0, maxVisible - 1);
            overflowCount = lineage.length - (maxVisible - 1);
        }

        const parts = displayEntries.map(entry => {
            const id = entry.organismId;
            const isAlive = aliveIds.has(String(id));

            const color = this._genomeHashToLineageHex(entry.genomeHash);

            if (isAlive) {
                return `<span class="lineage-ancestor" data-organism-id="${id}" style="color:${color}">#${id}</span>`;
            } else {
                return `<span class="lineage-ancestor-dead" style="color:${color}">#${id}</span>`;
            }
        });

        let html = parts.join('<span class="lineage-separator"> &gt; </span>');
        if (overflowCount > 0) {
            html += `<span class="lineage-separator"> &gt; </span><span class="lineage-overflow">(+${overflowCount})</span>`;
        }
        return html;
    }

    /**
     * Draws the organisms again with the colours of the current level, everywhere they appear.
     * No server request is involved: what changes is how a genome is turned into a colour.
     */
    repaintOrganismColors() {
        this._genomeColorCache.clear();
        const organisms = this.state.previousOrganisms;
        if (!organisms) {
            return;
        }
        this.updateOrganismPanel(organisms);
        this.renderer.renderOrganisms(organisms);
        // The marks take the hue of the selected organism, which a change of level changes
        this.renderer.refreshMutationMarks();
        this.minimapView?.organismOverlay?.clearSpriteCache();
        this.minimapView?.setOwnershipColorResolver(this._minimapOwnershipColorResolver());
        this.minimapView?.updateOrganisms(
            organisms,
            this._minimapColorResolver(),
            this._minimapGroupKeyFn()
        );
    }

    /**
     * Returns a color resolver for the minimap organism overlay based on the current color mode.
     * @returns {function(string): string} Maps group key to hex color
     * @private
     */
    _minimapColorResolver() {
        return (genomeHash) => this._genomeHashToLineageHex(genomeHash);
    }

    /**
     * Returns a grouping key function for the minimap organism overlay based on the current color mode.
     * @returns {function(object): string} Extracts the grouping key from an organism
     * @private
     */
    _minimapGroupKeyFn() {
        return (org) => String(org.genomeHash || 0);
    }

    /**
     * Returns a color resolver for minimap ownership mode.
     * Maps ownerId (int) to 0xRRGGBB (int) based on current color mode.
     * Only colors cells belonging to living organisms; returns -1 for unknown owners
     * (renderer uses background color for -1).
     * @param {Array|null} [organisms=null] - Organisms to build the mapping from. Falls back to previousOrganisms.
     * @returns {function(number): number}
     * @private
     */
    _minimapOwnershipColorResolver(organisms = null) {
        const orgs = organisms || this.state.previousOrganisms;
        const ownerToGenome = new Map();
        if (orgs) {
            for (const org of orgs) {
                ownerToGenome.set(org.organismId, org.genomeHash);
            }
        }
        return (ownerId) => {
            const genomeHash = ownerToGenome.get(ownerId);
            if (genomeHash != null) {
                return this._genomeHashToLineageColor(genomeHash);
            }
            return -1;
        };
    }

    /**
     * Loads the initial state (runId, tick) from the URL query parameters on page load.
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
