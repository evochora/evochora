class SidebarManager {
    constructor(appController) {
        this.sidebar = document.getElementById('sidebar');
        this.toggleBtn = document.getElementById('sidebar-toggle');
        this.container = document.querySelector('.container');
        this.appController = appController;
        this.isVisible = false;
        
        // Toggle button event listener
        this.toggleBtn.addEventListener('click', () => {
            this.toggleSidebar();
        });
    }
    
    showSidebar() {
        this.sidebar.classList.add('visible');
        this.container.classList.add('sidebar-visible');
        this.isVisible = true;
    }
    
    hideSidebar(manual = false) {
        this.sidebar.classList.remove('visible');
        this.container.classList.remove('sidebar-visible');
        this.isVisible = false;
        
        // If manually hidden, deselect the organism to prevent auto-reopening
        if (manual && this.appController) {
            this.appController.state.selectedOrganismId = null;
            
            // Update dropdown to show "---" selection
            const selector = document.getElementById('organism-selector');
            if (selector) {
                selector.value = '';
            }
            
            // Save to URL to remove organism parameter
            this.appController.saveToUrl();
        }
    }
    
    toggleSidebar() {
        if (this.isVisible) {
            this.hideSidebar(true); // Manual hide
        } else {
            this.showSidebar();
        }
    }
    
    setToggleButtonVisible(visible) {
        this.toggleBtn.style.display = visible ? 'block' : 'none';
    }
    
    // Auto-hide when no organism is selected
    autoHide() {
        this.hideSidebar();
    }
    
    // Auto-show when organism is selected
    autoShow() {
        this.showSidebar();
    }
}
