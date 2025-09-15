class SidebarBasicInfoView {
    constructor(root) { 
        this.root = root;
        this.previousState = null;
    }
    
    update(info, navigationDirection) {
        const el = this.root.querySelector('[data-section="basic"]');
        if (!info || !el) return;
        
        // Berechne Änderungen nur bei "forward" Navigation
        const changeFlags = this.calculateChanges(info, navigationDirection);
        
        // Unveränderliche Infos über der Box
        // Check if parent is still alive
        const parentId = info.parentId;
        const isParentAlive = this.isParentAlive(parentId);
        
        const unchangeableInfo = [
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">ID:</span><span class="unchangeable-info-value">${info.id}</span></div>`,
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Parent:</span><span class="unchangeable-info-value">${parentId && parentId !== 'null' && parentId !== 'undefined' ? 
                (isParentAlive ? 
                    `<span class="clickable-parent alive" data-parent-id="${parentId}">${parentId}</span>` : 
                    `<span class="parent-dead">${parentId}</span>`) : 
                'N/A'}</span></div>`,
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Birth:</span><span class="unchangeable-info-value">${info.birthTick}</span></div>`,
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Program:</span><span class="unchangeable-info-value">${info.programId || 'N/A'}</span></div>`
        ].join('');
        
        // Veränderliche Werte in der Box
        const changeableValues = [
            `IP=[${info.ip.join('|')}]`,
            `DV=[${info.dv.join('|')}]`,
            `ER=${info.energy}`
        ].map((value, index) => {
            const key = ['ip', 'dv', 'energy'][index];
            const isChanged = changeFlags && changeFlags[key];
            return isChanged ? `<span class="changed">${value}</span>` : value;
        }).join(' ');
        
        el.innerHTML = `
            <div class="unchangeable-info">${unchangeableInfo}</div>
            <div class="code-view changeable-box">${changeableValues}</div>
        `;
        
        // Event Listener für klickbare Parent-ID
        const parentSpan = el.querySelector('.clickable-parent');
        if (parentSpan) {
            parentSpan.addEventListener('click', () => {
                const parentId = parentSpan.dataset.parentId;
                // Navigiere zum Birth-Tick des Parents
                this.navigateToParent(parentId);
            });
        }
        
        // Speichere aktuellen Zustand für nächsten Vergleich
        this.previousState = { ...info };
    }
    
    calculateChanges(currentInfo, navigationDirection) {
        // Nur bei "forward" Navigation Änderungen hervorheben
        if (navigationDirection !== 'forward' || !this.previousState) {
            return null;
        }
        
        const changeFlags = {};
        changeFlags.energy = currentInfo.energy !== this.previousState.energy;
        changeFlags.ip = JSON.stringify(currentInfo.ip) !== JSON.stringify(this.previousState.ip);
        changeFlags.dv = JSON.stringify(currentInfo.dv) !== JSON.stringify(this.previousState.dv);
        
        return changeFlags;
    }
    
    isParentAlive(parentId) {
        if (!parentId || parentId === 'null' || parentId === 'undefined') {
            return false;
        }
        
        if (!this.appController || !this.appController.state.lastTickData) {
            return false;
        }
        
        const organisms = this.appController.state.lastTickData.worldState?.organisms || [];
        return organisms.some(o => String(o.id) === String(parentId));
    }
    
    navigateToParent(parentId) {
        if (!this.appController || !this.appController.state.lastTickData) {
            return;
        }
        
        const organisms = this.appController.state.lastTickData.worldState?.organisms || [];
        const parent = organisms.find(o => String(o.id) === parentId);
        
        if (parent) {
            // Parent ist noch lebendig - wähle ihn im Dropdown aus
            this.appController.selectOrganismById(parentId);
        } else {
            // Parent ist tot - navigiere zum Birth-Tick
            const organismDetails = this.appController.state.lastTickData.organismDetails || {};
            const parentDetails = organismDetails[parentId];
            if (parentDetails && parentDetails.birthTick !== undefined) {
                this.appController.navigateToTick(parentDetails.birthTick);
            }
        }
    }
}

// Export für globale Verfügbarkeit
window.SidebarBasicInfoView = SidebarBasicInfoView;
