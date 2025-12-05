import { ValueFormatter } from '../../utils/ValueFormatter.js';

/**
 * Renders the basic static and positional information of an organism in the sidebar.
 * This view displays details like parent ID, birth tick, IP, and DP coordinates,
 * and makes them clickable to navigate the simulation.
 *
 * @class SidebarBasicInfoView
 */
export class SidebarBasicInfoView {
    /**
     * Initializes the view.
     * @param {HTMLElement} root - The root element of the sidebar.
     * @param {EnvironmentGrid} [renderer=null] - The world renderer, used for centering the view on coordinates.
     * @param {AppController} [appController=null] - The main application controller, used for navigation.
     */
    constructor(root, renderer = null, appController = null) {
        this.root = root;
        this.renderer = renderer;
        this.appController = appController;
        this.previousState = null;
    }
    
    /**
     * Updates the view with the latest organism data.
     * It dynamically creates clickable links for parent ID, birth tick, IP, and DPs.
     * 
     * @param {object} staticInfo - Static organism info from the API, containing `parentId`, `birthTick`, etc.
     * @param {number} organismId - The ID of the currently selected organism.
     * @param {object|null} [state=null] - The dynamic runtime state of the organism, containing `ip`, `dataPointers`, etc.
     */
    update(staticInfo, organismId, state = null) {
        const el = this.root.querySelector('[data-section="basic"]');
        if (!el || !staticInfo) return;
        
        const parentId = staticInfo.parentId;
        
        // Check if parent is still alive (exists in current organisms)
        let isParentAlive = false;
        if (parentId != null && this.appController && this.appController.renderer) {
            const currentOrganisms = this.appController.renderer.currentOrganisms || [];
            isParentAlive = currentOrganisms.some(org => org.organismId === parentId);
        }
        
        // Format parent: clickable if alive, otherwise just text
        let parentDisplay = '-';
        if (parentId != null) {
            if (isParentAlive) {
                parentDisplay = `<span class="clickable-parent" data-parent-id="${parentId}">${parentId}</span>`;
            } else {
                parentDisplay = String(parentId);
            }
        }
        
        // Format birth: clickable to jump to birth tick
        const birthDisplay = `<span class="clickable-tick" data-tick="${staticInfo.birthTick}">${staticInfo.birthTick}</span>`;
        
        // Format IP and DPs as clickable links
        let ipDisplay = '-';
        let dpsDisplay = '-';
        
        if (state) {
            // Format IP
            if (state.ip && Array.isArray(state.ip) && state.ip.length >= 2) {
                const ipX = state.ip[0];
                const ipY = state.ip[1];
                const ipLink = `<span class="clickable-position" data-x="${ipX}" data-y="${ipY}">${ipX}|${ipY}</span>`;
                const dvArrow = state.dv ? ValueFormatter.formatDvAsArrow(state.dv) : '';
                ipDisplay = `${ipLink}${dvArrow}`;
            }
            
            // Format DPs
            if (state.dataPointers && Array.isArray(state.dataPointers) && state.dataPointers.length > 0) {
                const dpParts = [];
                for (let i = 0; i < state.dataPointers.length; i++) {
                    const dp = state.dataPointers[i];
                    if (dp && Array.isArray(dp) && dp.length >= 2) {
                        const dpX = dp[0];
                        const dpY = dp[1];
                        // Check if this is the active DP (activeDpIndex can be null/undefined, so use != null check)
                        const activeDpIndex = state.activeDpIndex != null ? state.activeDpIndex : -1;
                        const isActive = (i === activeDpIndex);
                        // Active DP gets brackets, but no special styling (not bold, not orange)
                        const dpText = isActive ? `[${dpX}|${dpY}]` : `${dpX}|${dpY}`;
                        dpParts.push(`<span class="clickable-position" data-x="${dpX}" data-y="${dpY}">${dpText}</span>`);
                    }
                }
                if (dpParts.length > 0) {
                    dpsDisplay = dpParts.join(' ');
                }
            }
        }
        
        const unchangeableInfo = [
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Parent:</span><span class="unchangeable-info-value">${parentDisplay}</span></div>`,
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">Birth:</span><span class="unchangeable-info-value">${birthDisplay}</span></div>`,
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">IP:</span><span class="unchangeable-info-value">${ipDisplay}</span></div>`,
            `<div class="unchangeable-info-item"><span class="unchangeable-info-label">DP:</span><span class="unchangeable-info-value">${dpsDisplay}</span></div>`
        ].join('');
        
        el.innerHTML = `<div class="unchangeable-info">${unchangeableInfo}</div>`;
        
        // Add click handlers for clickable positions (IP/DP)
        if (this.renderer) {
            const clickablePositions = el.querySelectorAll('.clickable-position');
            clickablePositions.forEach(span => {
                // Remove old listeners by cloning
                const newSpan = span.cloneNode(true);
                span.parentNode.replaceChild(newSpan, span);
                
                // Add new click listener
                newSpan.addEventListener('click', (e) => {
                    const x = parseInt(newSpan.getAttribute('data-x'), 10);
                    const y = parseInt(newSpan.getAttribute('data-y'), 10);
                    if (!isNaN(x) && !isNaN(y)) {
                        this.renderer.centerOn(x, y);
                    }
                });
            });
        }
        
        // Add click handler for clickable parent (select parent in dropdown)
        if (this.appController) {
            const clickableParent = el.querySelector('.clickable-parent');
            if (clickableParent) {
                clickableParent.addEventListener('click', (e) => {
                    const parentId = parseInt(clickableParent.getAttribute('data-parent-id'), 10);
                    if (!isNaN(parentId)) {
                        // Select parent in dropdown
                        const selector = document.getElementById('organism-selector');
                        if (selector) {
                            selector.value = String(parentId);
                            // Trigger change event to load parent details
                            selector.dispatchEvent(new Event('change'));
                        }
                    }
                });
            }
        }
        
        // Add click handler for clickable birth tick (navigate to birth tick)
        if (this.appController) {
            const clickableTick = el.querySelector('.clickable-tick');
            if (clickableTick) {
                clickableTick.addEventListener('click', (e) => {
                    const tick = parseInt(clickableTick.getAttribute('data-tick'), 10);
                    if (!isNaN(tick)) {
                        this.appController.navigateToTick(tick);
                    }
                });
            }
        }
        
        // Save current state for next comparison
        this.previousState = state ? { ...state } : null;
    }
}

