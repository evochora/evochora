document.addEventListener('DOMContentLoaded', () => {
    window.EvoDebugger = window.EvoDebugger || {};

    // Expose classes to global scope
    window.EvoDebugger.SidebarView = SidebarView;
    window.EvoDebugger.SidebarManager = SidebarManager;
    window.EvoDebugger.ToolbarView = ToolbarView;
    
    // Make classes globally available for AppController
    window.SidebarView = SidebarView;
    window.SidebarManager = SidebarManager;
    window.ToolbarView = ToolbarView;
    
    // Initialize the application
    window.EvoDebugger.controller = new AppController();
    // Auto-load first tick
    window.EvoDebugger.controller.init().catch(console.error);
});


