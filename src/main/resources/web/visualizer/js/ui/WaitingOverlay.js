/**
 * Centered overlay shown when the visualizer is opened before tick data is available
 * in the database. Polls the pipeline status API for simulation metrics and the
 * tick-range APIs for data availability, auto-resolving once data arrives.
 *
 * @class WaitingOverlay
 */
export class WaitingOverlay {
    static POLL_INTERVAL_MS = 5000;

    /**
     * @param {object} deps
     * @param {object} deps.environmentApi - EnvironmentApi instance (for fetchTickRange)
     * @param {object} deps.organismApi    - OrganismApi instance (for fetchTickRange)
     */
    constructor({ environmentApi, organismApi }) {
        this._environmentApi = environmentApi;
        this._organismApi = organismApi;
        this._timer = null;
        this._el = null;
        this._statusEl = null;
    }

    /**
     * Shows the overlay and polls until tick data becomes available.
     *
     * Resolves with the first available maxTick value.
     * Rejects if the simulation engine is not running for the given runId.
     *
     * @param {string} runId - The run ID to wait for.
     * @returns {Promise<number>} The first available maxTick.
     */
    waitForData(runId) {
        return new Promise((resolve, reject) => {
            this._mount();

            const poll = async () => {
                try {
                    const status = await fetch('/pipeline/api/status')
                        .then(r => r.ok ? r.json() : null)
                        .catch(() => null);

                    if (status) {
                        const pipelineActive = status.status === 'RUNNING' || status.status === 'DEGRADED';

                        if (!pipelineActive || status.activeRunId !== runId) {
                            this._stop();
                            this._unmount();
                            reject(new Error('No data available for this run'));
                            return;
                        }

                        this._updateStatus(status);
                    }

                    const maxTick = await this._fetchMaxTick(runId);
                    if (maxTick !== null) {
                        this._stop();
                        this._unmount();
                        resolve(maxTick);
                    }
                } catch (e) {
                    console.debug('WaitingOverlay poll error:', e);
                }
            };

            poll();
            this._timer = setInterval(poll, WaitingOverlay.POLL_INTERVAL_MS);
        });
    }

    /**
     * Immediately removes the overlay and stops polling (e.g. on navigation away).
     */
    cancel() {
        this._stop();
        this._unmount();
    }

    // ── Private ──────────────────────────────────────────────

    _updateStatus(pipelineStatus) {
        if (!this._statusEl) return;
        // Find the service that reports tick metrics (the simulation source)
        const source = (pipelineStatus.services || []).find(s => s.metrics?.current_tick !== undefined);
        if (!source) return;
        const ticks = Math.max(0, source.metrics.current_tick ?? 0);
        const tps = source.metrics.ticks_per_second ?? 0;
        const ticksFmt = Number(ticks).toLocaleString('en-US');
        const tpsFmt = Math.round(tps).toLocaleString('en-US');
        this._statusEl.textContent = `${ticksFmt} ticks  \u00b7  ${tpsFmt} t/s`;
    }

    async _fetchMaxTick(runId) {
        const [envRange, orgRange] = await Promise.all([
            this._environmentApi.fetchTickRange(runId).catch(() => null),
            this._organismApi.fetchTickRange(runId).catch(() => null)
        ]);
        if (envRange?.maxTick !== undefined && orgRange?.maxTick !== undefined) {
            return Math.min(envRange.maxTick, orgRange.maxTick);
        }
        if (envRange?.maxTick !== undefined) return envRange.maxTick;
        if (orgRange?.maxTick !== undefined) return orgRange.maxTick;
        return null;
    }

    _mount() {
        if (this._el) return;

        this._el = document.createElement('div');
        this._el.className = 'waiting-overlay';
        this._el.innerHTML =
            '<div class="waiting-overlay-box">' +
                '<div class="waiting-overlay-title">Waiting for data<span class="loading-dots" aria-hidden="true"><span>.</span><span>.</span><span>.</span></span></div>' +
                '<div class="waiting-overlay-status" id="waiting-overlay-status"></div>' +
            '</div>';

        document.body.appendChild(this._el);
        this._statusEl = this._el.querySelector('#waiting-overlay-status');
    }

    _unmount() {
        if (this._el) {
            this._el.remove();
            this._el = null;
            this._statusEl = null;
        }
    }

    _stop() {
        if (this._timer) {
            clearInterval(this._timer);
            this._timer = null;
        }
    }
}
