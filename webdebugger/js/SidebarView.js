class SidebarView {
    constructor(root, appController) {
        this.root = root;
        this.appController = appController;
        this.basic = new SidebarBasicInfoView(root);
        this.basic.appController = appController; // Referenz für Parent-Navigation
        this.next = new SidebarNextInstructionView(root);
        this.state = new SidebarStateView(root);
        this.source = new SidebarSourceView(root);
    }
    
    update(details, navigationDirection) {
        if (!details) return;
        this.basic.update(details.basicInfo, navigationDirection);
        this.next.update(details.nextInstruction, navigationDirection);
        this.state.update(details.internalState, navigationDirection);
        this.source.update(details.sourceView);
    }
}

// Export für globale Verfügbarkeit
window.SidebarView = SidebarView;
