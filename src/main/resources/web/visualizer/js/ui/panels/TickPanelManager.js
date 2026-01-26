/**
 * Manages the tick navigation panel.
 * The panel is always visible (not collapsible).
 *
 * @class TickPanelManager
 */
export class TickPanelManager {
    /**
     * Initializes the tick panel manager.
     * @param {object} options - Configuration options
     * @param {HTMLElement} options.panel - The panel element
     * @param {HTMLElement} options.multiplierWrapper - The wrapper for the multiplier input.
     * @param {HTMLElement} options.multiplierSuffix - The suffix for the multiplier input.
     */
    constructor({ panel, multiplierWrapper, multiplierSuffix }) {
        this.panel = panel;
        this.multiplierWrapper = multiplierWrapper;
        this.multiplierSuffix = multiplierSuffix;
    }

    /**
     * Updates the UI based on the sampling interval.
     * @param {number} interval - The sampling interval.
     */
    updateSamplingInfo(interval) {
        if (!this.multiplierWrapper || !this.multiplierSuffix) return;

        // Multiplier is always visible. The suffix indicates the step size.
        this.multiplierSuffix.textContent = `x${interval}`;
        this.multiplierWrapper.style.display = 'inline-flex';
    }
}
