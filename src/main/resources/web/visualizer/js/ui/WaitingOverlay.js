import { loadingManager } from './LoadingManager.js';
import { RunWaiter } from '../../../shared/run/RunAvailability.js';

/**
 * Shows the waiting state on the timeline canvas while the visualizer is open on a starting run
 * whose data has not been indexed yet, and resolves once the awaited data is there.
 *
 * Uses LoadingManager (not TickPanelManager directly) so that the explicit-status
 * flag prevents counter-based API-request tracking from interfering with the
 * waiting-for-data state.
 *
 * @class WaitingOverlay
 */
export class WaitingOverlay {
    /**
     * @param {object} deps
     * @param {object} deps.environmentApi - EnvironmentApi instance (for fetchTickRange)
     * @param {object} deps.organismApi    - OrganismApi instance (for fetchTickRange)
     */
    constructor({ environmentApi, organismApi }) {
        this._environmentApi = environmentApi;
        this._organismApi = organismApi;
        this._waiter = new RunWaiter();
    }

    /**
     * Shows the waiting state until the check yields a value.
     *
     * Fails with a RunUnavailableError once the run is no longer starting, and with an
     * AbortError when cancelled.
     *
     * @param {string} runId - The run ID to wait for.
     * @param {function(): Promise<any>} check - Resolves to the awaited value, or null while there is none.
     * @returns {Promise<any>} The first value the check yields.
     */
    async waitFor(runId, check) {
        loadingManager.show('Waiting for data');
        try {
            return await this._waiter.wait(runId, {
                check,
                onProgress: text => loadingManager.update(text)
            });
        } finally {
            loadingManager.hide();
        }
    }

    /**
     * Waits until tick data of the run is available.
     *
     * @param {string} runId - The run ID to wait for.
     * @returns {Promise<number>} The first available maxTick.
     */
    waitForData(runId) {
        return this.waitFor(runId, () => this._fetchMaxTick(runId));
    }

    /**
     * Immediately stops waiting and hides the loading overlay (e.g. on navigation away).
     */
    cancel() {
        this._waiter.cancel();
        loadingManager.hide();
    }

    // ── Private ──────────────────────────────────────────────

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
}
