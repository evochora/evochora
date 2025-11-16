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
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">ID:</span><span class="unchangeable-info-value">${organismId}</span></div>`,
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Parent:</span><span class="unchangeable-info-value">${parentDisplay}</span></div>`,
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Birth:</span><span class="unchangeable-info-value">${staticInfo.birthTick}</span></div>`,
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Program:</span><span class="unchangeable-info-value">${staticInfo.programId || 'N/A'}</span></div>`
        ].join('');
        
        // Changeable values (IP, DV, ER, DPs) in a box like old WebDebugger
        let changeableValues = '';
        if (state) {
            const ip = state.ip && Array.isArray(state.ip) && state.ip.length >= 2
                ? `${state.ip[0]}|${state.ip[1]}`
                : '?|?';
            const dv = state.dv && Array.isArray(state.dv) && state.dv.length >= 2
                ? `${state.dv[0]}|${state.dv[1]}`
                : '?|?';
            const er = state.energy || 0;
            
            // Format DPs: show all DPs, active one in brackets
            let dpStr = '';
            if (state.dataPointers && Array.isArray(state.dataPointers) && state.dataPointers.length > 0) {
                const activeDpIndex = state.activeDpIndex != null ? state.activeDpIndex : -1;
                const dpParts = [];
                for (let i = 0; i < state.dataPointers.length; i++) {
                    const dp = state.dataPointers[i];
                    if (dp && Array.isArray(dp) && dp.length >= 2) {
                        let value = `${dp[0]}|${dp[1]}`;
                        if (i === activeDpIndex) {
                            value = `[${value}]`;
                        }
                        dpParts.push(value);
                    }
                }
                if (dpParts.length > 0) {
                    dpStr = ` DP: ${dpParts.join(' ')}`;
                }
            }
            
            changeableValues = `IP: ${ip}     DV: ${dv}    ${dpStr}     ER: ${er}`;
        }
        
        el.innerHTML = `
            <div class="unchangeable-info">${unchangeableInfo}</div>
            ${changeableValues ? `<div class="code-view changeable-box">${changeableValues}</div>` : ''}
        `;
        
        // Save current state for next comparison
        this.previousState = state ? { ...state } : null;
    }
}

// Export for global availability
window.SidebarBasicInfoView = SidebarBasicInfoView;

