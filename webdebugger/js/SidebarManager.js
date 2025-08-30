class SidebarManager {
    constructor() {
        this.sidebar = document.getElementById('sidebar');
        this.toggleBtn = document.getElementById('sidebar-toggle');
        this.isVisible = false;
        
        // Toggle button event listener
        this.toggleBtn.addEventListener('click', () => {
            this.toggleSidebar();
        });
    }
    
    showSidebar() {
        this.sidebar.classList.add('visible');
        this.isVisible = true;
    }
    
    hideSidebar() {
        this.sidebar.classList.remove('visible');
        this.isVisible = false;
    }
    
    toggleSidebar() {
        if (this.isVisible) {
            this.hideSidebar();
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
