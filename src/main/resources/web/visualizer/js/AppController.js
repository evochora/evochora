import { EnvironmentApi, setTypeMappings } from './api/EnvironmentApi.js';
import { OrganismApi } from './api/OrganismApi.js';
import { SimulationApi } from './api/SimulationApi.js';
import { EnvironmentGrid } from './EnvironmentGrid.js';
import { MinimapView } from './ui/minimap/MinimapView.js';
import { OrganismInstructionView } from './ui/organism/OrganismInstructionView.js';
import { OrganismSourceView } from './ui/organism/OrganismSourceView.js';
import { OrganismStateView } from './ui/organism/OrganismStateView.js';
import { OrganismPanelManager } from './ui/panels/OrganismPanelManager.js';
import { TickPanelManager } from './ui/panels/TickPanelManager.js';
import { loadingManager } from './ui/LoadingManager.js';
import { WaitingOverlay } from './ui/WaitingOverlay.js';

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
    static ORGANISM_PALETTE = ['#32cd32', '#1e90ff', '#dc143c', '#ffd700', '#ffa500', '#9370db', '#00ffff'];

    /**
     * Initializes the AppController, creating instances of all APIs, views, and
     * setting up the initial state and event listeners.
     */
    constructor() {
        // APIs
        this.simulationApi = new SimulationApi();
        this.environmentApi = new EnvironmentApi();
        this.organismApi = new OrganismApi();

        this.waitingOverlay = new WaitingOverlay({
            environmentApi: this.environmentApi,
            organismApi: this.organismApi
        });

        // Request controllers for cancellation
        this.simulationRequestController = null;
        this.organismSummaryRequestController = null;
        this.organismDetailsRequestController = null;
        
        // State
        const storedZoom = localStorage.getItem('evochora-zoom-state');
        const initialZoom = storedZoom !== null ? storedZoom === 'true' : true; // Default zoomed out
        
        this.state = {
            currentTick: 0,
            maxTick: null,
            worldShape: null,
            runId: null,
            selectedOrganismId: null, // Track selected organism across tick changes
            previousTick: null, // For change detection
            previousOrganisms: null, // For change detection
            previousOrganismDetails: null, // For change detection in details
            isZoomedOut: initialZoom, // Zoom state (persisted)
            organisms: [], // Current organisms for the tick
            metadata: null, // Simulation metadata (includes organism config)
            colorMode: localStorage.getItem('evochora-color-mode') || 'id', // 'id' or 'genome'
        };
        this.programArtifactCache = new Map(); // Cache for program artifacts
        // Lineage-based color tracking (genome mode)
        this._genomeParent = new Map();       // String(genomeHash) → String(parentGenomeHash) | null
        this._genomeColorCache = new Map();   // String(genomeHash) → int 0xRRGGBB
        this._genomeHslCache = new Map();     // String(genomeHash) → [h, s, l]
        
        // Config for renderer
        const defaultConfig = {
            worldSize: [100, 30],
            cellSize: 22,
            typeCode: 0,
            typeData: 1,
            typeEnergy: 2,
            typeStructure: 3,
            typeLabel: 4,
            typeLabelRef: 5,
            typeRegister: 6,
            backgroundColor: '#1a1a28', // Border area visible when scrolling beyond grid
            colorEmptyBg: '#14141e',
            colorCodeBg: '#3c5078',
            colorDataBg: '#32323c',
            colorStructureBg: '#ff7878',
            colorEnergyBg: '#ffe664',
            colorLabelBg: '#a0a0a8', // Light gray for jump target labels
            colorLabelRefBg: '#a0a0a8', // Same background as LABEL
            colorRegisterBg: '#506080', // Medium blue-gray for register references
            colorCodeText: '#ffffff',
            colorDataText: '#ffffff',
            colorStructureText: '#323232',
            colorEnergyText: '#323232',
            colorLabelText: '#323232', // Dark text on light background
            colorLabelRefText: '#ffffff', // Light text to distinguish from LABEL
            colorRegisterText: '#ffffff',
            colorText: '#ffffff',
            organismPalette: AppController.ORGANISM_PALETTE.map(hex => parseInt(hex.slice(1), 16))
        };
        
        // Components
        this.worldContainer = document.querySelector('.world-container');
        this.renderer = new EnvironmentGrid(this, this.worldContainer, defaultConfig, this.environmentApi);

        // Minimap is initialized in init() after renderer.init() completes
        // (renderer.init() clears the container, so we must add minimap after)
        this.minimapView = null;
        this.lastMinimapTick = null;

        // Apply initial zoom state (persisted from localStorage, default zoomed out)
        this.renderer.setZoom(this.state.isZoomedOut);

        // Initialize panel managers
        this.initPanelManagers();

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

        // Keep run selector display in sync when state changes externally
        window.addEventListener('tickChanged', () => window.runSelectorPanel?.updateCurrent?.());
    }
    
    /**
     * Changes the active run and reloads all dependent data.
     * @param {string} runId - The run identifier to load.
     */
    async changeRun(runId) {
        const trimmed = runId ? runId.trim() : null;
        if (!trimmed || trimmed === this.state.runId) {
            return;
        }

        try {
            hideError();

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
            this.state.previousTick = null;
            this.state.previousOrganisms = null;
            this.state.previousOrganismDetails = null;
            this._genomeParent.clear();
            this._genomeColorCache.clear();
            this._genomeHslCache.clear();
            this.minimapView?.organismOverlay?.clearSpriteCache();
            this.state.maxTick = null;
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

            // Fetch metadata for new run
            const metadata = await this.simulationApi.fetchMetadata(this.state.runId);
            this.state.metadata = metadata; // Store metadata for use by components
            
            // Set type mappings for Protobuf ID resolution in EnvironmentApi
            setTypeMappings(metadata);

            // Update UI components that depend on metadata
            this.tickPanelManager?.updateSamplingInfo(metadata?.samplingInterval || 1);
            this.tickPanelManager?.loadMultiplierForRun(this.state.runId); // Load after metadata is ready
            this.tickPanelManager?.updateTooltips();

            if (metadata?.environment?.shape) {
                this.state.worldShape = Array.from(metadata.environment.shape);
                this.renderer.updateWorldShape(this.state.worldShape);
            }
            if (Array.isArray(metadata?.programs)) {
                // Attach environment info to each artifact for toroidal coordinate calculations
                const envInfo = metadata?.environment;
                for (const program of metadata.programs) {
                    if (program && program.programId && program.sources) {
                        if (envInfo) {
                            // Derive per-dimension toroidal flags from topology string.
                            // Config uses topology="TORUS" (string), not a toroidal array.
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
            if (this.organismPanelManager) {
                this.organismPanelManager.setMetadata(metadata);
            }

            // Fetch tick ranges from both environment and organism APIs
            const [envTickRange, orgTickRange] = await Promise.all([
                this.environmentApi.fetchTickRange(this.state.runId).catch(() => null),
                this.organismApi.fetchTickRange(this.state.runId).catch(() => null)
            ]);
            
            // Use minimum of both maxTicks
            if (envTickRange?.maxTick !== undefined && orgTickRange?.maxTick !== undefined) {
                this.state.maxTick = Math.min(envTickRange.maxTick, orgTickRange.maxTick);
            } else if (envTickRange?.maxTick !== undefined) {
                this.state.maxTick = envTickRange.maxTick;
            } else if (orgTickRange?.maxTick !== undefined) {
                this.state.maxTick = orgTickRange.maxTick;
            }
            this.tickPanelManager.updateTickDisplay(this.state.currentTick, this.state.maxTick);

            // Load initial tick for new run
            await this.navigateToTick(this.state.currentTick, true);
            window.runSelectorPanel?.updateCurrent?.();
            this._startMaxTickPolling();
        } catch (error) {
            console.error('Failed to change run:', error);
            showError('Failed to change run: ' + error.message);
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
            // An organism is selected - load its details
            await this.loadOrganismDetails(numericId);
        } else {
            // No organism is selected (deselection) - clear details
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
     * Toggles the zoom state of the environment grid and forces a full refresh.
     */
    async toggleZoom() {
        await this.setZoom(!this.state.isZoomedOut);
    }

    /**
     * Sets the zoom state, optionally with a specific scale.
     * @param {boolean} isZoomedOut - True for overview, false for detailed view
     * @param {number|null} [scale=null] - Optional scale to set when switching to zoomed-out
     */
    async setZoom(isZoomedOut, scale = null) {
        // If switching to zoomed-out with a specific scale, set it first (without navigation)
        if (isZoomedOut && scale !== null) {
            this.renderer.zoomOutScale = Math.max(1, Math.min(10, Math.round(scale)));
            localStorage.setItem('evochora-zoom-out-scale', String(this.renderer.zoomOutScale));
        }

        if (this.state.isZoomedOut === isZoomedOut) return;

        this.state.isZoomedOut = isZoomedOut;

        // Persist zoom state
        localStorage.setItem('evochora-zoom-state', isZoomedOut ? 'true' : 'false');

        // Update minimap panel zoom select
        this.minimapView?.updateZoomButton(isZoomedOut, this.renderer.getZoomOutScale());

        // Tell the renderer to update its internal state
        this.renderer.setZoom(isZoomedOut);

        // Force a full re-render of the current tick
        await this.navigateToTick(this.state.currentTick, true);
    }

    /**
     * Sets the zoom-out scale (pixels per cell in zoomed-out mode).
     * @param {number} scale - The new scale (1-4).
     */
    async setZoomOutScale(scale) {
        this.renderer.setZoomOutScale(scale);
        this.minimapView?.updateZoomButton(this.state.isZoomedOut, scale);

        // Force a full re-render of the current tick
        await this.navigateToTick(this.state.currentTick, true);
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

        // Color mode toggle (ID vs Genome Hash)
        const colorModeToggle = document.getElementById('color-mode-toggle');
        if (colorModeToggle) {
            colorModeToggle.addEventListener('click', () => this.toggleColorMode());
            this._updateColorModeButton();
        }
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
            hideError();
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
                    const lineageDisplay = this._buildLineageDisplay(staticInfo.lineage || [], organismId, isDead);

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
                
                // Update State View Artifact (Clean Architecture)
                const programIdForState = staticInfo.programId;
                if (programIdForState) {
                    // 1. Resolve Artifact (Controller responsibility)
                    let artifactForState = this.programArtifactCache.get(programIdForState) || null;

                    // 2. Set Context (View decides if update needed)
                    this.stateView.setProgram(artifactForState);
                    this.instructionView.setProgram(artifactForState);
                } else {
                    this.stateView.setProgram(null);
                    this.instructionView.setProgram(null);
                }

                // Update Source View (Clean Architecture)
                const programId = staticInfo.programId;
                if (programId) {
                    // 1. Resolve Artifact (Controller responsibility)
                    let artifact = this.programArtifactCache.get(programId) || null;
                    
                    // 2. Set Context (View decides if update needed)
                    this.sourceView.setProgram(artifact);
                    
                    // 3. Update Dynamic State (Fast update)
                    this.sourceView.updateExecutionState(state, staticInfo);
                } else {
                    this.sourceView.setProgram(null);
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
            showError('Failed to load organism details: ' + error.message);
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
            hideError();

            // Ensure we have an initial runId (latest if none provided)
            await this.ensureInitialRunId();

            // Initialize renderer
            this._initInProgress = true;
            loadingManager.show('Initializing renderer');
            await this.renderer.init();

            // Restore zoom-out scale from localStorage
            const savedScale = localStorage.getItem('evochora-zoom-out-scale');
            if (savedScale) {
                this.renderer.zoomOutScale = parseInt(savedScale, 10) || 1;
            }

            // Create minimap panel (positioned fixed, appended to body)
            this.minimapView = new MinimapView(
                (worldX, worldY) => {
                    this.renderer.centerOn(worldX, worldY);
                },
                (isZoomedOut, scale) => {
                    this.setZoom(isZoomedOut, scale);
                },
                (scale) => {
                    this.setZoomOutScale(scale);
                }
            );
            this.minimapView.restoreState(); // Restore expanded/collapsed state
            this.minimapView.updateZoomButton(this.state.isZoomedOut, this.renderer.getZoomOutScale());
            this.minimapView.setOwnershipColorResolver(this._minimapOwnershipColorResolver());

            // Abort previous request if it's still running
            this.waitingOverlay.cancel();
            if (this.simulationRequestController) {
                this.simulationRequestController.abort();
            }
            this.simulationRequestController = new AbortController();
            const signal = this.simulationRequestController.signal;

            // Load metadata for world shape
            loadingManager.update('Loading metadata', 15);
            const metadata = await this.simulationApi.fetchMetadata(this.state.runId, { signal });
            if (metadata) {
                this.state.metadata = metadata; // Store metadata for use by components
                
                // Set type mappings for Protobuf ID resolution in EnvironmentApi
                setTypeMappings(metadata);

                // Update sampling info in the UI
                this.tickPanelManager?.updateSamplingInfo(metadata?.samplingInterval || 1);
                this.tickPanelManager?.loadMultiplierForRun(this.state.runId);
                this.tickPanelManager?.updateTooltips();
                
                if (metadata.runId && !this.state.runId) {
                    this.state.runId = metadata.runId;
                }
                if (metadata.environment && metadata.environment.shape) {
                    this.state.worldShape = Array.from(metadata.environment.shape);
                    // Wait a bit before updating world shape to ensure devicePixelRatio is stable
                    // This helps with monitor-specific initialization issues
                    await new Promise(resolve => requestAnimationFrame(resolve));
                    this.renderer.updateWorldShape(this.state.worldShape);
                }

                // Cache program artifacts with environment info for toroidal calculations
                if (Array.isArray(metadata.programs)) {
                    const envInfo = metadata?.environment;
                    for (const program of metadata.programs) {
                        if (program && program.programId && program.sources) {
                            if (envInfo) {
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
                if (this.organismPanelManager) {
                    this.organismPanelManager.setMetadata(metadata);
                }
            }
            
            // Load tick range for maxTick (minimum of environment and organism ranges)
            loadingManager.update('Loading tick range', 30);
            const [envTickRange, orgTickRange] = await Promise.all([
                this.environmentApi.fetchTickRange(this.state.runId).catch(() => null),
                this.organismApi.fetchTickRange(this.state.runId).catch(() => null)
            ]);
            
            if (envTickRange?.maxTick !== undefined && orgTickRange?.maxTick !== undefined) {
                this.state.maxTick = Math.min(envTickRange.maxTick, orgTickRange.maxTick);
            } else if (envTickRange?.maxTick !== undefined) {
                this.state.maxTick = envTickRange.maxTick;
            } else if (orgTickRange?.maxTick !== undefined) {
                this.state.maxTick = orgTickRange.maxTick;
            }
            this.tickPanelManager.updateTickDisplay(this.state.currentTick, this.state.maxTick);

            // If no tick data is available yet, wait for the simulation to produce data
            if (this.state.maxTick === null) {
                loadingManager.hide();
                this.state.maxTick = await this.waitingOverlay.waitForData(this.state.runId);
                this.tickPanelManager.updateTickDisplay(this.state.currentTick, this.state.maxTick);
                loadingManager.show('Loading');
            }

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
            console.error('Failed to initialize application:', error);
            showError('Failed to initialize: ' + error.message);
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
            // Fetch both environment and organism tick ranges in parallel
            const [envTickRange, orgTickRange] = await Promise.all([
                this.environmentApi.fetchTickRange(this.state.runId).catch(() => null),
                this.organismApi.fetchTickRange(this.state.runId).catch(() => null)
            ]);
            
            // Calculate effective maxTick as the minimum of both (where available)
            let newMaxTick = null;
            if (envTickRange?.maxTick !== undefined && orgTickRange?.maxTick !== undefined) {
                newMaxTick = Math.min(envTickRange.maxTick, orgTickRange.maxTick);
            } else if (envTickRange?.maxTick !== undefined) {
                newMaxTick = envTickRange.maxTick;
            } else if (orgTickRange?.maxTick !== undefined) {
                newMaxTick = orgTickRange.maxTick;
            }
            
            if (newMaxTick !== null && newMaxTick !== this.state.maxTick) {
                this.state.maxTick = newMaxTick;
                this.tickPanelManager.updateTickDisplay(this.state.currentTick, this.state.maxTick);
            }
        } catch (error) {
            // Silently fail - don't interrupt navigation if update fails
            console.debug('Failed to update maxTick:', error);
        }
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

        const samplingInterval = this.state.metadata?.samplingInterval || 1;
        let target = Math.max(0, tick); // Ensure we don't go below zero
        
        // Round down to the nearest sampling interval, unless it's 1
        if (samplingInterval > 1) {
            target = Math.floor(target / samplingInterval) * samplingInterval;
        }

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
            hideError();

            // Request minimap only on tick change (not on panning)
            const needMinimap = this.state.currentTick !== this.lastMinimapTick;

            // Fire both requests simultaneously — organisms are fast, environment is slow
            const environmentPromise = this.renderer.loadViewport(
                this.state.currentTick,
                this.state.runId,
                needMinimap
            );
            const organismPromise = this.organismApi.fetchOrganismsAtTick(
                this.state.currentTick,
                this.state.runId,
                { signal: organismSignal }
            );

            // Process organisms as soon as they arrive (don't wait for environment)
            const organismResult = await organismPromise;
            const organisms = organismResult.organisms;
            this.state.totalOrganismCount = organismResult.totalOrganismCount;
            this._applyGenomeLineageTree(organismResult.genomeLineageTree);
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
                        await this.loadOrganismDetails(organismId, isForwardStep);
                    } else {
                        this.state.selectedOrganismId = null;
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
            showError('Failed to load viewport: ' + error.message);
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
            hideError();
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
            showError('Failed to load environment for viewport: ' + error.message);
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
        const totalCount = this.state.totalOrganismCount || organisms.length;
        
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

        if (this.state.colorMode === 'genome') {
            return this._genomeHashToLineageHex(genomeHash);
        }

        const palette = AppController.ORGANISM_PALETTE;
        return palette[(organismId - 1) % palette.length];
    }

    /**
     * Applies the genome lineage tree from the backend API response.
     * Replaces the genome→parentGenome map and clears derived color caches.
     * @param {Object} tree - Map of genomeHash → parentGenomeHash (null for roots), from API response.
     * @private
     */
    _applyGenomeLineageTree(tree) {
        if (!tree) return;
        this._genomeParent.clear();
        this._genomeColorCache.clear();
        this._genomeHslCache.clear();
        for (const [genomeHash, parentGenomeHash] of Object.entries(tree)) {
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
        const key = String(genomeHash);
        if (!this._genomeColorCache.has(key)) {
            this._computeLineageColor(key);
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
     * Computes and caches the lineage color for a genome hash.
     * If the genome has a known parent, the color is derived by shifting the parent's hue.
     * Otherwise, a new root color is assigned via the golden-ratio sequence.
     * @param {string} genomeKey - String representation of the genome hash.
     * @private
     */
    _computeLineageColor(genomeKey) {
        if (this._genomeColorCache.has(genomeKey)) return;

        const parentGenomeKey = this._genomeParent.get(genomeKey);

        if (parentGenomeKey && parentGenomeKey !== '0' && parentGenomeKey !== genomeKey) {
            // Ensure parent color is computed first (recursive)
            if (!this._genomeColorCache.has(parentGenomeKey)) {
                this._computeLineageColor(parentGenomeKey);
            }

            const parentHsl = this._genomeHslCache.get(parentGenomeKey);
            if (parentHsl) {
                const hashBits = AppController._hashStringToInt(genomeKey);
                // Hue shift: ±25° (noticeable but keeps family resemblance)
                const direction = (hashBits & 1) ? 1 : -1;
                const h = (parentHsl[0] + direction * 25 + 360) % 360;
                // Small S/L perturbation for sibling differentiation
                const satDelta = ((hashBits >> 1) & 0x3F) / 63 * 0.06 - 0.03;
                const litDelta = ((hashBits >> 7) & 0x3F) / 63 * 0.06 - 0.03;
                const s = Math.max(0.65, Math.min(0.95, parentHsl[1] + satDelta));
                const l = Math.max(0.40, Math.min(0.60, parentHsl[2] + litDelta));

                this._genomeHslCache.set(genomeKey, [h, s, l]);
                this._genomeColorCache.set(genomeKey, AppController._hslToRgb(h, s, l));
                return;
            }
        }

        // Root genome: deterministic hue from genome hash (golden-ratio spread)
        const h = (120.0 + AppController._hashStringToInt(genomeKey) * 137.508) % 360;
        this._genomeHslCache.set(genomeKey, [h, 0.80, 0.50]);
        this._genomeColorCache.set(genomeKey, AppController._hslToRgb(h, 0.80, 0.50));
    }

    /**
     * Deterministic string hash to a non-negative 32-bit integer.
     * Used to extract pseudo-random bits from genome hash strings for color perturbation.
     * @param {string} str - Input string.
     * @returns {number} Non-negative integer.
     * @private
     */
    static _hashStringToInt(str) {
        let hash = 0;
        for (let i = 0; i < str.length; i++) {
            hash = ((hash << 5) - hash + str.charCodeAt(i)) | 0;
        }
        return Math.abs(hash);
    }

    /**
     * Converts HSL color values to a packed RGB integer.
     * @param {number} h - Hue in degrees (0-360).
     * @param {number} s - Saturation (0-1).
     * @param {number} l - Lightness (0-1).
     * @returns {number} Packed RGB integer (0xRRGGBB).
     * @private
     */
    static _hslToRgb(h, s, l) {
        const c = (1 - Math.abs(2 * l - 1)) * s;
        const hPrime = h / 60;
        const x = c * (1 - Math.abs(hPrime % 2 - 1));

        let r1, g1, b1;
        if (hPrime < 1) { r1 = c; g1 = x; b1 = 0; }
        else if (hPrime < 2) { r1 = x; g1 = c; b1 = 0; }
        else if (hPrime < 3) { r1 = 0; g1 = c; b1 = x; }
        else if (hPrime < 4) { r1 = 0; g1 = x; b1 = c; }
        else if (hPrime < 5) { r1 = x; g1 = 0; b1 = c; }
        else { r1 = c; g1 = 0; b1 = x; }

        const m = l - c / 2;
        const r = Math.round(Math.max(0, Math.min(255, (r1 + m) * 255)));
        const g = Math.round(Math.max(0, Math.min(255, (g1 + m) * 255)));
        const b = Math.round(Math.max(0, Math.min(255, (b1 + m) * 255)));

        return (r << 16) | (g << 8) | b;
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

            // Color based on current color mode
            let color;
            if (this.state.colorMode === 'genome') {
                color = this._genomeHashToLineageHex(entry.genomeHash);
            } else {
                const palette = AppController.ORGANISM_PALETTE;
                color = palette[(id - 1) % palette.length];
            }

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
     * Toggles the organism coloring mode between ID-based and genome-hash-based.
     */
    toggleColorMode() {
        this.state.colorMode = this.state.colorMode === 'id' ? 'genome' : 'id';
        localStorage.setItem('evochora-color-mode', this.state.colorMode);
        this._updateColorModeButton();

        // Re-render organisms with new color mode (no server reload needed)
        const organisms = this.state.previousOrganisms;
        if (organisms) {
            this.updateOrganismPanel(organisms);
            this.renderer.renderOrganisms(organisms);
            this.minimapView?.organismOverlay?.clearSpriteCache();
            this.minimapView?.setOwnershipColorResolver(this._minimapOwnershipColorResolver());
            this.minimapView?.updateOrganisms(
                organisms,
                this._minimapColorResolver(),
                this._minimapGroupKeyFn()
            );
        }
    }

    /**
     * Updates the color mode toggle button appearance.
     * @private
     */
    _updateColorModeButton() {
        const btn = document.getElementById('color-mode-toggle');
        if (!btn) return;
        const isGenome = this.state.colorMode === 'genome';
        btn.textContent = isGenome ? 'GH' : 'ID';
        btn.title = isGenome
            ? 'Color by: Genome Hash (click to switch to ID)'
            : 'Color by: Organism ID (click to switch to Genome)';
        btn.classList.toggle('active', isGenome);
    }

    /**
     * Returns a color resolver for the minimap organism overlay based on the current color mode.
     * @returns {function(string): string} Maps group key to hex color
     * @private
     */
    _minimapColorResolver() {
        if (this.state.colorMode === 'genome') {
            return (genomeHash) => this._genomeHashToLineageHex(genomeHash);
        }
        const palette = AppController.ORGANISM_PALETTE;
        return (organismId) => palette[(parseInt(organismId, 10) - 1) % palette.length];
    }

    /**
     * Returns a grouping key function for the minimap organism overlay based on the current color mode.
     * @returns {function(object): string} Extracts the grouping key from an organism
     * @private
     */
    _minimapGroupKeyFn() {
        if (this.state.colorMode === 'genome') {
            return (org) => String(org.genomeHash || 0);
        }
        return (org) => String(org.organismId);
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
        const livingIds = new Set();
        if (orgs) {
            for (const org of orgs) {
                livingIds.add(org.organismId);
            }
        }

        if (this.state.colorMode === 'genome') {
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
        const palette = AppController.ORGANISM_PALETTE;
        return (ownerId) => {
            if (!livingIds.has(ownerId)) return -1;
            return parseInt(palette[(ownerId - 1) % palette.length].slice(1), 16);
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
     * Ensures there is an initial runId by fetching the latest run if none is set.
     * Mirrors analyzer behavior: auto-picks newest run.
     * @private
     */
    async ensureInitialRunId() {
        if (this.state.runId) return;
        try {
            const response = await fetch('/analyzer/api/runs');
            if (!response.ok) {
                const text = await response.text();
                throw new Error(text || 'Failed to fetch runs');
            }
            const runs = await response.json();
            if (Array.isArray(runs) && runs.length > 0) {
                runs.sort((a, b) => (b.startTime || 0) - (a.startTime || 0));
                this.state.runId = runs[0].runId;
            }
        } catch (error) {
            console.error('Failed to auto-select runId:', error);
        }
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
