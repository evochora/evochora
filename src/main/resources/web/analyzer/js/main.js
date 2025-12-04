/**
 * Evochora Analytics Dashboard - Main Entry Point
 * 
 * Initializes the application when the DOM is ready.
 */

(function() {
    'use strict';
    
    // Wait for DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initApp);
    } else {
        initApp();
    }
    
    function initApp() {
        console.debug('[Analytics] Starting Evochora Analytics Dashboard...');
        
        // Setup error bar close button
        const closeErrorBtn = document.getElementById('close-error-banner');
        if (closeErrorBtn) {
            closeErrorBtn.addEventListener('click', () => {
                AnalyzerController.hideError();
            });
        }
        
        // Initialize the main controller
        AnalyzerController.init().catch(error => {
            console.error('[Analytics] Initialization failed:', error);
            AnalyzerController.showError(`Initialization failed: ${error.message}`);
        });
    }
    
})();

