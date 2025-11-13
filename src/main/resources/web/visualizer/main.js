/**
 * Main entry point for the visualizer application.
 */
document.addEventListener('DOMContentLoaded', () => {
    // Initialize the application
    window.visualizer = window.visualizer || {};
    window.visualizer.controller = new AppController();
    
    // Auto-initialize
    window.visualizer.controller.init().catch(error => {
        console.error('Failed to initialize visualizer:', error);
    });
});

