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
            selectedOrganismId: null // Track selected organism across tick changes
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
        this.sidebarBasicInfo = new SidebarBasicInfoView(sidebarRoot);
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
     */
    async loadOrganismDetails(organismId) {
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
                // Update basic info view with static data and hot-path values (IP, DV, ER)
                this.sidebarBasicInfo.update(staticInfo, details.organismId, state);
                
                // Update state view with runtime data (starts with DP, no IP/DV/ER)
                if (state) {
                    this.sidebarStateView.update(state);
                }
                
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
        await this.loadViewport();
    }
    
    /**
     * Loads environment data and organism summaries for the current tick and viewport.
     */
    async loadViewport() {
        try {
            // Load environment cells first (viewport-based)
            await this.renderer.loadViewport(this.state.currentTick, this.state.runId);

            // Then load organisms for this tick (no region; filtering happens client-side)
            const organisms = await this.organismApi.fetchOrganismsAtTick(
                this.state.currentTick,
                this.state.runId
            );
            this.renderer.renderOrganisms(organisms);
            this.updateOrganismSelector(organisms);
            
            // Reload organism details if one is selected
            if (this.state.selectedOrganismId) {
                const organismId = parseInt(this.state.selectedOrganismId, 10);
                if (!isNaN(organismId)) {
                    await this.loadOrganismDetails(organismId);
                }
            }
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
     * 
     * @param {Array<Object>} organisms - Array of organism summaries:
     *   [{ organismId, energy, ip: [x,y], dv, dataPointers, activeDpIndex }, ...]
     */
    updateOrganismSelector(organisms) {
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
        let optionsHtml = `<option value="">--- (${aliveCount}/${totalCount})</option>`;
        
        // Track which organism IDs exist in the new tick
        const organismIdsInNewTick = new Set();
        
        // Add organism options with formatting matching old WebDebugger
        organisms.forEach(organism => {
            if (!organism || typeof organism.organismId !== 'number') {
                return;
            }
            
            organismIdsInNewTick.add(String(organism.organismId));
            
            // Get organism color (as hex string for CSS)
            const color = this.getOrganismColor(organism.organismId, organism.energy);
            const energy = organism.energy || 0;
            const ip = organism.ip || [];
            const x = ip[0] ?? '?';
            const y = ip[1] ?? '?';
            
            // Format: <ID>: [x | y] (<ER wert>)
            // Set color directly in HTML with inline style
            const text = `${organism.organismId}: [${x} | ${y}] (${energy})`;
            optionsHtml += `<option value="${organism.organismId}" style="color: ${color} !important;">${text}</option>`;
        });
        
        // Set all options at once
        selector.innerHTML = optionsHtml;
        
        // Restore selection if the previously selected organism still exists
        if (previouslySelected && organismIdsInNewTick.has(previouslySelected)) {
            selector.value = previouslySelected;
            // Update state to match
            this.state.selectedOrganismId = previouslySelected;
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
        } else {
            // Reset to "---" if previously selected organism no longer exists
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

