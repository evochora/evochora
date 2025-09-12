class AppController {
    constructor() {
        this.statusManager = new StatusManager();
        this.api = new ApiService(this.statusManager);
        this.canvas = document.getElementById('worldCanvas');
        
        // Default configuration - will be overridden by actual simulation metadata
        const defaultConfig = { 
            worldSize: [100,30], 
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
            colorDead: '#505050' 
        };
        
        this.renderer = new WorldRenderer(this.canvas, defaultConfig, {});
        this.sidebar = new SidebarView(document.getElementById('sidebar'), this);
        this.sidebarManager = new SidebarManager(this);
        this.toolbar = new ToolbarView(this);
        this.state = { currentTick: 0, selectedOrganismId: null, lastTickData: null, totalTicks: null };
        this.canvas.addEventListener('click', (e) => this.onCanvasClick(e));
        
        // Tracking für Navigationsrichtung (für Änderungs-Hervorhebung)
        this.lastNavigationDirection = null; // 'forward', 'backward', 'goto'
        
        // Referenz auf den AppController für Parent-Navigation
        this.appController = null;
    }
    
    async init() {
        // Load URL parameters first
        this.loadFromUrl();
        
        // Load simulation metadata first to get correct world dimensions
        // NEU: Kein separater /api/meta Request mehr - worldMeta kommt aus Tick-Response
        await this.navigateToTick(this.state.currentTick); 
    }
    
    async navigateToTick(tick) {
        let target = typeof tick === 'number' ? tick : 0;
        if (target < 0) target = 0;
        // Tick validation against totalTicks removed - allow navigation to any tick
        
        // Bestimme die Navigationsrichtung für Änderungs-Hervorhebung
        if (target === this.state.currentTick + 1) {
            this.lastNavigationDirection = 'forward';
        } else if (target === this.state.currentTick - 1) {
            this.lastNavigationDirection = 'backward';
        } else {
            this.lastNavigationDirection = 'goto';
        }
        
        try {
            const data = await this.api.fetchTickData(target);
            this.state.currentTick = target;
            this.state.lastTickData = data;
            
            // Reset keyboard events to prevent stuck keys
            this.resetKeyboardEvents();
            if (typeof data.totalTicks === 'number') {
                this.state.totalTicks = data.totalTicks;
            }
            if (data.worldMeta && Array.isArray(data.worldMeta.shape)) {
                this.renderer.updateWorldShape(data.worldMeta.shape);
            }
            // ISA-Mapping is intentionally not used; rely solely on cell.opcodeName provided by backend
            const typeToId = t => ({ CODE:0, DATA:1, ENERGY:2, STRUCTURE:3 })[t] ?? 1;
            const cells = (data.worldState?.cells||[]).map(c => ({ position: JSON.stringify(c.position), type: typeToId(c.type), value: c.value, ownerId: c.ownerId, opcodeName: c.opcodeName }));
            const organisms = (data.worldState?.organisms||[]).map(o => {
                // Hole den korrekten activeDpIndex aus organismDetails
                const details = data.organismDetails?.[o.id];
                const correctActiveDpIndex = details?.internalState?.activeDpIndex ?? o.activeDpIndex ?? 0;
                return { organismId: o.id, programId: o.programId, energy: o.energy, positionJson: JSON.stringify(o.position), dps: o.dps, dv: o.dv, activeDpIndex: correctActiveDpIndex };
            });
            this.renderer.draw({ cells, organisms, selectedOrganismId: this.state.selectedOrganismId });
            const ids = Object.keys(data.organismDetails||{});
            const sel = this.state.selectedOrganismId && ids.includes(this.state.selectedOrganismId) ? this.state.selectedOrganismId : null;
            if (sel) {
                this.sidebar.update(data.organismDetails[sel], this.lastNavigationDirection);
                this.sidebarManager.autoShow();
                this.sidebarManager.setToggleButtonVisible(true);
            } else {
                // No organism selected - auto-hide sidebar
                this.sidebarManager.autoHide();
                this.sidebarManager.setToggleButtonVisible(false);
            }
            this.updateTickUi();
            this.saveToUrl(); // Save state to URL
        } catch (error) {
            // Error is already displayed by ApiService
            console.error('Failed to navigate to tick:', error);
        }
    }
    
    updateTickUi() {
        const input = document.getElementById('tick-input');
        if (input) input.value = String(this.state.currentTick || 0);
        const suffix = document.getElementById('tick-total-suffix');
        if (suffix) suffix.textContent = '/' + (this.state.totalTicks != null ? this.state.totalTicks : 'N/A');
        if (input && typeof this.state.totalTicks === 'number' && this.state.totalTicks > 0) {
            try { input.max = String(Math.max(0, this.state.totalTicks - 1)); } catch (_) {}
        }
    }
    
    onCanvasClick(event) {
        if (!this.renderer) return; // Renderer noch nicht verfügbar
        
        const rect = this.canvas.getBoundingClientRect();
        const x = event.clientX - rect.left;
        const y = event.clientY - rect.top;
        const gridX = Math.floor(x / this.renderer.config.cellSize);
        const gridY = Math.floor(y / this.renderer.config.cellSize);
        const organisms = (this.state.lastTickData?.worldState?.organisms)||[];
        for (const o of organisms) {
            const pos = o.position;
            if (Array.isArray(pos) && pos[0] === gridX && pos[1] === gridY) {
                this.state.selectedOrganismId = String(o.id);
                const det = this.state.lastTickData.organismDetails?.[this.state.selectedOrganismId];
                if (det) {
                    this.sidebar.update(det, this.lastNavigationDirection);
                    this.sidebarManager.autoShow();
                    this.sidebarManager.setToggleButtonVisible(true);
                    this.saveToUrl(); // Save state to URL
                    break;
                }
            }
        }
    }
    
    showError(message) {
        // Verwende den StatusManager für Fehlermeldungen
        if (window.EvoDebugger && window.EvoDebugger.statusManager) {
            window.EvoDebugger.statusManager.showError(message);
        } else {
            // Fallback: Zeige Fehler in der Konsole
            console.error('Error:', message);
            alert('Fehler: ' + message);
        }
    }
    
    // Reset keyboard event stack to prevent stuck keys
    resetKeyboardEvents() {
        if (this.toolbar && this.toolbar.handleKeyRelease) {
            this.toolbar.handleKeyRelease();
        }
    }
    
    // Load state from URL parameters
    loadFromUrl() {
        const urlParams = new URLSearchParams(window.location.search);
        const tick = urlParams.get('tick');
        const organism = urlParams.get('organism');
        
        if (tick !== null) {
            const tickNumber = parseInt(tick, 10);
            if (!isNaN(tickNumber) && tickNumber >= 0) {
                this.state.currentTick = tickNumber;
            }
        }
        
        if (organism !== null) {
            this.state.selectedOrganismId = organism;
        }
    }
    
    // Save state to URL parameters
    saveToUrl() {
        const url = new URL(window.location);
        const params = url.searchParams;
        
        if (this.state.currentTick !== null && this.state.currentTick !== 0) {
            params.set('tick', this.state.currentTick.toString());
        } else {
            params.delete('tick');
        }
        
        if (this.state.selectedOrganismId !== null) {
            params.set('organism', this.state.selectedOrganismId);
        } else {
            params.delete('organism');
        }
        
        // Update URL without reloading the page
        window.history.replaceState({}, '', url.toString());
    }
}
