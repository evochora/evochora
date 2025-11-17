/**
 * Renders basic static organism information in the sidebar.
 */
class SidebarBasicInfoView {
    constructor(root) {
        this.root = root;
        this.previousState = null;
    }
    
    /**
     * Updates the basic info section with static organism data and hot-path values (IP, DV, ER).
     * 
     * @param {Object} staticInfo - Static organism info:
     *   { parentId, birthTick, programId }
     * @param {number} organismId - Organism ID
     * @param {Object} state - Runtime state with IP, DV, ER (optional, for changeable box)
     */
    update(staticInfo, organismId, state = null) {
        const el = this.root.querySelector('[data-section="basic"]');
        if (!el || !staticInfo) return;
        
        const parentId = staticInfo.parentId;
        const parentDisplay = parentId != null 
            ? String(parentId)
            : 'N/A';
        
        const unchangeableInfo = [
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Parent:</span><span class="unchangeable-info-value">${parentDisplay}</span></div>`,
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Birth:</span><span class="unchangeable-info-value">${staticInfo.birthTick}</span></div>`,
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Program:</span><span class="unchangeable-info-value">${staticInfo.programId || 'N/A'}</span></div>`
        ].join('');
        
        // Basic view (changeable-box) is now shown in the dropdown, not here
        // Only show static info
        el.innerHTML = `<div class="unchangeable-info">${unchangeableInfo}</div>`;
        
        // Save current state for next comparison
        this.previousState = state ? { ...state } : null;
    }
}

// Export for global availability
window.SidebarBasicInfoView = SidebarBasicInfoView;

