/**
 * The main application controller. It initializes all components, manages the application state,
 * and orchestrates the data flow between the API clients and the UI views.
 *
 * This class is the central hub of the visualizer.
 *
 * @class AppController
 */
class AppController {
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
        this.state = {
            currentTick: 0,
            maxTick: null,
            worldShape: null,
            runId: null,
            selectedOrganismId: null, // Track selected organism across tick changes
            previousTick: null, // For change detection
            previousOrganisms: null, // For change detection in dropdown
            previousOrganismDetails: null, // For change detection in sidebar
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
            backgroundColor: '#0a0a14',
            colorEmptyBg: '#14141e',
            colorCodeBg: '#3c5078',
            colorDataBg: '#32323c',
            colorStructureBg: '#ff7878',
            colorEnergyBg: '#ffe664',
            colorCodeText: '#ffffff',
            colorDataText: '#ffffff',
            colorStructureText: '#323232',
            colorEnergyText: '#323232',
            colorText: '#ffffff'
        };
        
        // Components
        this.worldContainer = document.querySelector('.world-container');
        this.renderer = new EnvironmentGrid(this.worldContainer, defaultConfig, this.environmentApi);
        this.headerbar = new HeaderbarView(this);
        
        // Sidebar components (initialize after DOM is ready)
        const sidebarRoot = document.getElementById('organism-details');
        if (!sidebarRoot) {
            console.warn('Sidebar root element not found');
        }
        this.sidebarManager = new SidebarManager(this);
        this.sidebarBasicInfo = new SidebarBasicInfoView(sidebarRoot, this.renderer, this);
        this.sidebarInstructionView = new SidebarInstructionView(sidebarRoot);
        this.sidebarStateView = new SidebarStateView(sidebarRoot);
        this.sidebarSourceView = new SidebarSourceView(sidebarRoot);
        
        // Setup organism selector change handler
        this.setupOrganismSelector();
        
        // Load initial state (runId, tick) from URL if present
        this.loadFromUrl();
        
