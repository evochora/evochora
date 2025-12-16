/**
 * Manages the tick navigation panel visibility and state.
 * Handles collapsing/expanding the panel and displaying tick info in collapsed state.
 *
 * @class TickPanelManager
 */
export class TickPanelManager {
    static STORAGE_KEY = 'evochora-tick-panel-visible';

    /**
     * Initializes the tick panel manager.
     * @param {object} options - Configuration options
     * @param {HTMLElement} options.panel - The expanded panel element
     * @param {HTMLElement} options.collapsed - The collapsed state element
     * @param {HTMLElement} options.hideBtn - Button to hide/collapse the panel
     * @param {HTMLElement} options.tickInput - The tick input element
     * @param {HTMLElement} options.tickTotal - The tick total suffix element
     * @param {HTMLElement} options.tickInfo - The tick info display in collapsed state
     */
    constructor({ panel, collapsed, hideBtn, tickInput, tickTotal, tickInfo }) {
        this.panel = panel;
        this.collapsed = collapsed;
        this.hideBtn = hideBtn;
        this.tickInput = tickInput;
        this.tickTotal = tickTotal;
        this.tickInfo = tickInfo;
        this.observer = null;

        this.init();
    }

    /**
     * Initializes the panel state and event listeners.
     * @private
     */
    init() {
        if (!this.panel || !this.collapsed || !this.hideBtn) {
            console.error('TickPanelManager: Required elements not found');
            return;
        }

        // Set up tick info observer
        this.setupTickInfoObserver();

        // Restore state from localStorage
        const stored = localStorage.getItem(TickPanelManager.STORAGE_KEY);
        const isVisible = stored !== 'false'; // Default to visible
        this.setVisible(isVisible);

        // Bind event listeners
        this.collapsed.addEventListener('click', (e) => {
            e.stopPropagation();
            this.setVisible(true);
        });

        this.hideBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            this.setVisible(false);
        });
    }

    /**
     * Sets up the MutationObserver to track tick changes.
     * @private
     */
    setupTickInfoObserver() {
        if (!this.tickTotal || !this.tickInfo) return;

        this.observer = new MutationObserver(() => this.updateTickInfo());
        this.observer.observe(this.tickTotal, {
            childList: true,
            characterData: true,
            subtree: true
        });

        if (this.tickInput) {
            this.tickInput.addEventListener('input', () => this.updateTickInfo());
            this.tickInput.addEventListener('change', () => this.updateTickInfo());
        }

        // Initial update
        this.updateTickInfo();
    }

    /**
     * Updates the tick info display in the collapsed state.
     * @private
     */
    updateTickInfo() {
        if (!this.tickInfo) return;
        const current = this.tickInput?.value || '0';
        const total = this.tickTotal?.textContent || '/N/A';
        this.tickInfo.textContent = current + total;
    }

    /**
     * Sets the visibility of the tick panel.
     * @param {boolean} visible - True to show expanded panel, false to show collapsed state
     */
    setVisible(visible) {
        const wasVisible = this.isVisible();
        this.panel.classList.toggle('hidden', !visible);
        this.collapsed.classList.toggle('hidden', visible);
        localStorage.setItem(TickPanelManager.STORAGE_KEY, visible ? 'true' : 'false');
        
        // When panel is expanded (was collapsed, now visible), focus and select the input field
        // This allows immediate typing without having to clear the current tick number
        if (visible && !wasVisible && this.tickInput) {
            // Use setTimeout to ensure the panel is visible before focusing
            setTimeout(() => {
                this.tickInput.focus();
                this.tickInput.select();
            }, 0);
        }
    }

    /**
     * Returns whether the panel is currently visible (expanded).
     * @returns {boolean}
     */
    isVisible() {
        return !this.panel.classList.contains('hidden');
    }

    /**
     * Cleans up resources (observer, event listeners).
     */
    destroy() {
        if (this.observer) {
            this.observer.disconnect();
            this.observer = null;
        }
    }
}

