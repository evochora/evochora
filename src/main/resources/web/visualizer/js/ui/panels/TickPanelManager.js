/**
 * Manages the tick navigation panel including buttons, inputs, and keyboard shortcuts.
 * Uses callback injection for loose coupling (no direct controller reference).
 *
 * @class TickPanelManager
 */
export class TickPanelManager {
    static MULTIPLIER_STORAGE_PREFIX = 'evochora-multiplier-';

    /**
     * Initializes the tick panel manager.
     * @param {object} options - Configuration options
     * @param {HTMLElement} options.panel - The panel element
     * @param {HTMLElement} options.prevSmallBtn - Button for small step backward
     * @param {HTMLElement} options.nextSmallBtn - Button for small step forward
     * @param {HTMLElement} options.prevLargeBtn - Button for large step backward
     * @param {HTMLElement} options.nextLargeBtn - Button for large step forward
     * @param {HTMLElement} options.tickInput - Input field for tick number
     * @param {HTMLElement} options.tickSuffix - Element showing "/maxTick"
     * @param {HTMLElement} options.multiplierInput - Input field for step multiplier
     * @param {HTMLElement} options.multiplierWrapper - Wrapper for multiplier (for visibility)
     * @param {HTMLElement} options.multiplierSuffix - Element showing "x1" etc.
     * @param {Function} options.onNavigate - Callback when navigating: (targetTick) => void
     * @param {Function} options.getState - Callback to get current state: () => { currentTick, maxTick, runId, samplingInterval }
     */
    constructor({
        panel,
        prevSmallBtn,
        nextSmallBtn,
        prevLargeBtn,
        nextLargeBtn,
        tickInput,
        tickSuffix,
        multiplierInput,
        multiplierWrapper,
        multiplierSuffix,
        onNavigate,
        getState
    }) {
        this.panel = panel;
        this.elements = {
            prevSmall: prevSmallBtn,
            nextSmall: nextSmallBtn,
            prevLarge: prevLargeBtn,
            nextLarge: nextLargeBtn,
            tickInput: tickInput,
            tickSuffix: tickSuffix,
            multiplierInput: multiplierInput,
            multiplierWrapper: multiplierWrapper,
            multiplierSuffix: multiplierSuffix
        };
        this.onNavigate = onNavigate;
        this.getState = getState;

        // Key repeat state for held arrow keys
        this.keyRepeatTimeout = null;
        this.keyRepeatInterval = null;
        this.isKeyHeld = false;

        this.init();
    }