        // Setup viewport change handler (environment only, organisms are cached per tick)
        this.renderer.onViewportChange = () => {
            this.loadEnvironmentForCurrentViewport();
        };
    }
    
    /**
     * Sets up the event listener for the organism selector dropdown.
     * Handles loading organism details and showing/hiding the sidebar when the selection changes.
     * @private
     */
    setupOrganismSelector() {
        const selector = document.getElementById('organism-selector');
        if (!selector) return;
        
        selector.addEventListener('change', async (event) => {
            const selectedValue = event.target.value;
            // Update state to track selected organism
            this.state.selectedOrganismId = selectedValue || null;
            
            if (selectedValue) {
                // Update select element color to match selected organism
                const selectedOption = event.target.options[event.target.selectedIndex];
                if (selectedOption) {
                    // Try to get color from inline style attribute
                    const styleAttr = selectedOption.getAttribute('style');
                    if (styleAttr) {
                        const colorMatch = styleAttr.match(/color:\s*([^;!]+)/);
                        if (colorMatch) {
                            event.target.style.color = colorMatch[1].trim();
                        }
                    } else {
                        // Fallback: calculate color from organism ID
                        const organismId = parseInt(selectedValue, 10);
                        if (!isNaN(organismId)) {
                            const color = this.getOrganismColor(organismId, 1); // Assume alive
                            event.target.style.color = color;
                        }
                    }
                }
                
                // Load organism details and show sidebar
                await this.loadOrganismDetails(parseInt(selectedValue, 10));
            } else {
                // Reset to default color when "---" is selected
                event.target.style.color = '#e0e0e0';
                // Hide sidebar
                this.sidebarManager.hideSidebar(true);
            }
        });
    }
    
    /**
     * Fetches and displays detailed information for a specific organism in the sidebar.
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
                // Update basic info view with static data and clickable IP/DP coordinates
                this.sidebarBasicInfo.update(staticInfo, details.organismId, state);
                
                // Update instruction view with last and next instructions
                if (details.instructions) {
                    this.sidebarInstructionView.update(details.instructions, this.state.currentTick);
                }
                
                // Update state view with runtime data (starts with DP, no IP/DV/ER)
                if (state) {
                    const previousState = (isForwardStep && this.state.previousOrganismDetails && 
                                          this.state.previousOrganismDetails.organismId === organismId) 
                                         ? this.state.previousOrganismDetails.state 
                                         : null;
                    this.sidebarStateView.update(state, isForwardStep, previousState, staticInfo);
                }
                
                // Update State View Artifact (Clean Architecture)
                const programIdForState = staticInfo.programId;
                if (programIdForState) {
                    // 1. Resolve Artifact (Controller responsibility)
                    let artifactForState = this.programArtifactCache.get(programIdForState) || null;
                    
                    // 2. Set Context (View decides if update needed)
                    this.sidebarStateView.setProgram(artifactForState);
                } else {
                    this.sidebarStateView.setProgram(null);
                }

                // Update Source View (Clean Architecture)
                const programId = staticInfo.programId;
                if (programId) {
                    // 1. Resolve Artifact (Controller responsibility)
                    let artifact = this.programArtifactCache.get(programId) || null;
                    
                    // 2. Set Context (View decides if update needed)
                    this.sidebarSourceView.setProgram(artifact);
                    
                    // 3. Update Dynamic State (Fast update)
                    this.sidebarSourceView.updateExecutionState(state, staticInfo);
                } else {
                    this.sidebarSourceView.setProgram(null);
                }
                
                // Save current details for next comparison
                this.state.previousOrganismDetails = details;
                
                // Show sidebar
                this.sidebarManager.autoShow();
            } else {
                console.warn('No static info in details:', details);
            }
        } catch (error) {
            // Ignore AbortError, as it's an expected cancellation
            if (error.name === 'AbortError') {
                console.debug('Request aborted by user navigation.');
                return;
            }
            console.error('Failed to load organism details:', error);
            // Hide sidebar on error
            this.sidebarManager.hideSidebar(true);
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
            // Initialize renderer
            await this.renderer.init();
            
            // Abort previous request if it's still running
            if (this.simulationRequestController) {
                this.simulationRequestController.abort();
            }
            this.simulationRequestController = new AbortController();
            const signal = this.simulationRequestController.signal;

            // Load metadata for world shape
            const metadata = await this.simulationApi.fetchMetadata(this.state.runId, { signal });
            if (metadata) {
                if (metadata.environment && metadata.environment.shape) {
                    this.state.worldShape = Array.from(metadata.environment.shape);
                    // Wait a bit before updating world shape to ensure devicePixelRatio is stable
                    // This helps with monitor-specific initialization issues
                    await new Promise(resolve => requestAnimationFrame(resolve));
                    this.renderer.updateWorldShape(this.state.worldShape);
                }

                // Cache program artifacts
                if (Array.isArray(metadata.programs)) {
                    for (const program of metadata.programs) {
                        if (program && program.programId && program.sources) {
                            this.programArtifactCache.set(program.programId, program);
                        }
                    }
                    console.debug(`Cached ${this.programArtifactCache.size} program artifacts.`);
                }
            }
            
            // Load tick range for maxTick
            const tickRange = await this.environmentApi.fetchTickRange(this.state.runId);
            if (tickRange) {
                this.state.maxTick = tickRange.maxTick;
                this.headerbar.updateTickDisplay(this.state.currentTick, this.state.maxTick);
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
            
            // Load initial tick
            await this.navigateToTick(this.state.currentTick);
            
        } catch (error) {
            // Ignore AbortError, as it's an expected cancellation
            if (error.name === 'AbortError') {
                console.debug('Request aborted by user navigation.');
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
            const tickRange = await this.environmentApi.fetchTickRange(this.state.runId);
            if (tickRange && tickRange.maxTick !== undefined) {
                const oldMaxTick = this.state.maxTick;
                const newMaxTick = tickRange.maxTick;
                
                if (newMaxTick !== oldMaxTick) {
                    this.state.maxTick = newMaxTick;
                    this.headerbar.updateTickDisplay(this.state.currentTick, this.state.maxTick);
                }
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
     */
    async navigateToTick(tick) {
        const target = Math.max(0, tick);
        const previousTick = this.state.currentTick;
        
        // Check if this is a forward step (x -> x+1)
        const isForwardStep = (target === previousTick + 1);
        
        // Update state
        this.state.currentTick = target;

        // Organismen-Overlays werden nicht hart gelöscht; renderOrganisms()
        // entfernt bzw. aktualisiert Marker organismusweise basierend auf
        // den Daten des neuen Ticks, um Flicker zu minimieren.
        
        // Update headerbar with current values
        this.headerbar.updateTickDisplay(this.state.currentTick, this.state.maxTick);
        
        // Update maxTick from server (non-blocking)
        // Use .catch() to handle errors without blocking navigation
        this.updateMaxTick().catch(error => {
            console.error('updateMaxTick failed:', error);
        });

        // Load environment and organisms for new tick
        await this.loadViewport(isForwardStep, previousTick);
    }
    
    /**
     * Loads all necessary data for the current tick and viewport.
     * This includes both the environment cells and the organism summaries. It then
     * triggers updates for the renderer and the organism selector.
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
            // Load environment cells first (viewport-based)
            await this.renderer.loadViewport(this.state.currentTick, this.state.runId);

            // Then load organisms for this tick (no region; filtering happens client-side)
            const organisms = await this.organismApi.fetchOrganismsAtTick(
                this.state.currentTick,
                this.state.runId,
                { signal: organismSignal }
            );
            this.renderer.renderOrganisms(organisms);
            this.updateOrganismSelector(organisms, isForwardStep);
            
            // Reload organism details if one is selected
            if (this.state.selectedOrganismId) {
                const organismId = parseInt(this.state.selectedOrganismId, 10);
                if (!isNaN(organismId)) {
                    await this.loadOrganismDetails(organismId, isForwardStep);
                }
            }
            
            // Save current organisms for next comparison
            this.state.previousOrganisms = organisms;
            this.state.previousTick = this.state.currentTick;
        } catch (error) {
            // Ignore AbortError, as it's an expected cancellation
            if (error.name === 'AbortError') {
                console.debug('Request aborted by user navigation.');
                return;
            }
            console.error('Failed to load viewport:', error);
            showError('Failed to load viewport: ' + error.message);
            // Update dropdown with empty list on error
            this.updateOrganismSelector([]);
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
                console.debug('Request aborted by user navigation.');
                return;
            }
            console.error('Failed to load environment for viewport:', error);
            showError('Failed to load environment for viewport: ' + error.message);
        }
    }

    /**
     * Updates the organism selector dropdown with the list of organisms for the current tick.
     * It preserves the user's selection if the organism still exists and updates the summary counts.
     * 
     * @param {Array<object>} organisms - An array of organism summary objects for the current tick.
     * @param {boolean} [isForwardStep=false] - True if navigating forward.
     * @private
     */
    updateOrganismSelector(organisms, isForwardStep = false) {
        const selector = document.getElementById('organism-selector');
        if (!selector) return;
        
        if (!Array.isArray(organisms)) {
            organisms = [];
        }
        
        // Save currently selected value before updating
        const previouslySelected = selector.value;
        
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
        
        // Build options HTML with inline styles for colors
        let optionsHtml = `<option value="">(${aliveCount}/${totalCount})</option>`;
        
        // Track which organism IDs exist in the new tick
        const organismIdsInNewTick = new Set();
        
        // Add organism options with formatting matching basic view: ID: 1     IP: x|y     DV: dx|dy DP: ...     ER: value
        organisms.forEach(organism => {
            if (!organism || typeof organism.organismId !== 'number') {
                return;
            }
            
            organismIdsInNewTick.add(String(organism.organismId));
            
            // Get organism color (as hex string for CSS)
            const color = this.getOrganismColor(organism.organismId, organism.energy);
            const energy = organism.energy || 0;
            
            // Format IP
            const ip = organism.ip || [];
            const ipStr = ip.length >= 2 ? `${ip[0]}|${ip[1]}` : '?|?';
            
            // Format DV
            const dv = organism.dv || [];
            const dvStr = dv.length >= 2 ? `${dv[0]}|${dv[1]}` : '?|?';
            
            // Format DPs: show all DPs, active one in brackets
            let dpStr = '';
            if (organism.dataPointers && Array.isArray(organism.dataPointers) && organism.dataPointers.length > 0) {
                const activeDpIndex = organism.activeDpIndex != null ? organism.activeDpIndex : -1;
                const dpParts = [];
                for (let i = 0; i < organism.dataPointers.length; i++) {
                    const dp = organism.dataPointers[i];
                    if (dp && Array.isArray(dp) && dp.length >= 2) {
                        let value = `${dp[0]}|${dp[1]}`;
                        if (i === activeDpIndex) {
                            value = `[${value}]`;
                        }
                        dpParts.push(value);
                    }
                }
                if (dpParts.length > 0) {
                    dpStr = `${dpParts.join(' ')}`;
                }
            }
            
            // Format: ID: 1     IP: x|y     DV: dx|dy DP: ...     ER: value
            const text = `ID: ${organism.organismId} &nbsp; IP: ${ipStr} &nbsp; DV: ${dvStr} &nbsp; DP: ${dpStr} &nbsp; ER: ${energy}`;
            optionsHtml += `<option value="${organism.organismId}" style="color: ${color} !important;">${text}</option>`;
        });
        
        // Set all options at once
        selector.innerHTML = optionsHtml;
        
        // Restore selection if the previously selected organism still exists
        if (previouslySelected && organismIdsInNewTick.has(previouslySelected)) {
            selector.value = previouslySelected;
            // Update state to match
            this.state.selectedOrganismId = previouslySelected;
            
            // Find selected organism
            const selectedOrg = organisms.find(o => String(o.organismId) === previouslySelected);
            
            if (selectedOrg) {
                // Update select element color to match selected organism
                const selectedOption = selector.options[selector.selectedIndex];
                if (selectedOption) {
                    const styleAttr = selectedOption.getAttribute('style');
                    if (styleAttr) {
                        const colorMatch = styleAttr.match(/color:\s*([^;!]+)/);
                        if (colorMatch) {
                            selector.style.color = colorMatch[1].trim();
                        }
                    }
                }
            }
        } else {
            // Reset if previously selected organism no longer exists
            selector.value = '';
            this.state.selectedOrganismId = null;
            selector.style.color = '#e0e0e0';
        }
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
}

// Export for global availability
window.AppController = AppController;

// Wait for the UI to be ready before initializing the controller
document.addEventListener('uiReady', () => {
    window.visualizer = window.visualizer || {};
    window.visualizer.controller = new AppController();
    
    // Auto-initialize
    window.visualizer.controller.init().catch(error => {
        console.error('Failed to initialize visualizer:', error);
        showError('Failed to initialize visualizer: ' + error.message);
    });
});
