import { loadingManager } from './LoadingManager.js';

/**
 * Polls the pipeline status API and tick-range APIs when the visualizer is opened
 * before tick data is available in the database. Shows loading status with live
 * simulation metrics on the timeline canvas (via LoadingManager) and
 * auto-resolves once data arrives.
 *
 * Uses LoadingManager (not TickPanelManager directly) so that the explicit-status
 * flag prevents counter-based API-request tracking from interfering with the
 * waiting-for-data state.
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
    }

    /**
     * Shows loading status on the timeline and polls until tick data becomes available.
     *
     * Resolves with the first available maxTick value.
     * Rejects if the simulation engine is not running for the given runId.
     *
     * @param {string} runId - The run ID to wait for.
     * @returns {Promise<number>} The first available maxTick.
     */
    waitForData(runId) {
        return new Promise((resolve, reject) => {
            loadingManager.show('Waiting for data');

            const poll = async () => {
                try {
                    const status = await fetch('/pipeline/api/status')
                        .then(r => r.ok ? r.json() : null)
                        .catch(() => null);

                    if (status) {
                        const pipelineActive = status.status === 'RUNNING' || status.status === 'DEGRADED';

                        if (!pipelineActive || status.activeRunId !== runId) {
                            this._stop();
                            loadingManager.hide();
                            reject(new Error('No data available for this run'));
                            return;
                        }

                        this._updateStatus(status);
                    }

                    const maxTick = await this._fetchMaxTick(runId);
                    if (maxTick !== null) {
                        this._stop();
                        loadingManager.hide();
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
     * Immediately stops polling and hides the loading overlay (e.g. on navigation away).
     */
    cancel() {
        this._stop();
        loadingManager.hide();
    }

    // ── Private ──────────────────────────────────────────────

    _updateStatus(pipelineStatus) {
        const source = (pipelineStatus.services || []).find(s => s.metrics?.current_tick !== undefined);
        if (!source) return;
        const ticks = Math.max(0, source.metrics.current_tick ?? 0);
        const tps = source.metrics.ticks_per_second ?? 0;
        const ticksFmt = Number(ticks).toLocaleString('en-US');
        const tpsFmt = Math.round(tps).toLocaleString('en-US');
        loadingManager.update(`Waiting for data \u2014 ${ticksFmt} ticks \u00b7 ${tpsFmt} t/s`);
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

    _stop() {
        if (this._timer) {
            clearInterval(this._timer);
            this._timer = null;
        }
    }
}
