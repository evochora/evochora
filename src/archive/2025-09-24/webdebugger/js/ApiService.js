class ApiService {
    constructor(statusManager) {
        this.statusManager = statusManager;
    }
    
    async fetchTickData(tick) {
        try {
            const res = await fetch(`/api/tick/${tick}`);
            
            if (!res.ok) {
                if (res.status === 404) {
                    const errorData = await res.json().catch(() => ({}));
                    if (errorData.error) {
                        this.statusManager.showError(errorData.error, 3000);
                    } else {
                        this.statusManager.showError(`Tick ${tick} nicht gefunden`, 3000);
                    }
                } else {
                    this.statusManager.showError(`HTTP Fehler: ${res.status}`, 3000);
                }
                throw new Error(`HTTP ${res.status}`);
            }
            
            const data = await res.json();
            
            // Validiere die Antwort-Struktur
            if (!this.validateTickData(data)) {
                this.statusManager.showError('Unerwartetes Datenformat vom Server', 3000);
                throw new Error('Invalid data format');
            }
            
            return data;
            
        } catch (error) {
            // Spezifische Fehlerbehandlung
            if (error.name === 'TypeError' && error.message.includes('fetch')) {
                // Netzwerkfehler - Server nicht erreichbar
                this.statusManager.showError('Debug Server nicht erreichbar - ist er gestartet?', 3000);
            } else if (error.message === 'Invalid data format') {
                // Bereits behandelt
            } else if (error.message.includes('HTTP')) {
                // HTTP-Fehler bereits behandelt
            } else {
                // Unerwarteter Fehler
                this.statusManager.showError(`Unerwarteter Fehler: ${error.message}`, 3000);
            }
            throw error;
        }
    }
    
    /**
     * Validiert die Struktur der Tick-Daten vom Server
     */
    validateTickData(data) {
        try {
            // Grundlegende Struktur prüfen
            if (!data || typeof data !== 'object') {
                return false;
            }
            
            // Erwartete Felder prüfen
            if (typeof data.tickNumber !== 'number') {
                return false;
            }
            
            if (!data.worldMeta || !Array.isArray(data.worldMeta.shape)) {
                return false;
            }
            
            if (!data.worldState || !Array.isArray(data.worldState.cells) || !Array.isArray(data.worldState.organisms)) {
                return false;
            }
            
            if (!data.organismDetails || typeof data.organismDetails !== 'object') {
                return false;
            }
            
            return true;
            
        } catch (error) {
            console.error('Error validating tick data:', error);
            return false;
        }
    }
}

// Export für globale Verfügbarkeit
window.ApiService = ApiService;
