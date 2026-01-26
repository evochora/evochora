/**
 * Manages the header bar component, including tick navigation controls,
 * the organism selector, and associated keyboard shortcuts.
 *
 * @class HeaderbarView
 */
export class HeaderbarView {
    /**
     * Initializes the HeaderbarView and sets up all event listeners.
     * @param {AppController} controller - The main application controller.
     */
    constructor(controller) {
        this.controller = controller;
        this.elements = {
            prevSmall: document.getElementById('btn-prev-small'),
            nextSmall: document.getElementById('btn-next-small'),
            prevLarge: document.getElementById('btn-prev-large'),
            nextLarge: document.getElementById('btn-next-large'),
            tickInput: document.getElementById('tick-input'),
            largeStepInput: document.getElementById('large-step-multiplier'),
        };
        
        // Debouncing for keyboard navigation
        this.keyRepeatTimeout = null;
        this.keyRepeatInterval = null;
        this.isKeyHeld = false;
        
        // Button event listeners
        this.elements.prevSmall.addEventListener('click', () => this.navigateInDirection('backward'));
        this.elements.nextSmall.addEventListener('click', () => this.navigateInDirection('forward'));
        this.elements.prevLarge.addEventListener('click', () => this.navigateLargeStep('backward'));
        this.elements.nextLarge.addEventListener('click', () => this.navigateLargeStep('forward'));
        
        // Input field listeners
        this.elements.tickInput.addEventListener('keydown', (e) => this.handleTickInputKeyDown(e));
        this.elements.tickInput.addEventListener('change', () => this.handleTickInputChange());
        this.elements.tickInput.addEventListener('click', () => this.elements.tickInput.select());
        this.elements.largeStepInput.addEventListener('change', () => this.handleMultiplierChange());
        this.elements.largeStepInput.addEventListener('keyup', (e) => {
            // Don't trigger on arrow keys (handled by keydown)
            if (!['ArrowUp', 'ArrowDown'].includes(e.key)) {
                this.handleMultiplierChange();
            }
        });
        this.elements.largeStepInput.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                e.preventDefault();
                this.elements.largeStepInput.blur();
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                const current = parseInt(this.elements.largeStepInput.value, 10) || 100;
                this.elements.largeStepInput.value = current * 10;
                this.handleMultiplierChange();
            } else if (e.key === 'ArrowDown') {
                e.preventDefault();
                const current = parseInt(this.elements.largeStepInput.value, 10) || 100;
                this.elements.largeStepInput.value = Math.max(1, Math.floor(current / 10));
                this.handleMultiplierChange();
            }
        });
        this.elements.largeStepInput.addEventListener('click', () => this.elements.largeStepInput.select());

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

    /**
     * Loads the multiplier for a specific runId from localStorage.
     * @param {string} runId The ID of the run.
     */
    loadMultiplierForRun(runId) {
        const defaultValue = '100';
        let value = defaultValue;
        if (runId) {
            const key = `evochora-multiplier-${runId}`;
            const storedValue = localStorage.getItem(key);
            if (storedValue && !isNaN(parseInt(storedValue, 10))) {
                value = storedValue;
            }
        }
        this.elements.largeStepInput.value = value;
        this.updateTooltips(); // Ensure tooltips are correct after loading
    }

    /**
     * Saves the multiplier to localStorage and updates tooltips.
     * @private
     */
    handleMultiplierChange() {
        const runId = this.controller.state.runId;
        if (runId) {
            localStorage.setItem(`evochora-multiplier-${runId}`, this.elements.largeStepInput.value);
        }
        this.updateTooltips();
    }

    /**
     * Formats a large number into a compact representation (e.g., 10k, 1.5M).
     * @param {number} num The number to format.
     * @returns {string} The formatted string.
     */
    formatNumber(num) {
        if (num === null || num === undefined) return 'N/A';
        if (num < 1000) return String(num);
        if (num < 1000000) {
            return (num / 1000).toFixed(num % 1000 !== 0 ? 1 : 0) + 'k';
        }
        return (num / 1000000).toFixed(num % 1000000 !== 0 ? 2 : 0) + 'M';
    }

    /**
     * Updates tooltips for navigation buttons based on current step sizes.
     */
    updateTooltips() {
        const samplingInterval = this.controller.state.metadata?.samplingInterval || 1;
        const multiplier = parseInt(this.elements.largeStepInput.value, 10) || 100;
        const largeStep = multiplier * samplingInterval;

        this.elements.prevSmall.dataset.tooltip = `Step -${this.formatNumber(samplingInterval)} ticks (←)`;
        this.elements.nextSmall.dataset.tooltip = `Step +${this.formatNumber(samplingInterval)} ticks (→)`;
        this.elements.prevLarge.dataset.tooltip = `Step -${this.formatNumber(largeStep)} ticks (↓)`;
        this.elements.nextLarge.dataset.tooltip = `Step +${this.formatNumber(largeStep)} ticks (↑)`;
    }

    /**
     * Handles keydown events on the tick input field.
     * Arrow keys use default behavior (cursor movement).
     * @param {KeyboardEvent} e The keyboard event.
     */
    handleTickInputKeyDown(e) {
        if (e.key === 'Enter') {
            e.preventDefault();
            this.handleTickInputChange();
            setTimeout(() => this.elements.tickInput.select(), 0);
        } else if (e.key === 'Escape') {
            e.preventDefault();
            this.elements.tickInput.blur();
        }
        // Arrow keys: default behavior (cursor movement in input)
    }

    /**
     * Handles the change event for the tick input field.
     */
    handleTickInputChange() {
        const v = parseInt(this.elements.tickInput.value, 10);
        if (!Number.isNaN(v)) {
            this.controller.navigateToTick(v);
        }
    }

    /**
     * Central handler for global keydown events, routing to the correct action.
     * @param {KeyboardEvent} e The keyboard event.
     * @private
     */
    handleGlobalKeyDown(e) {
        // Ignore all navigation shortcuts if any input is focused
        if (document.activeElement.matches('input[type="text"], input[type="number"]')) {
            return;
        }

        if (['ArrowRight', 'ArrowLeft', 'ArrowUp', 'ArrowDown'].includes(e.key)) {
            e.preventDefault();
            this.handleNavigationKey(e.key);
        }
    }

    /**
     * Handles a navigation key press (arrow keys).
     * @param {string} key - The key that was pressed.
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
     * Updates the displayed tick number in the input field and the total tick suffix.
     * @param {number} currentTick - The current tick number to display.
     * @param {number|null} maxTick - The maximum available tick, or null if not yet known.
     */
    updateTickDisplay(currentTick, maxTick) {
        const input = this.elements.tickInput;
        const suffix = document.getElementById('tick-total-suffix');
        
        if (input) {
            input.value = String(currentTick || 0);
            if (typeof maxTick === 'number' && maxTick > 0) {
                input.max = String(Math.max(0, maxTick));
            }
        }
        
        if (suffix) {
            suffix.textContent = '/' + this.formatNumber(maxTick);
        }
    }
    
    /**
     * Handles the initial press of a navigation key.
     * @param {('forward'|'backward')} direction - The direction to navigate.
     * @private
     */
    handleKeyPress(direction) {
        if (this.isKeyHeld) return;
        this.isKeyHeld = true;
        this.navigateInDirection(direction);
        
        this.keyRepeatTimeout = setTimeout(() => {
            this.keyRepeatInterval = setInterval(() => {
                this.navigateInDirection(direction);
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
    
    /**
     * Navigates one small step.
     * @param {('forward'|'backward')} direction - The direction to navigate.
     * @private
     */
    navigateInDirection(direction) {
        const step = this.controller.state.metadata?.samplingInterval || 1;
        const currentTick = this.controller.state.currentTick;
        const targetTick = direction === 'forward' ? currentTick + step : currentTick - step;
        this.controller.navigateToTick(targetTick);
    }

    /**
     * Navigates a large step.
     * @param {('forward'|'backward')} direction - The direction to navigate.
     * @private
     */
    navigateLargeStep(direction) {
        const samplingInterval = this.controller.state.metadata?.samplingInterval || 1;
        let multiplier = 100;
        const parsed = parseInt(this.elements.largeStepInput.value, 10);
        if (!isNaN(parsed) && parsed > 0) {
            multiplier = parsed;
        }
        
        const largeStep = multiplier * samplingInterval;
        const currentTick = this.controller.state.currentTick;
        const targetTick = direction === 'forward' ? currentTick + largeStep : currentTick - largeStep;
        this.controller.navigateToTick(targetTick);
    }
}
