/**
 * Manages sidebar visibility and toggle functionality.
 */
class SidebarManager {
    constructor(appController) {
        this.sidebar = document.getElementById('sidebar');
        this.toggleBtn = document.getElementById('sidebar-toggle');
        this.container = document.querySelector('.container');
        this.appController = appController;
        this.isVisible = false;
        
        // Toggle button event listener
        if (this.toggleBtn) {
            this.toggleBtn.addEventListener('click', () => {
                this.toggleSidebar();
            });
        }
    }
    
    showSidebar() {
        if (this.sidebar) {
            this.sidebar.classList.add('visible');
            // Also set transform directly as fallback
            this.sidebar.style.transform = 'translateX(0)';
        }
        if (this.container) {
            this.container.classList.add('sidebar-visible');
        }
        this.isVisible = true;
    }
    
    hideSidebar(manual = false) {
        if (this.sidebar) {
            this.sidebar.classList.remove('visible');
            // Reset transform to ensure sidebar is hidden
            this.sidebar.style.transform = 'translateX(100%)';
        }
        if (this.container) {
            this.container.classList.remove('sidebar-visible');
        }
        this.isVisible = false;
        
        // If manually hidden, deselect the organism to prevent auto-reopening
        if (manual && this.appController) {
            this.appController.state.selectedOrganismId = null;
            
            // Update dropdown to show "---" selection
            const selector = document.getElementById('organism-selector');
            if (selector) {
                selector.value = '';
            }
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
        if (this.toggleBtn) {
            this.toggleBtn.style.display = visible ? 'block' : 'none';
        }
    }
    
    // Auto-hide when no organism is selected
    autoHide() {
        this.hideSidebar();
    }
    
    // Auto-show when organism is selected
    autoShow() {
        this.showSidebar();
        this.setToggleButtonVisible(true);
    }
}

// Export for global availability
window.SidebarManager = SidebarManager;

