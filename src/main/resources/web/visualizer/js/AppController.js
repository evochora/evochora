/**
 * Main application controller coordinating all components.
 */
class AppController {
    constructor() {
        // APIs
        this.simulationApi = new SimulationApi();
        this.environmentApi = new EnvironmentApi();
        
        // State
        this.state = {
            currentTick: 0,
            maxTick: null,
            worldShape: null,
            runId: null
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
        
        // Load initial state (runId, tick) from URL if present
        this.loadFromUrl();
        
        // Setup viewport change handler
        this.renderer.onViewportChange = () => {
            this.loadViewport();
        };
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
        
        // Update headerbar with current values
        this.headerbar.updateTickDisplay(this.state.currentTick, this.state.maxTick);
        
        // Update maxTick from server (non-blocking)
        // Use .catch() to handle errors without blocking navigation
        this.updateMaxTick().catch(error => {
            console.error('updateMaxTick failed:', error);
        });

        // Load viewport for new tick
        await this.loadViewport();
    }
    
    /**
     * Loads environment data for the current viewport.
     */
    async loadViewport() {
        try {
            await this.renderer.loadViewport(this.state.currentTick, this.state.runId);
        } catch (error) {
            console.error('Failed to load viewport:', error);
        }
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