    /**
     * Initializes all event listeners.
     * @private
     */
    init() {
        const { prevSmall, nextSmall, prevLarge, nextLarge, tickInput, multiplierInput } = this.elements;

        // Button click events
        prevSmall?.addEventListener('click', () => this.navigateSmallStep('backward'));
        nextSmall?.addEventListener('click', () => this.navigateSmallStep('forward'));
        prevLarge?.addEventListener('click', () => this.navigateLargeStep('backward'));
        nextLarge?.addEventListener('click', () => this.navigateLargeStep('forward'));

        // Tick input events
        tickInput?.addEventListener('keydown', (e) => this.handleTickInputKeyDown(e));
        tickInput?.addEventListener('change', () => this.handleTickInputChange());
        tickInput?.addEventListener('click', () => tickInput.select());

        // Multiplier input events
        multiplierInput?.addEventListener('change', () => this.handleMultiplierChange());
        multiplierInput?.addEventListener('keyup', (e) => {
            if (!['ArrowUp', 'ArrowDown'].includes(e.key)) {
                this.handleMultiplierChange();
            }
        });
        multiplierInput?.addEventListener('keydown', (e) => this.handleMultiplierKeyDown(e));
        multiplierInput?.addEventListener('click', () => multiplierInput.select());

        // Global keyboard shortcuts
        document.addEventListener('keydown', (e) => this.handleGlobalKeyDown(e));
        document.addEventListener('keyup', (e) => {
            if (['ArrowLeft', 'ArrowRight'].includes(e.key)) {
                this.handleKeyRelease();
            }
        });

        // Reset keyboard events when window loses focus
        window.addEventListener('blur', () => this.handleKeyRelease());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Navigation Methods
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Navigates one small step (by sampling interval).
     * @param {('forward'|'backward')} direction - The direction to navigate.
     */
    navigateSmallStep(direction) {
        const state = this.getState();
        const step = state.samplingInterval || 1;
        const targetTick = direction === 'forward'
            ? state.currentTick + step
            : state.currentTick - step;
        this.onNavigate(targetTick);
    }

    /**
     * Navigates a large step (by multiplier × sampling interval).
     * @param {('forward'|'backward')} direction - The direction to navigate.
     */
    navigateLargeStep(direction) {
        const state = this.getState();
        const samplingInterval = state.samplingInterval || 1;
        const multiplier = this.getMultiplier();
        const largeStep = multiplier * samplingInterval;
        const targetTick = direction === 'forward'
            ? state.currentTick + largeStep
            : state.currentTick - largeStep;
        this.onNavigate(targetTick);
    }

    /**
     * Gets the current multiplier value.
     * @returns {number}
     */
    getMultiplier() {
        const parsed = parseInt(this.elements.multiplierInput?.value, 10);
        return (!isNaN(parsed) && parsed > 0) ? parsed : this.getDefaultMultiplier();
    }

    /**
     * Calculates the default multiplier based on sampling interval.
     * Target: multiplier × samplingInterval ≈ 10,000 ticks
     * Result is always a power of 10 (1, 10, 100, 1000, 10000, etc.)
     * @returns {number}
     */
    getDefaultMultiplier() {
        const state = this.getState();
        const samplingInterval = state.samplingInterval || 1;
        const targetStep = 10000;
        const rawMultiplier = targetStep / samplingInterval;

        // Round to nearest power of 10
        const exponent = Math.round(Math.log10(rawMultiplier));
        return Math.pow(10, Math.max(0, exponent)); // Minimum 1
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Input Event Handlers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Handles keydown events on the tick input field.
     * @param {KeyboardEvent} e
     * @private
     */
    handleTickInputKeyDown(e) {
        if (e.key === 'Enter') {
            e.preventDefault();
            this.handleTickInputChange();
            setTimeout(() => this.elements.tickInput?.select(), 0);
        } else if (e.key === 'Escape') {
            e.preventDefault();
            this.elements.tickInput?.blur();
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            this.navigateLargeStep('forward');
        } else if (e.key === 'ArrowDown') {
            e.preventDefault();
            this.navigateLargeStep('backward');
        }
        // ArrowLeft/Right: default cursor movement
    }

    /**
     * Handles the change event for the tick input.
     * @private
     */
    handleTickInputChange() {
        const value = parseInt(this.elements.tickInput?.value, 10);
        if (!Number.isNaN(value)) {
            this.onNavigate(value);
        }
    }

    /**
     * Handles keydown events on the multiplier input field.
     * @param {KeyboardEvent} e
     * @private
     */
    handleMultiplierKeyDown(e) {
        if (e.key === 'Escape') {
            e.preventDefault();
            this.elements.multiplierInput?.blur();
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            this.navigateLargeStep('forward');
        } else if (e.key === 'ArrowDown') {
            e.preventDefault();
            this.navigateLargeStep('backward');
        }
    }

    /**
     * Saves the multiplier to localStorage and updates UI.
     * @private
     */
    handleMultiplierChange() {
        const state = this.getState();
        if (state.runId) {
            const key = TickPanelManager.MULTIPLIER_STORAGE_PREFIX + state.runId;
            localStorage.setItem(key, this.elements.multiplierInput?.value || '100');
        }
        this.updateTooltips();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Global Keyboard Shortcuts
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Handles global keydown events for arrow key navigation.
     * @param {KeyboardEvent} e
     * @private
     */
    handleGlobalKeyDown(e) {
        // Skip if any text/number input is focused (except our own tick/multiplier inputs)
        const activeEl = document.activeElement;
        if (activeEl?.matches('input[type="text"], input[type="number"]')) {
            // Allow tick panel inputs to handle their own keys
            if (activeEl !== this.elements.tickInput && activeEl !== this.elements.multiplierInput) {
                return;
            }
            // Our inputs handle arrow keys themselves
            return;
        }

        if (['ArrowRight', 'ArrowLeft', 'ArrowUp', 'ArrowDown'].includes(e.key)) {
            e.preventDefault();
            this.handleNavigationKey(e.key);
        }
    }

    /**
     * Routes a navigation key to the appropriate action.
     * @param {string} key
     * @private
     */
    handleNavigationKey(key) {
        switch (key) {
            case 'ArrowRight':
                this.handleKeyPress('forward');
                break;
            case 'ArrowLeft':
                this.handleKeyPress('backward');
                break;
            case 'ArrowUp':
                this.navigateLargeStep('forward');
                break;
            case 'ArrowDown':
                this.navigateLargeStep('backward');
                break;
        }
    }

    /**
     * Handles the initial press of a left/right key (with repeat support).
     * @param {('forward'|'backward')} direction
     * @private
     */
    handleKeyPress(direction) {
        if (this.isKeyHeld) return;
        this.isKeyHeld = true;
        this.navigateSmallStep(direction);

        this.keyRepeatTimeout = setTimeout(() => {
            this.keyRepeatInterval = setInterval(() => {
                this.navigateSmallStep(direction);
            }, 100);
        }, 300);
    }

    /**
     * Handles the release of a navigation key.
     * @private
     */
    handleKeyRelease() {
        this.isKeyHeld = false;
        clearTimeout(this.keyRepeatTimeout);
        clearInterval(this.keyRepeatInterval);
        this.keyRepeatTimeout = null;
        this.keyRepeatInterval = null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // UI Update Methods (called by controller)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Updates the tick display (input value and max tick suffix).
     * @param {number} currentTick
     * @param {number|null} maxTick
     */
    updateTickDisplay(currentTick, maxTick) {
        const { tickInput, tickSuffix } = this.elements;

        if (tickInput) {
            tickInput.value = String(currentTick || 0);
            if (typeof maxTick === 'number' && maxTick > 0) {
                tickInput.max = String(Math.max(0, maxTick));
            }
        }

        if (tickSuffix) {
            tickSuffix.textContent = '/' + this.formatNumber(maxTick);
        }
    }

    /**
     * Updates the sampling interval display and shows the multiplier.
     * @param {number} samplingInterval
     */
    updateSamplingInfo(samplingInterval) {
        const { multiplierWrapper, multiplierSuffix } = this.elements;

        if (multiplierWrapper) {
            multiplierWrapper.style.display = 'inline-flex';
        }
        if (multiplierSuffix) {
            multiplierSuffix.textContent = `x${samplingInterval}`;
        }

        this.updateTooltips();
    }

    /**
     * Loads the multiplier from localStorage for a specific run.
     * Uses smart default: multiplier × samplingInterval ≈ 10,000 ticks.
     * @param {string} runId
     */
    loadMultiplierForRun(runId) {
        let value = null;

        // Try to load from localStorage
        if (runId) {
            const key = TickPanelManager.MULTIPLIER_STORAGE_PREFIX + runId;
            const stored = localStorage.getItem(key);
            if (stored && !isNaN(parseInt(stored, 10))) {
                value = stored;
            }
        }

        // Use smart default if nothing stored
        if (value === null) {
            value = String(this.getDefaultMultiplier());
        }

        if (this.elements.multiplierInput) {
            this.elements.multiplierInput.value = value;
        }
        this.updateTooltips();
    }

    /**
     * Updates button tooltips with current step sizes.
     */
    updateTooltips() {
        const state = this.getState();
        const samplingInterval = state.samplingInterval || 1;
        const multiplier = this.getMultiplier();
        const largeStep = multiplier * samplingInterval;

        const { prevSmall, nextSmall, prevLarge, nextLarge } = this.elements;

        if (prevSmall) prevSmall.dataset.tooltip = `Step -${this.formatNumber(samplingInterval)} ticks (←)`;
        if (nextSmall) nextSmall.dataset.tooltip = `Step +${this.formatNumber(samplingInterval)} ticks (→)`;
        if (prevLarge) prevLarge.dataset.tooltip = `Step -${this.formatNumber(largeStep)} ticks (↓)`;
        if (nextLarge) nextLarge.dataset.tooltip = `Step +${this.formatNumber(largeStep)} ticks (↑)`;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Utility Methods
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Formats a number in compact form (e.g., 10k, 1.5M).
     * @param {number|null} num
     * @returns {string}
     */
    formatNumber(num) {
        if (num === null || num === undefined) return 'N/A';
        if (num < 1000) return String(num);
        if (num < 1000000) {
            return (num / 1000).toFixed(num % 1000 !== 0 ? 1 : 0) + 'k';
        }
        return (num / 1000000).toFixed(num % 1000000 !== 0 ? 2 : 0) + 'M';
    }
}
