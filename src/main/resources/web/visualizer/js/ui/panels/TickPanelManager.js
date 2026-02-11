/**
 * Manages the timeline panel with interactive canvas track, tick input, and keyboard shortcuts.
 * The canvas displays sampled tick marks, a current-tick marker, and a hover preview.
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
     * @param {HTMLElement} options.tickInput - Input field for tick number
     * @param {HTMLElement} options.tickSuffix - Element showing "/maxTick"
     * @param {HTMLElement} options.prevLargeBtn - Button for large step backward
     * @param {HTMLElement} options.prevSmallBtn - Button for small step backward
     * @param {HTMLElement} options.nextSmallBtn - Button for small step forward
     * @param {HTMLElement} options.nextLargeBtn - Button for large step forward
     * @param {HTMLElement} options.trackContainer - The timeline track container
     * @param {HTMLCanvasElement} options.trackCanvas - The timeline canvas element
     * @param {HTMLElement} options.tooltip - The hover tooltip element
     * @param {HTMLElement} options.multiplierInput - Input field for step multiplier
     * @param {HTMLElement} options.multiplierWrapper - Wrapper for multiplier (for visibility)
     * @param {HTMLElement} options.multiplierSuffix - Element showing "x1" etc.
     * @param {Function} options.onNavigate - Callback when navigating: (targetTick) => void
     * @param {Function} options.getState - Callback to get current state: () => { currentTick, maxTick, runId, samplingInterval }
     */
    constructor({
        panel,
        tickInput,
        tickSuffix,
        prevLargeBtn,
        prevSmallBtn,
        nextSmallBtn,
        nextLargeBtn,
        trackContainer,
        trackCanvas,
        tooltip,
        multiplierInput,
        multiplierWrapper,
        multiplierSuffix,
        onNavigate,
        getState
    }) {
        this.panel = panel;
        this.elements = {
            tickInput,
            tickSuffix,
            prevLargeBtn,
            prevSmallBtn,
            nextSmallBtn,
            nextLargeBtn,
            trackContainer,
            trackCanvas,
            tooltip,
            multiplierInput,
            multiplierWrapper,
            multiplierSuffix
        };
        this.onNavigate = onNavigate;
        this.getState = getState;

        // Key repeat state for held arrow keys
        this.keyRepeatTimeout = null;
        this.keyRepeatInterval = null;
        this.isKeyHeld = false;

        // Debounced navigation for keyboard input
        this._navigateDebounceTimer = null;
        this._pendingTick = null;

        // Timeline hover state
        this._hoverTick = null;

        this.init();
    }

    /**
     * Initializes all event listeners.
     * @private
     */
    init() {
        const { tickInput, multiplierInput, prevLargeBtn, prevSmallBtn, nextSmallBtn, nextLargeBtn } = this.elements;

        // Navigation button events
        prevLargeBtn?.addEventListener('click', () => this.navigateLargeStep('backward'));
        prevSmallBtn?.addEventListener('click', () => this.navigateSmallStep('backward'));
        nextSmallBtn?.addEventListener('click', () => this.navigateSmallStep('forward'));
        nextLargeBtn?.addEventListener('click', () => this.navigateLargeStep('forward'));

        // Tick input events
        tickInput?.addEventListener('keydown', (e) => this.handleTickInputKeyDown(e));
        tickInput?.addEventListener('keyup', (e) => {
            if (['ArrowUp', 'ArrowDown'].includes(e.key)) this.handleKeyRelease();
        });
        tickInput?.addEventListener('change', () => this.handleTickInputChange());
        tickInput?.addEventListener('click', () => tickInput.select());

        // Multiplier input events
        multiplierInput?.addEventListener('change', () => this.handleMultiplierChange());
        multiplierInput?.addEventListener('keyup', (e) => {
            if (['ArrowUp', 'ArrowDown'].includes(e.key)) {
                this.handleKeyRelease();
            } else {
                this.handleMultiplierChange();
            }
        });
        multiplierInput?.addEventListener('keydown', (e) => this.handleMultiplierKeyDown(e));
        multiplierInput?.addEventListener('click', () => multiplierInput.select());

        // Global keyboard shortcuts
        document.addEventListener('keydown', (e) => this.handleGlobalKeyDown(e));
        document.addEventListener('keyup', (e) => {
            if (['ArrowUp', 'ArrowDown'].includes(e.key)) {
                this.handleKeyRelease();
            }
        });

        // Reset keyboard events when window loses focus
        window.addEventListener('blur', () => this.handleKeyRelease());

        // Initialize timeline canvas
        this._initTimeline();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Timeline Canvas
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Initializes the timeline canvas, DPI scaling, mouse events, and resize observer.
     * @private
     */
    _initTimeline() {
        const { trackContainer, trackCanvas, tooltip } = this.elements;
        if (!trackContainer || !trackCanvas) return;

        this._ctx = trackCanvas.getContext('2d');

        // Mouse events on track container
        trackContainer.addEventListener('mousemove', (e) => this._handleTimelineMouseMove(e));
        trackContainer.addEventListener('click', (e) => this._handleTimelineClick(e));
        trackContainer.addEventListener('mouseleave', () => this._handleTimelineMouseLeave());

        // Resize observer to keep canvas dimensions in sync
        this._resizeObserver = new ResizeObserver(() => {
            this._syncCanvasSize();
            this._renderTimeline();
        });
        this._resizeObserver.observe(trackContainer);

        // Initial sizing
        this._syncCanvasSize();
    }

    /**
     * Synchronizes the canvas pixel dimensions with its CSS display size, accounting for DPI.
     * @private
     */
    _syncCanvasSize() {
        const { trackCanvas, trackContainer } = this.elements;
        if (!trackCanvas || !trackContainer) return;

        const dpr = window.devicePixelRatio || 1;
        const rect = trackContainer.getBoundingClientRect();
        trackCanvas.width = Math.round(rect.width * dpr);
        trackCanvas.height = Math.round(rect.height * dpr);
        this._ctx?.scale(dpr, dpr);
        this._canvasWidth = rect.width;
        this._canvasHeight = rect.height;
    }

    /**
     * Renders the timeline canvas: background, sampled tick marks, and current-tick marker.
     * @private
     */
    _renderTimeline() {
        const ctx = this._ctx;
        if (!ctx) return;

        const w = this._canvasWidth || 0;
        const h = this._canvasHeight || 0;
        if (w === 0 || h === 0) return;

        const state = this.getState();
        const maxTick = state.maxTick;
        const currentTick = this._pendingTick !== null ? this._pendingTick : (state.currentTick || 0);
        const samplingInterval = state.samplingInterval || 1;

        // Clear and fill background
        ctx.clearRect(0, 0, w, h);
        ctx.fillStyle = '#1a1a24';
        ctx.fillRect(0, 0, w, h);

        if (!maxTick || maxTick <= 0) return;

        // Draw sampled tick marks
        const numSamples = Math.floor(maxTick / samplingInterval) + 1;
        const pixelsPerSample = w / Math.max(1, numSamples - 1);

        if (pixelsPerSample >= 3 && numSamples <= 10000) {
            // Individual marks visible
            ctx.fillStyle = 'rgba(255, 255, 255, 0.25)';
            for (let i = 0; i < numSamples; i++) {
                const tick = i * samplingInterval;
                const x = this._tickToPosition(tick);
                ctx.fillRect(Math.round(x), 0, 1, h);
            }
        } else {
            // Marks too dense — render as continuous filled area
            ctx.fillStyle = 'rgba(255, 255, 255, 0.12)';
            ctx.fillRect(0, 0, w, h);
        }

        // Hover marker (behind progress fill)
        if (this._hoverTick !== null) {
            const hx = this._tickToPosition(this._hoverTick);
            ctx.fillStyle = 'rgba(255, 255, 255, 0.35)';
            ctx.fillRect(Math.round(hx) - 1, 0, 2, h);
        }

        // Progress fill from left edge to current tick
        const cx = this._tickToPosition(currentTick);
        ctx.fillStyle = 'rgba(74, 158, 255, 0.35)';
        ctx.fillRect(0, 0, Math.round(cx), h);

        // Current tick edge marker (thin bright line at progress boundary)
        ctx.fillStyle = '#4a9eff';
        ctx.fillRect(Math.max(0, Math.round(cx) - 1), 0, 2, h);
    }

    /**
     * Handles mousemove over the timeline track: computes snapped tick and shows tooltip.
     * @param {MouseEvent} e
     * @private
     */
    _handleTimelineMouseMove(e) {
        const { trackContainer, tooltip } = this.elements;
        if (!trackContainer) return;

        const rect = trackContainer.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const rawTick = this._positionToTick(x);
        const snapped = this._snapToSampledTick(rawTick);

        this._hoverTick = snapped;
        this._renderTimeline();

        // Position and show tooltip
        if (tooltip) {
            const snappedX = this._tickToPosition(snapped);
            tooltip.textContent = String(snapped);
            tooltip.style.left = `${snappedX}px`;
            tooltip.classList.add('visible');
        }
    }

    /**
     * Handles click on the timeline track: navigates to the snapped tick.
     * @param {MouseEvent} e
     * @private
     */
    _handleTimelineClick(e) {
        const { trackContainer } = this.elements;
        if (!trackContainer) return;

        const rect = trackContainer.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const rawTick = this._positionToTick(x);
        const snapped = this._snapToSampledTick(rawTick);

        this.onNavigate(snapped);
    }

    /**
     * Handles mouseleave from the timeline track: hides tooltip and hover marker.
     * @private
     */
    _handleTimelineMouseLeave() {
        this._hoverTick = null;
        this._renderTimeline();

        const { tooltip } = this.elements;
        if (tooltip) {
            tooltip.classList.remove('visible');
        }
    }

    /**
     * Converts a pixel x-position on the canvas to a tick value.
     * @param {number} x - The x position in CSS pixels relative to canvas.
     * @returns {number} The corresponding tick value.
     * @private
     */
    _positionToTick(x) {
        const w = this._canvasWidth || 1;
        const state = this.getState();
        const maxTick = state.maxTick || 0;
        return (x / w) * maxTick;
    }

    /**
     * Converts a tick value to a pixel x-position on the canvas.
     * @param {number} tick - The tick value.
     * @returns {number} The x position in CSS pixels.
     * @private
     */
    _tickToPosition(tick) {
        const w = this._canvasWidth || 1;
        const state = this.getState();
        const maxTick = state.maxTick || 1;
        return (tick / maxTick) * w;
    }

    /**
     * Snaps a tick value to the nearest sampled tick, clamped to [0, maxTick].
     * @param {number} tick - The raw tick value.
     * @returns {number} The nearest sampled tick.
     * @private
     */
    _snapToSampledTick(tick) {
        const state = this.getState();
        const samplingInterval = state.samplingInterval || 1;
        const maxTick = state.maxTick || 0;
        const snapped = Math.round(tick / samplingInterval) * samplingInterval;
        return Math.max(0, Math.min(maxTick, snapped));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Navigation Methods
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Navigates one small step (by sampling interval).
     * @param {('forward'|'backward')} direction - The direction to navigate.
     * @param {boolean} [debounce=false] - If true, debounce the data load.
     */
    navigateSmallStep(direction, debounce = false) {
        const state = this.getState();
        const step = state.samplingInterval || 1;
        const baseTick = this._pendingTick !== null ? this._pendingTick : state.currentTick;
        const targetTick = direction === 'forward' ? baseTick + step : baseTick - step;
        if (debounce) {
            this._navigateDebounced(targetTick);
        } else {
            this.onNavigate(targetTick);
        }
    }

    /**
     * Navigates a large step (by multiplier × sampling interval).
     * @param {('forward'|'backward')} direction - The direction to navigate.
     * @param {boolean} [debounce=false] - If true, debounce the data load.
     */
    navigateLargeStep(direction, debounce = false) {
        const state = this.getState();
        const samplingInterval = state.samplingInterval || 1;
        const multiplier = this.getMultiplier();
        const largeStep = multiplier * samplingInterval;
        const baseTick = this._pendingTick !== null ? this._pendingTick : state.currentTick;
        const targetTick = direction === 'forward' ? baseTick + largeStep : baseTick - largeStep;
        if (debounce) {
            this._navigateDebounced(targetTick);
        } else {
            this.onNavigate(targetTick);
        }
    }

    /**
     * Updates the timeline marker and tick display immediately, but debounces the actual data load.
     * @param {number} targetTick - The target tick to navigate to.
     * @private
     */
    _navigateDebounced(targetTick) {
        const state = this.getState();
        const samplingInterval = state.samplingInterval || 1;
        const maxTick = state.maxTick || 0;

        // Clamp and snap
        let clamped = Math.max(0, targetTick);
        if (samplingInterval > 1) {
            clamped = Math.round(clamped / samplingInterval) * samplingInterval;
        }
        if (maxTick > 0) {
            clamped = Math.min(clamped, maxTick);
        }

        this._pendingTick = clamped;

        // Immediate visual feedback: update tick display and timeline marker
        this.updateTickDisplay(clamped, maxTick);

        // Debounce the actual data load
        clearTimeout(this._navigateDebounceTimer);
        this._navigateDebounceTimer = setTimeout(() => {
            this._flushPendingNavigation();
        }, 200);
    }

    /**
     * Immediately triggers navigation to the pending tick and clears the debounce state.
     * @private
     */
    _flushPendingNavigation() {
        clearTimeout(this._navigateDebounceTimer);
        this._navigateDebounceTimer = null;
        if (this._pendingTick !== null) {
            const tick = this._pendingTick;
            this._pendingTick = null;
            this.onNavigate(tick);
        }
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
     * Target: multiplier × samplingInterval ≈ 100,000 ticks
     * Result is always a power of 10 (1, 10, 100, 1000, 10000, etc.)
     * @returns {number}
     */
    getDefaultMultiplier() {
        const state = this.getState();
        const samplingInterval = state.samplingInterval || 1;
        const targetStep = 100000;
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
            this.handleKeyPress('forward');
        } else if (e.key === 'ArrowDown') {
            e.preventDefault();
            this.handleKeyPress('backward');
        } else if (e.key === 'PageUp') {
            e.preventDefault();
            this.navigateLargeStep('forward', true);
        } else if (e.key === 'PageDown') {
            e.preventDefault();
            this.navigateLargeStep('backward', true);
        }
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
            this.handleKeyPress('forward');
        } else if (e.key === 'ArrowDown') {
            e.preventDefault();
            this.handleKeyPress('backward');
        } else if (e.key === 'PageUp') {
            e.preventDefault();
            this.navigateLargeStep('forward', true);
        } else if (e.key === 'PageDown') {
            e.preventDefault();
            this.navigateLargeStep('backward', true);
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
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Global Keyboard Shortcuts
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Handles global keydown events for tick navigation.
     * Up/Down = single step, PageUp/PageDown = large step.
     * @param {KeyboardEvent} e
     * @private
     */
    handleGlobalKeyDown(e) {
        // Skip if any text/number input is focused
        const activeEl = document.activeElement;
        if (activeEl?.matches('input[type="text"], input[type="number"]')) {
            return;
        }

        if (e.key === 'ArrowUp') {
            e.preventDefault();
            this.handleKeyPress('forward');
        } else if (e.key === 'ArrowDown') {
            e.preventDefault();
            this.handleKeyPress('backward');
        } else if (e.key === 'PageUp') {
            e.preventDefault();
            this.navigateLargeStep('forward', true);
        } else if (e.key === 'PageDown') {
            e.preventDefault();
            this.navigateLargeStep('backward', true);
        }
    }

    /**
     * Handles the initial press of an up/down key (with repeat support).
     * @param {('forward'|'backward')} direction
     * @private
     */
    handleKeyPress(direction) {
        if (this.isKeyHeld) return;
        this.isKeyHeld = true;
        this.navigateSmallStep(direction, true);

        this.keyRepeatTimeout = setTimeout(() => {
            this.keyRepeatInterval = setInterval(() => {
                this.navigateSmallStep(direction, true);
            }, 100);
        }, 300);
    }

    /**
     * Handles the release of a navigation key.
     * Flushes any pending debounced navigation immediately.
     * @private
     */
    handleKeyRelease() {
        this.isKeyHeld = false;
        clearTimeout(this.keyRepeatTimeout);
        clearInterval(this.keyRepeatInterval);
        this.keyRepeatTimeout = null;
        this.keyRepeatInterval = null;
        this._flushPendingNavigation();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // UI Update Methods (called by controller)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Updates the tick display (input value and max tick suffix) and re-renders the timeline.
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

        this._renderTimeline();
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

        this._renderTimeline();
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
    }

    /**
     * Updates navigation button tooltips with current multiplier and sampling interval.
     */
    updateTooltips() {
        const { prevLargeBtn, prevSmallBtn, nextSmallBtn, nextLargeBtn } = this.elements;
        const state = this.getState();
        const interval = state.samplingInterval || 1;
        const multiplier = this.getMultiplier();
        const largeStep = multiplier * interval;

        if (prevSmallBtn) prevSmallBtn.title = `Previous sample: −${interval} (↓)`;
        if (nextSmallBtn) nextSmallBtn.title = `Next sample: +${interval} (↑)`;
        if (prevLargeBtn) prevLargeBtn.title = `Back: −${this.formatNumber(largeStep)} (PgDn)`;
        if (nextLargeBtn) nextLargeBtn.title = `Forward: +${this.formatNumber(largeStep)} (PgUp)`;
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
