/**
 * Polls the pipeline status API at a fixed interval and notifies listeners on change.
 * Uses plain fetch() to avoid triggering any loading indicators.
 *
 * @class PipelineStatusPoller
 */
export class PipelineStatusPoller {
    static DEFAULT_INTERVAL_MS = 30_000;

    constructor(intervalMs = PipelineStatusPoller.DEFAULT_INTERVAL_MS) {
        this._intervalMs = intervalMs;
        this._timer = null;
        this._listeners = [];
        this._activeRunId = null;
        this._status = null;
    }

    /** @returns {string|null} The run ID of the currently active pipeline run. */
    get activeRunId() { return this._activeRunId; }

    /** @returns {string|null} Overall pipeline status: "RUNNING", "DEGRADED", "STOPPED", or "IDLE". */
    get status() { return this._status; }

    /**
     * Registers a listener that is called whenever the pipeline status changes.
     * @param {function({activeRunId: string|null, status: string|null}): void} fn
     */
    onChange(fn) {
        this._listeners.push(fn);
    }

    /**
     * Starts polling. Performs an immediate first poll.
     */
    start() {
        this.stop();
        this._poll();
        this._timer = setInterval(() => this._poll(), this._intervalMs);
    }

    /**
     * Stops polling.
     */
    stop() {
        if (this._timer) {
            clearInterval(this._timer);
            this._timer = null;
        }
    }

    /** @private */
    async _poll() {
        try {
            const resp = await fetch('/pipeline/api/status');
            if (!resp.ok) return;
            const data = await resp.json();

            const newRunId = data.activeRunId || null;
            const newStatus = data.status || null;

            if (newRunId !== this._activeRunId || newStatus !== this._status) {
                this._activeRunId = newRunId;
                this._status = newStatus;
                this._notify();
            }
        } catch {
            // Silently ignore — network errors are transient
        }
    }

    /** @private */
    _notify() {
        const snapshot = { activeRunId: this._activeRunId, status: this._status };
        for (const fn of this._listeners) {
            try { fn(snapshot); } catch (e) { console.debug('PipelineStatusPoller listener error:', e); }
        }
    }
}
