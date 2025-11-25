class StatusManager {
    constructor() {
        this.statusBar = document.getElementById('status-bar');
        this.statusMessage = document.getElementById('status-message');
        this.statusClose = document.getElementById('status-close');
        
        this.statusClose.addEventListener('click', () => this.hideStatus());
    }
    
    showError(message, duration = 0) {
        this.showStatus(message, 'error', duration);
    }
    
    showInfo(message, duration = 5000) {
        this.showStatus(message, 'info', duration);
    }
    
    showStatus(message, type = 'info', duration = 0) {
        this.statusMessage.textContent = message;
        this.statusBar.className = `status-bar ${type}`;
        this.statusBar.style.display = 'flex';
        
        if (duration > 0) {
            setTimeout(() => this.hideStatus(), duration);
        }
    }
    
    hideStatus() {
        this.statusBar.style.display = 'none';
    }
}

// Export für globale Verfügbarkeit
window.StatusManager = StatusManager;
