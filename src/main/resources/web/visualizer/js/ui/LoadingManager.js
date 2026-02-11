/**
 * Manages the global loading state and controls the loading status panel.
 * Provides both explicit status management (show/update/hide) for orchestrated
 * loading sequences with step-based progress, and automatic counter-based
 * tracking (incrementRequests/decrementRequests) for standalone API calls.
 *
 * @class LoadingManager
 */
export class LoadingManager {
    constructor() {
        /** @private */ this.activeRequestCount = 0;
        /** @private */ this.activeTaskCount = 0;
        /** @private */ this._explicitStatus = false;
        /** @private */ this._panel = null;
        /** @private */ this._statusEl = null;
        /** @private */ this._progressBar = null;

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', () => this._initDom());
        } else {
            this._initDom();
        }
    }

    /** @private */
    _initDom() {
        this._panel = document.getElementById('loading-panel');
        this._statusEl = document.getElementById('loading-status');
        this._progressBar = document.getElementById('loading-progress-bar');
    }

    /** @private */
    _ensureDom() {
        if (!this._panel) {
            this._initDom();
        }
        return !!this._panel;
    }

    /**
     * Shows the loading panel with a status message. Progress starts at 0%.
     * @param {string} status - The status text to display.
     */
    show(status) {
        if (!this._ensureDom()) return;
        this._explicitStatus = true;
        this._statusEl.textContent = status;
        this._progressBar.style.width = '0%';
        this._panel.classList.add('active');
    }

    /**
     * Updates the status text and optionally the progress bar.
     * If the panel is not already visible, shows it.
     * @param {string} status - The new status text.
     * @param {number} [percent] - Optional progress percentage (0-100).
     */
    update(status, percent) {
        if (!this._ensureDom()) return;
        this._statusEl.textContent = status;
        if (percent !== undefined) {
            this._progressBar.style.width = `${Math.max(0, Math.min(100, percent))}%`;
        }
        if (!this._panel.classList.contains('active')) {
            this._panel.classList.add('active');
        }
    }

    /**
     * Returns whether an explicit orchestrated status is currently active.
     * Useful for nested calls to determine if an outer orchestrator is managing progress.
     * @returns {boolean}
     */
    get isActive() {
        return this._explicitStatus;
    }

    /**
     * Hides the loading panel and clears the explicit status.
     */
    hide() {
        if (!this._ensureDom()) return;
        this._explicitStatus = false;
        this._panel.classList.remove('active');
    }

    /** @private */
    _updateFromCounters() {
        if (this._explicitStatus) return;
        if (!this._ensureDom()) return;

        if (this.activeRequestCount > 0 || this.activeTaskCount > 0) {
            this._statusEl.textContent = 'Loading';
            this._progressBar.style.width = '0%';
            this._panel.classList.add('active');
        } else {
            this._panel.classList.remove('active');
        }
    }

    /**
     * Registers the start of an API request.
     * Shows generic "Loading..." if no explicit status is active.
     */
    incrementRequests() {
        this.activeRequestCount++;
        this._updateFromCounters();
    }

    /**
     * Registers the end of an API request.
     * Hides the panel if no explicit status and all counters are zero.
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
}

// Export a single instance for global use
export const loadingManager = new LoadingManager();
