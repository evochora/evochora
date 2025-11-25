class AppController {
    constructor() {
        this.statusManager = new StatusManager();
        this.api = new ApiService(this.statusManager);
        this.worldContainer = document.querySelector('.world-container');
        
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
        
        this.renderer = new WebGLRenderer(this.worldContainer, defaultConfig, {});
        this.sidebar = new SidebarView(document.getElementById('sidebar'), this);
        this.sidebarManager = new SidebarManager(this);
        this.toolbar = new ToolbarView(this);
        this.state = { currentTick: 0, selectedOrganismId: null, lastTickData: null, totalTicks: null };
        
        this.lastNavigationDirection = null;
        this.appController = null;
    }
    
    async init() {
        await this.renderer.init();
        this.renderer.app.view.addEventListener('click', (e) => this.onCanvasClick(e));
        
        this.setupOrganismSelector();
        
        this.loadFromUrl();
        await this.navigateToTick(this.state.currentTick);
        
        // Auto-scroll to organism only on initial load (from URL parameter)
        if (this.state.selectedOrganismId) {
            this.scrollToOrganism(this.state.selectedOrganismId);
        }
    }
    
    async navigateToTick(tick) {
        let target = typeof tick === 'number' ? tick : 0;
        if (target < 0) target = 0;
        
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
            
            this.resetKeyboardEvents();
            if (typeof data.totalTicks === 'number') {
                this.state.totalTicks = data.totalTicks;
            }
            if (data.worldMeta && Array.isArray(data.worldMeta.shape)) {
                this.renderer.updateWorldShape(data.worldMeta.shape);
            }
            const typeToId = t => ({ CODE:0, DATA:1, ENERGY:2, STRUCTURE:3 })[t] ?? 1;
            const cells = (data.worldState?.cells||[]).map(c => ({ position: JSON.stringify(c.position), type: typeToId(c.type), value: c.value, ownerId: c.ownerId, opcodeName: c.opcodeName }));
            const organisms = (data.worldState?.organisms||[]).map(o => {
                const details = data.organismDetails?.[o.id];
                const correctActiveDpIndex = details?.internalState?.activeDpIndex ?? o.activeDpIndex ?? 0;
                return { organismId: o.id, programId: o.programId, energy: o.energy, positionJson: JSON.stringify(o.position), dps: o.dps, dv: o.dv, activeDpIndex: correctActiveDpIndex };
            });
            this.renderer.draw({ cells, organisms, selectedOrganismId: this.state.selectedOrganismId });
            
            // Update organism selector dropdown
            this.updateOrganismSelector(data.worldState?.organisms || []);
            
            const ids = Object.keys(data.organismDetails||{});
            const sel = this.state.selectedOrganismId && ids.includes(this.state.selectedOrganismId) ? this.state.selectedOrganismId : null;
            if (sel) {
                this.sidebar.update(data.organismDetails[sel], this.lastNavigationDirection);
                this.sidebarManager.autoShow();
                this.sidebarManager.setToggleButtonVisible(true);
            } else {
                this.sidebarManager.autoHide();
                this.sidebarManager.setToggleButtonVisible(false);
            }
            
            // Note: Auto-scrolling to organism only happens on initial load or dropdown selection,
            // not during tick navigation to avoid disrupting user's view
            
            this.updateTickUi();
            this.saveToUrl();
        } catch (error) {
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
        if (!this.renderer) return;
        
        const rect = this.renderer.app.view.getBoundingClientRect();
        const x = event.clientX - rect.left;
        const y = event.clientY - rect.top;
        const gridX = Math.floor(x / this.renderer.config.cellSize);
        const gridY = Math.floor(y / this.renderer.config.cellSize);
        const organisms = (this.state.lastTickData?.worldState?.organisms) || [];
        for (const o of organisms) {
            const pos = o.position;
            if (Array.isArray(pos) && pos[0] === gridX && pos[1] === gridY) {
                this.state.selectedOrganismId = String(o.id);
                
                // Update dropdown selection
                const selector = document.getElementById('organism-selector');
                if (selector) {
                    selector.value = this.state.selectedOrganismId;
                }
                
                const det = this.state.lastTickData.organismDetails?.[this.state.selectedOrganismId];
                if (det) {
                    this.sidebar.update(det, this.lastNavigationDirection);
                    this.sidebarManager.autoShow();
                    this.sidebarManager.setToggleButtonVisible(true);
                    this.saveToUrl();
                    break;
                }
            }
        }
    }
    
    showError(message) {
        if (window.EvoDebugger && window.EvoDebugger.statusManager) {
            window.EvoDebugger.statusManager.showError(message);
        } else {
            console.error('Error:', message);
            alert('Fehler: ' + message);
        }
    }
    
    resetKeyboardEvents() {
        if (this.toolbar && this.toolbar.handleKeyRelease) {
            this.toolbar.handleKeyRelease();
        }
    }
    
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
        
        window.history.replaceState({}, '', url.toString());
    }
    
    setupOrganismSelector() {
        const selector = document.getElementById('organism-selector');
        if (!selector) return;
        
        selector.addEventListener('change', (event) => {
            const organismId = event.target.value;
            if (organismId) {
                this.selectOrganismById(organismId);
            } else {
                this.state.selectedOrganismId = null;
                this.sidebarManager.hideSidebar(true);
                this.saveToUrl();
            }
        });
    }
    
    updateOrganismSelector(organisms) {
        const selector = document.getElementById('organism-selector');
        if (!selector) return;
        
        // Calculate organism counts
        const aliveCount = organisms.length;
        const totalCount = this.getTotalOrganismCount();
        
        // Clear existing options except the first one
        selector.innerHTML = `<option value="">--- (${aliveCount}/${totalCount})</option>`;
        
        // Add organism options with improved formatting
        organisms.forEach(organism => {
            const option = document.createElement('option');
            option.value = organism.id;
            
            // Get organism color
            const color = this.getOrganismColor(organism.id);
            const energy = organism.energy || 0;
            const x = organism.position?.[0] ?? '?';
            const y = organism.position?.[1] ?? '?';
            
            // Format: <ID>: [x | y] (<ER wert>)
            option.textContent = `${organism.id}: [${x} | ${y}] (${energy})`;
            
            // Set color style
            option.style.color = color;
            
            selector.appendChild(option);
        });
        
        // Set selected value if there's a selected organism
        if (this.state.selectedOrganismId) {
            selector.value = this.state.selectedOrganismId;
        }
    }
    
    getTotalOrganismCount() {
        // Try to get total count from organism details
        const organismDetails = this.state.lastTickData?.organismDetails || {};
        const detailIds = Object.keys(organismDetails);
        
        if (detailIds.length > 0) {
            // Find the highest organism ID to estimate total count
            const maxId = Math.max(...detailIds.map(id => parseInt(id, 10)));
            return maxId;
        }
        
        // Fallback: use current alive count if no details available
        const organisms = this.state.lastTickData?.worldState?.organisms || [];
        return organisms.length;
    }
    
    getOrganismColor(id) {
        // Use the same color palette as WebGLRenderer
        const organismColorPalette = [
            '#32cd32', '#1e90ff', '#dc143c', '#ffd700',
            '#ffa500', '#9370db', '#00ffff'
        ];
        
        if (typeof id === 'string') {
            id = parseInt(id, 10);
        }
        
        if (isNaN(id) || id < 1) {
            return '#ffffff'; // Default white for invalid IDs
        }
        
        const paletteIndex = (id - 1) % organismColorPalette.length;
        return organismColorPalette[paletteIndex];
    }
    
    selectOrganismById(organismId) {
        this.state.selectedOrganismId = String(organismId);
        
        // Update dropdown selection
        const selector = document.getElementById('organism-selector');
        if (selector) {
            selector.value = this.state.selectedOrganismId;
        }
        
        const det = this.state.lastTickData?.organismDetails?.[this.state.selectedOrganismId];
        if (det) {
            this.sidebar.update(det, this.lastNavigationDirection);
            this.sidebarManager.autoShow();
            this.sidebarManager.setToggleButtonVisible(true);
            this.saveToUrl();
            
            // Scroll to organism IP
            this.scrollToOrganism(organismId);
        }
    }
    
    scrollToOrganism(organismId) {
        const organisms = this.state.lastTickData?.worldState?.organisms || [];
        const organism = organisms.find(o => String(o.id) === String(organismId));
        
        if (!organism || !organism.position || !Array.isArray(organism.position)) {
            return;
        }
        
        const [gridX, gridY] = organism.position;
        const cellSize = this.renderer.config.cellSize;
        
        // Calculate pixel position of the organism
        const pixelX = gridX * cellSize;
        const pixelY = gridY * cellSize;
        
        // Get world container
        const worldContainer = this.worldContainer;
        if (!worldContainer) return;
        
        // Calculate center position in the container
        const containerWidth = worldContainer.clientWidth;
        const containerHeight = worldContainer.clientHeight;
        
        // Calculate scroll position to center the organism
        const scrollLeft = Math.max(0, pixelX - containerWidth / 2);
        const scrollTop = Math.max(0, pixelY - containerHeight / 2);
        
        // Smooth scroll to the organism
        worldContainer.scrollTo({
            left: scrollLeft,
            top: scrollTop,
            behavior: 'smooth'
        });
    }
}
