import { TimelineLoadingOverlay } from '../TimelineLoadingOverlay.js';
import * as TickGrid from '../../TickGrid.js';
import { bindTickField, formatTick, groupDigits, parseTick } from '../../../../shared/tick/TickText.js';
import { isTextEntry } from '../../interaction/EditableTarget.js';

/**
 * Milliseconds a click on the timeline waits for a second click before it jumps: a double click
 * jumps to the last tick alone, without loading the tick of its first click on the way.
 */
const TIMELINE_DOUBLE_CLICK_MS = 200;

/**
 * Manages the timeline panel with interactive canvas track, tick input, and keyboard shortcuts.
 * The canvas displays the recorded ticks, a current-tick marker, and a hover preview.
 * Uses callback injection for loose coupling (no direct controller reference).
 * <p>
 * Every step and every snap goes over the ticks the run holds, the ranges in the state: a small
 * step is the next recorded tick, a large step moves by the multiplier times the local step and
 * lands on a recorded tick, and the track spans the first to the last recorded tick. A run that
 * does not start at zero, or holds stretches of different step, is navigated like any other.
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
     * @param {Function} options.getState - Callback to get current state: () => { currentTick, maxTick, runId, ranges, samplingInterval }
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
        this._timelineClickTimer = null; // A single click waiting for a possible second one
        this.isKeyHeld = false;

        // Debounced navigation for keyboard input
        this._navigateDebounceTimer = null;
        this._pendingTick = null;

        // Timeline hover state
        this._hoverTick = null;

        // Loading overlay state
        this._loadingOverlay = new TimelineLoadingOverlay();
        this._isLoading = false;
        this._rafId = null;

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
        if (tickInput) bindTickField(tickInput);

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
        const ranges = state.ranges || [];
        const currentTick = this._pendingTick !== null ? this._pendingTick : (state.currentTick || 0);

        // Clear and fill background
        ctx.clearRect(0, 0, w, h);
        ctx.fillStyle = '#1a1a24';
        ctx.fillRect(0, 0, w, h);

        if (ranges.length === 0) return;

        // Draw the recorded ticks; a gap between ranges keeps the background
        const numSamples = TickGrid.sampleCount(ranges);
        const pixelsPerSample = w / Math.max(1, numSamples - 1);

        if (pixelsPerSample >= 3 && numSamples <= 10000) {
            // Individual marks visible
            ctx.fillStyle = 'rgba(255, 255, 255, 0.25)';
            for (const range of ranges) {
                for (let tick = range.first; tick <= range.last; tick += range.step) {
                    const x = this._tickToPosition(tick);
                    ctx.fillRect(Math.round(x), 0, 1, h);
                }
            }
        } else {
            // Marks too dense — render each range as a continuous filled area
            ctx.fillStyle = 'rgba(255, 255, 255, 0.12)';
            for (const range of ranges) {
                const from = Math.round(this._tickToPosition(range.first));
                const to = Math.round(this._tickToPosition(range.last));
                ctx.fillRect(from, 0, Math.max(1, to - from), h);
            }
        }

        this._renderScale(ctx, w, h, TickGrid.firstTick(ranges) ?? 0, TickGrid.lastTick(ranges) ?? 0);

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
     * Draws the scale of the track: a mark every round step, its tick centred above it where it
     * fits. The step
     * is the 1-2-5 step that puts the marks about 90 pixels apart, whatever the length of the run.
     * @param {CanvasRenderingContext2D} ctx
     * @param {number} w - Track width in CSS pixels
     * @param {number} h - Track height in CSS pixels
     * @param {number} first - First tick of the run
     * @param {number} last - Last tick of the run
     * @private
     */
    _renderScale(ctx, w, h, first, last) {
        if (last <= first) return;
        const raw = (last - first) / Math.max(1, w / 90);
        const power = Math.pow(10, Math.floor(Math.log10(raw)));
        const step = [1, 2, 5, 10].map(factor => factor * power).find(candidate => candidate >= raw);

        ctx.font = '9px "Roboto Mono", "Courier New", monospace';
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'center';
        for (let tick = Math.ceil(first / step) * step; tick <= last; tick += step) {
            const x = Math.round(this._tickToPosition(tick));
            ctx.fillStyle = 'rgba(255, 255, 255, 0.4)';
            ctx.fillRect(Math.min(w - 1, x), h - 6, 1, 6);
            // A tick that would not fit centred over its mark is left out: a shifted one misleads
            const label = formatTick(tick);
            const half = ctx.measureText(label).width / 2;
            if (x - half < 2 || x + half > w - 2) continue;
            ctx.fillStyle = 'rgba(255, 255, 255, 0.6)';
            ctx.fillText(label, x, (h - 6) / 2 + 1);
        }
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
            tooltip.textContent = groupDigits(snapped);
            tooltip.style.left = `${snappedX}px`;
            tooltip.classList.add('visible');
        }
    }

    /**
     * Handles click on the timeline track: navigates to the snapped tick, or to the last tick when
     * the click is the second of a double click.
     * @param {MouseEvent} e
     * @private
     */
    _handleTimelineClick(e) {
        const { trackContainer } = this.elements;
        if (!trackContainer) return;

        clearTimeout(this._timelineClickTimer);
        this._timelineClickTimer = null;

        // The browser counts the clicks of a double click by the system's double-click time
        if (e.detail >= 2) {
            const last = TickGrid.lastTick(this.getState().ranges || []);
            if (last !== null) this.onNavigate(last);
            return;
        }

        const rect = trackContainer.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const rawTick = this._positionToTick(x);
        const snapped = this._snapToSampledTick(rawTick);

        this._timelineClickTimer = setTimeout(() => {
            this._timelineClickTimer = null;
            this.onNavigate(snapped);
        }, TIMELINE_DOUBLE_CLICK_MS);
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
        const ranges = this.getState().ranges || [];
        const first = TickGrid.firstTick(ranges) ?? 0;
        const last = TickGrid.lastTick(ranges) ?? 0;
        return first + (x / w) * (last - first);
    }

    /**
     * Converts a tick value to a pixel x-position on the canvas.
     * @param {number} tick - The tick value.
     * @returns {number} The x position in CSS pixels.
     * @private
     */
    _tickToPosition(tick) {
        const w = this._canvasWidth || 1;
        const ranges = this.getState().ranges || [];
        const first = TickGrid.firstTick(ranges) ?? 0;
        const last = TickGrid.lastTick(ranges) ?? 0;
        const span = Math.max(1, last - first);
        return ((tick - first) / span) * w;
    }

    /**
     * Snaps a tick value to the nearest recorded tick. Before anything is recorded the value is
     * only kept from going below zero.
     * @param {number} tick - The raw tick value.
     * @returns {number} The nearest recorded tick.
     * @private
     */
    _snapToSampledTick(tick) {
        const ranges = this.getState().ranges || [];
        return TickGrid.snap(ranges, Math.round(tick)) ?? Math.max(0, Math.round(tick));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Navigation Methods
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Navigates one small step: to the next or the previous recorded tick. At either end the
     * step stays where it is.
     * @param {('forward'|'backward')} direction - The direction to navigate.
     * @param {boolean} [debounce=false] - If true, debounce the data load.
     */
    navigateSmallStep(direction, debounce = false) {
        const state = this.getState();
        const ranges = state.ranges || [];
        const baseTick = this._pendingTick !== null ? this._pendingTick : state.currentTick;
        const neighbour = direction === 'forward'
            ? TickGrid.next(ranges, baseTick)
            : TickGrid.previous(ranges, baseTick);
        const targetTick = neighbour ?? baseTick;
        if (debounce) {
            this._navigateDebounced(targetTick);
        } else {
            this.onNavigate(targetTick);
        }
    }

    /**
     * Navigates a large step: by the multiplier times the local step, landing on a recorded
     * tick in the direction of travel, and at either end on that end.
     * @param {('forward'|'backward')} direction - The direction to navigate.
     * @param {boolean} [debounce=false] - If true, debounce the data load.
     */
    navigateLargeStep(direction, debounce = false) {
        const state = this.getState();
        const ranges = state.ranges || [];
        const baseTick = this._pendingTick !== null ? this._pendingTick : state.currentTick;
        const largeStep = this.getMultiplier() * this._localStep(baseTick);
        const targetTick = TickGrid.jump(ranges, baseTick, direction === 'forward' ? largeStep : -largeStep)
            ?? baseTick;
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
        const maxTick = state.maxTick || 0;

        const clamped = this._snapToSampledTick(targetTick);

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
     * Calculates the default multiplier from the local step at the current tick.
     * Target: multiplier × step ≈ 100,000 ticks
     * Result is always a power of 10 (1, 10, 100, 1000, 10000, etc.)
     * @returns {number}
     */
    getDefaultMultiplier() {
        const state = this.getState();
        const targetStep = 100000;
        const rawMultiplier = targetStep / this._localStep(state.currentTick);

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
            if (this.handleTickInputChange()) {
                setTimeout(() => this.elements.tickInput?.select(), 0);
            }
        } else if (e.key === 'Escape') {
            e.preventDefault();
            this.showCurrentTick();
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
     * Takes the typed tick. A text that is no tick - a decimal part without a suffix - is marked
     * and keeps its text, so that it can be completed.
     * @returns {boolean} Whether the text was a tick
     * @private
     */
    handleTickInputChange() {
        const { tickInput } = this.elements;
        const value = parseTick(tickInput?.value);
        tickInput?.classList.toggle('invalid', value === null);
        if (value !== null) {
            this.onNavigate(value);
        }
        return value !== null;
    }

    /**
     * Puts the current tick back into the tick input.
     * @private
     */
    showCurrentTick() {
        const { tickInput } = this.elements;
        if (!tickInput) return;
        tickInput.value = groupDigits(this.getState().currentTick || 0);
        tickInput.classList.remove('invalid');
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
     * Up/Down = single step, PageUp/PageDown = large step, Enter = the tick field, End = the
     * newest recorded tick.
     * @param {KeyboardEvent} e
     * @private
     */
    handleGlobalKeyDown(e) {
        // Keys typed into a text field are text, not navigation
        if (isTextEntry(document.activeElement)) {
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
        } else if (e.key === 'Enter') {
            // Taken from whatever button holds the focus, which would fire its click instead
            e.preventDefault();
            this.focusTickInput();
        } else if (e.key === 'End') {
            e.preventDefault();
            this.navigateToLastTick();
        }
    }

    /**
     * Puts the caret into the tick field with its text selected, so that a tick can be typed
     * straight away.
     */
    focusTickInput() {
        const { tickInput } = this.elements;
        if (!tickInput) return;
        tickInput.focus();
        tickInput.select();
    }

    /**
     * Navigates to the newest tick the run holds. A run that holds nothing stays where it is.
     */
    navigateToLastTick() {
        const last = TickGrid.lastTick(this.getState().ranges || []);
        if (last !== null) {
            this.onNavigate(last);
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
    // Loading Overlay
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Activates the loading overlay animation on the timeline canvas.
     * @param {string} text - Status text to display (e.g. "Loading metadata").
     */
    showLoading(text) {
        this._loadingOverlay.setStatusText(text);
        if (!this._isLoading) {
            this._isLoading = true;
            this._startAnimationLoop();
        }
    }

    /**
     * Updates the status text while the loading overlay is active.
     * @param {string} text - New status text.
     */
    updateLoadingText(text) {
        this._loadingOverlay.setStatusText(text);
    }

    /**
     * Deactivates the loading overlay and renders one final clean frame.
     */
    hideLoading() {
        if (!this._isLoading) return;
        this._isLoading = false;
        this._stopAnimationLoop();
        this._renderTimeline();
    }

    /**
     * @private
     */
    _startAnimationLoop() {
        if (this._rafId !== null) return;
        const loop = (timestamp) => {
            if (!this._isLoading) return;
            this._renderTimeline();
            const state = this.getState();
            const currentTick = this._pendingTick !== null ? this._pendingTick : (state.currentTick || 0);
            const cx = this._tickToPosition(currentTick);
            this._loadingOverlay.render(
                this._ctx,
                this._canvasWidth || 0,
                this._canvasHeight || 0,
                cx,
                timestamp
            );
            this._rafId = requestAnimationFrame(loop);
        };
        this._rafId = requestAnimationFrame(loop);
    }

    /**
     * @private
     */
    _stopAnimationLoop() {
        if (this._rafId !== null) {
            cancelAnimationFrame(this._rafId);
            this._rafId = null;
        }
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
            tickInput.value = groupDigits(currentTick || 0);
            tickInput.classList.remove('invalid');
        }

        if (tickSuffix) {
            tickSuffix.textContent = '/' + this.formatNumber(maxTick);
        }

        this._renderTimeline();
    }

    /**
     * Shows the step the multiplier is applied to and shows the multiplier.
     * @param {number} step - The step the run is recorded at around the current tick.
     */
    updateStepInfo(step) {
        const { multiplierWrapper, multiplierSuffix } = this.elements;

        if (multiplierWrapper) {
            multiplierWrapper.style.display = 'inline-flex';
        }
        if (multiplierSuffix) {
            multiplierSuffix.textContent = `x${step}`;
        }

        this._renderTimeline();
    }

    /**
     * The step the run is recorded at around a tick; the configured sampling interval before
     * anything is recorded.
     * @param {number} tick
     * @returns {number}
     * @private
     */
    _localStep(tick) {
        const state = this.getState();
        return TickGrid.stepAt(state.ranges || [], tick) ?? state.samplingInterval ?? 1;
    }

    /**
     * Loads the multiplier from localStorage for a specific run.
     * Uses smart default: multiplier × local step ≈ 100,000 ticks.
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
     * Updates the navigation button tooltips with the distance each button actually moves from
     * the current tick: the same targets the buttons navigate to, so a tooltip at a gap or at the
     * end of the recorded ticks says what the step will really be.
     */
    updateTooltips() {
        const { prevLargeBtn, prevSmallBtn, nextSmallBtn, nextLargeBtn } = this.elements;
        const state = this.getState();
        const ranges = state.ranges || [];
        const base = state.currentTick || 0;
        const largeStep = this.getMultiplier() * this._localStep(base);
        const distance = (target) => target === null ? 0 : Math.abs(target - base);

        if (prevSmallBtn) prevSmallBtn.title = `Back: −${this.formatNumber(distance(TickGrid.previous(ranges, base)))} (↓)`;
        if (nextSmallBtn) nextSmallBtn.title = `Forward: +${this.formatNumber(distance(TickGrid.next(ranges, base)))} (↑)`;
        if (prevLargeBtn) prevLargeBtn.title = `Back: −${this.formatNumber(distance(TickGrid.jump(ranges, base, -largeStep)))} (PgDn)`;
        if (nextLargeBtn) nextLargeBtn.title = `Forward: +${this.formatNumber(distance(TickGrid.jump(ranges, base, largeStep)))} (PgUp)`;
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
