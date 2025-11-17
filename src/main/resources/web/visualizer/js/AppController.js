/**
 * Main application controller coordinating all components.
 */
class AppController {
    constructor() {
        // APIs
        this.simulationApi = new SimulationApi();
        this.environmentApi = new EnvironmentApi();
        this.organismApi = new OrganismApi();
        
        // State
        this.state = {
            currentTick: 0,
            maxTick: null,
            worldShape: null,
            runId: null,
            selectedOrganismId: null, // Track selected organism across tick changes
            previousTick: null, // For change detection
            previousOrganisms: null, // For change detection in dropdown
            previousOrganismDetails: null // For change detection in sidebar
        };
        
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
        this.sidebarStateView = new SidebarStateView(sidebarRoot);
        
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
     * Sets up event listener for organism selector dropdown.
     * Opens sidebar and loads organism details when an organism is selected.
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
     * Loads detailed organism information and displays it in the sidebar.
     * 
     * @param {number} organismId - Organism ID to load
     * @param {boolean} isForwardStep - Whether this is a forward step (x -> x+1)
     */
    async loadOrganismDetails(organismId, isForwardStep = false) {
        try {
            const details = await this.organismApi.fetchOrganismDetails(
                this.state.currentTick,
                organismId,
                this.state.runId
            );
            
            // API returns "static" not "staticInfo"
            const staticInfo = details.static || details.staticInfo;
            const state = details.state;
            
            if (details && staticInfo) {
                // Update basic info view with static data and clickable IP/DP coordinates
                this.sidebarBasicInfo.update(staticInfo, details.organismId, state);
                
                // Update state view with runtime data (starts with DP, no IP/DV/ER)
                if (state) {
                    const previousState = (isForwardStep && this.state.previousOrganismDetails && 
                                          this.state.previousOrganismDetails.organismId === organismId) 
                                         ? this.state.previousOrganismDetails.state 
                                         : null;
                    this.sidebarStateView.update(state, isForwardStep, previousState);
                }
                
                // Save current details for next comparison
                this.state.previousOrganismDetails = details;
                
                // Show sidebar
                this.sidebarManager.autoShow();
            } else {
                console.warn('No static info in details:', details);
            }
        } catch (error) {
            console.error('Failed to load organism details:', error);
            // Hide sidebar on error
            this.sidebarManager.hideSidebar(true);
        }
    }
    
    /**
     * Initializes the application by loading metadata and tick range.
     */
    async init() {
        try {
            // Initialize renderer
            await this.renderer.init();
            
            // Load metadata for world shape
            const metadata = await this.simulationApi.fetchMetadata(this.state.runId);
            if (metadata && metadata.environment && metadata.environment.shape) {
                this.state.worldShape = Array.from(metadata.environment.shape);
                // Wait a bit before updating world shape to ensure devicePixelRatio is stable
                // This helps with monitor-specific initialization issues
                await new Promise(resolve => requestAnimationFrame(resolve));
                this.renderer.updateWorldShape(this.state.worldShape);
            }
            
            // Load tick range for maxTick
            const tickRange = await this.simulationApi.fetchTickRange(this.state.runId);
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
            console.error('Failed to initialize application:', error);
            alert('Failed to initialize: ' + error.message);
        }
    }
    
    /**
     * Updates maxTick from server.
     * Called automatically on navigation, can also be called manually.
     */
    async updateMaxTick() {
        try {
            const tickRange = await this.simulationApi.fetchTickRange(this.state.runId);
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
     * Navigates to a specific tick and loads environment data.
     * 
     * @param {number} tick - Target tick number
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
     * Loads environment data and organism summaries for the current tick and viewport.
     * 
     * @param {boolean} isForwardStep - Whether this is a forward step (x -> x+1)
     * @param {number} previousTick - Previous tick number (for change detection)
     */
    async loadViewport(isForwardStep = false, previousTick = null) {
        try {
            // Load environment cells first (viewport-based)
            await this.renderer.loadViewport(this.state.currentTick, this.state.runId);

            // Then load organisms for this tick (no region; filtering happens client-side)
            const organisms = await this.organismApi.fetchOrganismsAtTick(
                this.state.currentTick,
                this.state.runId
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
            console.error('Failed to load viewport:', error);
            // Update dropdown with empty list on error
            this.updateOrganismSelector([]);
        }
    }

    /**
     * Loads only environment data for the current viewport (no new organism HTTP call).
     * Reuses cached organism data in the renderer for IP/DP overlay re-rendering.
     */
    async loadEnvironmentForCurrentViewport() {
        try {
            await this.renderer.loadViewport(this.state.currentTick, this.state.runId);
            // Re-render organism markers for the new viewport using cached data
            this.renderer.renderOrganisms(this.renderer.currentOrganisms || []);
        } catch (error) {
            console.error('Failed to load environment for viewport:', error);
        }
    }

    /**
     * Updates the organism selector dropdown with the current tick's organisms.
     * Preserves the selected organism if it still exists in the new tick.
     * Shows changed values in bold for the selected element (when dropdown is closed).
     * 
     * @param {Array<Object>} organisms - Array of organism summaries:
     *   [{ organismId, energy, ip: [x,y], dv, dataPointers, activeDpIndex }, ...]
     * @param {boolean} isForwardStep - Whether this is a forward step (x -> x+1)
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
     * Gets the color for an organism based on its ID and energy state.
     * Returns a hex color string suitable for CSS.
     * 
     * @param {number} organismId - Organism ID
     * @param {number} energy - Current energy level
     * @returns {string} Hex color string (e.g., "#32cd32")
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
     * Loads initial state (runId, tick) from the browser URL, if provided.
     * Supported query parameters:
     *  - runId: simulation run ID
     *  - tick: initial tick number
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

