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
        };
        this.programArtifactCache = new Map(); // Cache for program artifacts
        
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
            backgroundColor: '#1a1a28', // Border area visible when scrolling beyond grid
            colorEmptyBg: '#14141e',
            colorCodeBg: '#3c5078',
            colorDataBg: '#32323c',
            colorStructureBg: '#ff7878',
            colorEnergyBg: '#ffe664',
            colorLabelBg: '#a0a0a8', // Light gray for jump target labels
            colorLabelRefBg: '#a0a0a8', // Same background as LABEL
            colorCodeText: '#ffffff',
            colorDataText: '#ffffff',
            colorStructureText: '#323232',
            colorEnergyText: '#323232',
            colorLabelText: '#323232', // Dark text on light background
            colorLabelRefText: '#ffffff', // Light text to distinguish from LABEL
            colorText: '#ffffff'
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

            // Cancel ongoing requests
            if (this.simulationRequestController) this.simulationRequestController.abort();
            if (this.organismSummaryRequestController) this.organismSummaryRequestController.abort();
            if (this.organismDetailsRequestController) this.organismDetailsRequestController.abort();

            // Reset state
            this.state.runId = trimmed;
            this.state.currentTick = 0;
            this.state.selectedOrganismId = null;
            this.state.previousTick = null;
            this.state.previousOrganisms = null;
            this.state.previousOrganismDetails = null;
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

        // Update state
        this.state.selectedOrganismId = numericId ? String(numericId) : null;

        // Update organism list selection in panel
        this.updateOrganismListSelection();

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
        // Tick panel (navigation buttons, tick input, multiplier, keyboard shortcuts)
        this.tickPanelManager = new TickPanelManager({
            panel: document.getElementById('tick-panel'),
            prevSmallBtn: document.getElementById('btn-prev-small'),
            nextSmallBtn: document.getElementById('btn-next-small'),
            prevLargeBtn: document.getElementById('btn-prev-large'),
            nextLargeBtn: document.getElementById('btn-next-large'),
            tickInput: document.getElementById('tick-input'),
            tickSuffix: document.getElementById('tick-total-suffix'),
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
            onTickClick: (tick) => this.navigateToTick(tick),
            onParentClick: (parentId) => this.selectOrganism(parentId)
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
                
                // Update info section (Birth, MR, Parent)
                const infoEl = document.querySelector('[data-section="info"]');
                if (infoEl && staticInfo && state) {
                    const birthTick = staticInfo.birthTick;
                    const mrValue = state.moleculeMarkerRegister != null ? state.moleculeMarkerRegister : 0;
                    const parentId = staticInfo.parentId;
                    
                    // Birth is clickable (navigates to tick)
                    const birthDisplay = birthTick != null 
                        ? `<span class="clickable-tick" data-tick="${birthTick}">${birthTick}</span>` 
                        : '-';
                    
                    // Parent is clickable only if alive
                    let parentDisplay = '-';
                    if (parentId != null) {
                        const isParentAlive = this.organismPanelManager?.currentOrganisms?.some(
                            o => String(o.id) === String(parentId)
                        );
                        if (isParentAlive) {
                            parentDisplay = `<span class="clickable-parent" data-parent-id="${parentId}">#${parentId}</span>`;
                        } else {
                            parentDisplay = `#${parentId}`;
                        }
                    }
                    
                    infoEl.innerHTML = `<div class="organism-info-line">Birth: ${birthDisplay}  MR: ${mrValue}  Parent: ${parentDisplay}</div>`;
                    
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
                    infoEl.querySelectorAll('.clickable-parent').forEach(el => {
                        el.addEventListener('click', (e) => {
                            e.stopPropagation();
                            const pId = el.dataset.parentId;
                            if (pId) {
                                this.selectOrganism(pId);
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

            // Abort previous request if it's still running
            if (this.simulationRequestController) {
                this.simulationRequestController.abort();
            }
            this.simulationRequestController = new AbortController();
            const signal = this.simulationRequestController.signal;

            // Load metadata for world shape
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
            await this.navigateToTick(this.state.currentTick, true);
            window.runSelectorPanel?.updateCurrent?.();
            
        } catch (error) {
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
            if (this.state.runId) {
                url.searchParams.set('runId', this.state.runId);
            }
            if (this.state.currentTick !== null && this.state.currentTick !== undefined) {
                url.searchParams.set('tick', this.state.currentTick);
            } else {
                url.searchParams.delete('tick');
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

        try {
            hideError();

            // Request minimap only on tick change (not on panning)
            const needMinimap = this.state.currentTick !== this.lastMinimapTick;

            // Load environment cells (viewport-based), with optional minimap
            const result = await this.renderer.loadViewport(
                this.state.currentTick,
                this.state.runId,
                needMinimap
            );

            // Update minimap if data received
            if (result?.minimap && this.state.worldShape) {
                this.minimapView.update(result.minimap, this.state.worldShape);
                this.lastMinimapTick = this.state.currentTick;
            }

            // Update minimap viewport rectangle
            this.updateMinimapViewport();

            // Then load organisms for this tick (no region; filtering happens client-side)
            const organisms = await this.organismApi.fetchOrganismsAtTick(
                this.state.currentTick,
                this.state.runId,
                { signal: organismSignal }
            );
            this.renderer.renderOrganisms(organisms);
            this.updateOrganismPanel(organisms, isForwardStep);

            // Update minimap organism overlay
            this.minimapView?.updateOrganisms(organisms);
            
            // Reload organism details if one is selected
            if (this.state.selectedOrganismId) {
                const organismId = parseInt(this.state.selectedOrganismId, 10);
                if (!isNaN(organismId)) {
                    // Check if selected organism still exists
                    const stillExists = organisms.some(o => String(o.organismId) === this.state.selectedOrganismId);
                    if (stillExists) {
                        await this.loadOrganismDetails(organismId, isForwardStep);
                    } else {
                        // Organism died - deselect
                        this.state.selectedOrganismId = null;
                        this.clearOrganismDetails();
                        this.updateOrganismListSelection();
                    }
                }
            }
            
            // Save current organisms for next comparison
            this.state.previousOrganisms = organisms;
            this.state.previousTick = this.state.currentTick;
        } catch (error) {
            // Ignore AbortError, as it's an expected cancellation
            if (error.name === 'AbortError') {
                // Request aborted by user navigation - expected
                return;
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
        try {
            hideError();
            await this.renderer.loadViewport(this.state.currentTick, this.state.runId);
            // Re-render organism markers for the new viewport using cached data
            this.renderer.renderOrganisms(this.renderer.currentOrganisms || []);
        } catch (error) {
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
        
        // Calculate organism counts
        const aliveCount = organisms.length;
        // Estimate total count from highest organism ID
        let totalCount = aliveCount;
        if (aliveCount > 0) {
            const maxId = Math.max(...organisms.map(org => org.organismId || 0));
            if (maxId > 0) {
                totalCount = maxId;
            }
        }
        
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
                color: this.getOrganismColor(organism.organismId, organism.energy),
                ip: organism.ip,
                dv: organism.dv,
                dataPointers: organism.dataPointers,
                activeDpIndex: organism.activeDpIndex,
                parentId: organism.parentId,
                birthTick: organism.birthTick
            };
        }).filter(Boolean);
        
        // Update organism list in panel
        this.organismPanelManager?.updateList(listData, this.state.selectedOrganismId);
    }
    
    /**
     * Gets a deterministic color for an organism based on its ID and energy state.
     * Returns a hex color string suitable for CSS.
     * 
     * @param {number} organismId - The ID of the organism.
     * @param {number} energy - The current energy level of the organism.
     * @returns {string} A hex color string (e.g., "#32cd32").
     * @private
     */
    getOrganismColor(organismId, energy) {
        // Same palette as EnvironmentGrid._getOrganismColor
        const organismColorPalette = [
            '#32cd32', '#1e90ff', '#dc143c', '#ffd700',
            '#ffa500', '#9370db', '#00ffff'
        ];
        
        if (typeof organismId !== 'number' || organismId < 1) {
            return '#ffffff'; // Default white for invalid IDs
        }
        
        // If energy <= 0, return dimmed grayish color to indicate death
        if (typeof energy === 'number' && energy <= 0) {
            return '#555555';
        }
        
        const paletteIndex = (organismId - 1) % organismColorPalette.length;
        return organismColorPalette[paletteIndex];
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
