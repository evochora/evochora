/**
 * Manages the visibility and state of the main sidebar container.
 * It controls the CSS classes that show/hide the sidebar and adjusts the main
 * content area accordingly. It also handles the logic for the toggle button
 * and auto-showing/hiding when an organism is selected or deselected.
 *
 * @class SidebarManager
 */
class SidebarManager {
    /**
     * Initializes the SidebarManager, caching DOM elements and setting up event listeners.
     * @param {AppController} appController - The main application controller, used to deselect organisms.
     */
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
    
    /**
     * Makes the sidebar visible by applying the appropriate CSS classes.
     */
    showSidebar() {
        if (this.sidebar) {
            this.sidebar.classList.add('visible');
            // Also set transform directly as fallback
            this.sidebar.style.transform = 'translateX(0)';
        }
        if (this.container) {
            this.container.classList.add('sidebar-visible');
        }
        // Show toggle button when sidebar is visible
        this.setToggleButtonVisible(true);
        this.isVisible = true;
    }
    
    /**
     * Hides the sidebar. If hidden manually by the user, it also deselects the
     * currently active organism to prevent it from immediately reopening.
     *
     * @param {boolean} [manual=false] - True if the action was triggered by a direct user interaction (e.g., toggle button).
     */
    hideSidebar(manual = false) {
        if (this.sidebar) {
            this.sidebar.classList.remove('visible');
            // Reset transform to ensure sidebar is hidden
            this.sidebar.style.transform = 'translateX(100%)';
        }
        if (this.container) {
            this.container.classList.remove('sidebar-visible');
        }
        // Hide toggle button when sidebar is hidden
        this.setToggleButtonVisible(false);
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
    
    /**
     * Toggles the sidebar's visibility. Considers the action as manual.
     */
    toggleSidebar() {
        if (this.isVisible) {
            this.hideSidebar(true); // Manual hide
        } else {
            this.showSidebar();
        }
    }
    
    /**
     * Shows or hides the sidebar toggle button.
     * @param {boolean} visible - True to show the button, false to hide it.
     * @private
     */
    setToggleButtonVisible(visible) {
        if (this.toggleBtn) {
            this.toggleBtn.style.display = visible ? 'block' : 'none';
        }
    }
    
    /**
     * Hides the sidebar as part of an automatic action (e.g., no organism selected).
     */
    autoHide() {
        this.hideSidebar();
    }
    
    /**
     * Shows the sidebar as part of an automatic action (e.g., an organism was selected).
     */
    autoShow() {
        this.showSidebar();
        // Button visibility is already handled in showSidebar()
    }
}

// Export for global availability
window.SidebarManager = SidebarManager;

