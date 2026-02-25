/**
 * Manages the global loading state by delegating to the timeline canvas overlay
 * via TickPanelManager. Provides both explicit status management (show/update/hide)
 * for orchestrated loading sequences and automatic counter-based tracking
 * (incrementRequests/decrementRequests) for standalone API calls.
 *
 * The TickPanelManager reference is set late via {@link setTickPanelManager}
 * because it is constructed after the singleton is imported. All methods
 * silently no-op until the reference is available.
 *
 * @class LoadingManager
 */
export class LoadingManager {
    constructor() {
        /** @private */ this.activeRequestCount = 0;
        /** @private */ this.activeTaskCount = 0;
        /** @private */ this._explicitStatus = false;
        /** @private */ this._tpm = null;
    }

    /**
     * Binds the TickPanelManager that owns the timeline canvas overlay.
     * @param {import('../panels/TickPanelManager.js').TickPanelManager} tpm
     */
    setTickPanelManager(tpm) {
        this._tpm = tpm;
    }

    /**
     * Shows the loading overlay with a status message.
     * @param {string} status - The status text to display.
     */
    show(status) {
        this._explicitStatus = true;
        this._tpm?.showLoading(status);
    }

    /**
     * Updates the status text on the loading overlay.
     * @param {string} status - The new status text.
     * @param {number} [_percent] - Ignored (kept for call-site compatibility).
     */
    update(status, _percent) {
        this._tpm?.updateLoadingText(status);
        if (!this._explicitStatus) {
            this._explicitStatus = true;
            this._tpm?.showLoading(status);
        }
    }

    /**
     * Returns whether an explicit orchestrated status is currently active.
     * @returns {boolean}
     */
    get isActive() {
        return this._explicitStatus;
    }

    /**
     * Hides the loading overlay and clears the explicit status.
     * If request counters are still active, the next counter event will re-show.
     */
    hide() {
        this._explicitStatus = false;
        this._tpm?.hideLoading();
    }

    /**
     * Registers the start of an API request.
     * Shows generic "Loading" if no explicit status is active.
     */
    incrementRequests() {
        this.activeRequestCount++;
        this._updateFromCounters();
    }

    /**
     * Registers the end of an API request.
     * Hides the overlay if no explicit status and all counters are zero.
     */
    decrementRequests() {
        if (this.activeRequestCount > 0) {
            this.activeRequestCount--;
        }
        this._updateFromCounters();
    }

    /**
     * Registers the start of a long-running local task.
     */
    incrementTasks() {
        this.activeTaskCount++;
        this._updateFromCounters();
    }

    /**
     * Registers the end of a long-running local task.
     */
    decrementTasks() {
        if (this.activeTaskCount > 0) {
            this.activeTaskCount--;
        }
        this._updateFromCounters();
    }

    /** @private */
    _updateFromCounters() {
        if (this._explicitStatus) return;

        if (this.activeRequestCount > 0 || this.activeTaskCount > 0) {
            this._tpm?.showLoading('Loading');
        } else {
            this._tpm?.hideLoading();
        }
    }
}

// Export a single instance for global use
export const loadingManager = new LoadingManager();
