/**
 * Decides which run a web interface shows and whether a run without data is still to be expected.
 *
 * A run counts as starting while the pipeline of this node reports it as its active run and is
 * RUNNING or DEGRADED: its data may not be indexed yet, but it is on its way. A run without data
 * that is not starting has none to come, and the interfaces say so instead of waiting.
 *
 * The pipeline status only knows the simulation of this node. A run simulated elsewhere is never
 * starting here; it appears once its data has been indexed.
 */

import { hideStartNotice, showErrorNotice, showStartNotice, updateStartNotice } from '../notice/Notice.js';

/**
 * Raised when an interface has nothing to show: no run at all ({@code runId} null), or a run
 * without data that is not starting.
 */
export class RunUnavailableError extends Error {
    /** @param {string|null} runId */
    constructor(runId) {
        super(runId ? `Run ${runId} has no data on this node.` : 'No simulation runs available.');
        this.name = 'RunUnavailableError';
        this.runId = runId;
    }
}

/**
 * Returns the names of the pipeline services that stopped with an error.
 * @param {object|null} pipeline
 * @returns {string[]}
 */
function failedServices(pipeline) {
    return (pipeline?.services || []).filter(s => s.state === 'ERROR').map(s => s.name);
}

/** Interval in which a waiting interface checks again whether its data has arrived. */
export const WAIT_INTERVAL_MS = 5000;

/**
 * Fetches the pipeline status of this node without touching any loading indicator.
 * @returns {Promise<object|null>} The status, or null if it cannot be read.
 */
export async function fetchPipelineStatus() {
    try {
        const response = await fetch('/pipeline/api/status');
        return response.ok ? await response.json() : null;
    } catch {
        return null;
    }
}

/**
 * Returns the run the pipeline is producing data for, if it is producing any.
 * @param {{activeRunId?: string|null, status?: string|null}|null} pipeline
 * @returns {string|null}
 */
export function startingRunId(pipeline) {
    if (!pipeline || !pipeline.activeRunId) return null;
    return pipeline.status === 'RUNNING' || pipeline.status === 'DEGRADED' ? pipeline.activeRunId : null;
}

/**
 * Tells whether data for the given run is still to be expected.
 * @param {object|null} pipeline
 * @param {string} runId
 * @returns {boolean}
 */
export function isRunStarting(pipeline, runId) {
    return !!runId && startingRunId(pipeline) === runId;
}

/**
 * Chooses the run to show when none was asked for: the starting run, otherwise the newest run
 * with data.
 * @param {Array<{runId: string, startTime?: number}>} runs - runs with data
 * @param {object|null} pipeline
 * @returns {string|null}
 */
export function chooseInitialRunId(runs, pipeline) {
    const starting = startingRunId(pipeline);
    if (starting) return starting;
    const newest = sortRunsNewestFirst(runs || [])[0];
    return newest ? newest.runId : null;
}

/**
 * Adds the starting run to a list of runs with data if it is not in there yet.
 * @param {Array<{runId: string}>} runs
 * @param {object|null} pipeline
 * @returns {Array<{runId: string, starting?: boolean}>} a new list; the added run carries {@code starting: true}
 */
export function includeStartingRun(runs, pipeline) {
    const starting = startingRunId(pipeline);
    const list = runs || [];
    if (!starting || list.some(r => r.runId === starting)) return list;
    return [{ runId: starting, starting: true }, ...list];
}

/**
 * Sorts runs newest first, by start time or by the timestamp their id begins with.
 * @param {Array<{runId: string, startTime?: number}>} runs
 * @returns {Array} a new, sorted list
 */
export function sortRunsNewestFirst(runs) {
    const score = (r) => {
        if (r.startTime) return r.startTime;
        const m = (r.runId || '').match(/^(\d{8})-(\d{8})/);
        return m ? Number(m[1] + m[2]) : 0;
    };
    return [...runs].sort((a, b) => score(b) - score(a));
}

/**
 * Shortens a run id for display: its start date and time and the last characters of its UUID.
 * @param {string} runId
 * @returns {string}
 */
export function formatRunLabel(runId) {
    const m = (runId || '').match(/^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})\d*-([0-9a-fA-F-]+)$/);
    if (!m) return runId || '';
    const tail = m[6].replace(/-/g, '').slice(-4);
    return `${m[1]}-${m[2]}-${m[3]} ${m[4]}:${m[5]} \u00b7 \u2026${tail}`;
}

/**
 * Tells how far a starting run is from its first data: the part of its first chunk simulated so
 * far, whether that chunk is complete and waits for the indexers, and which services have failed
 * meanwhile.
 * @param {object|null} pipeline
 * @returns {{indexing: boolean, fraction: number, ticks: number|null, ticksPerSecond: number|null, failed: string[]}}
 */
export function startProgress(pipeline) {
    const failed = failedServices(pipeline);
    const engine = (pipeline?.services || []).find(s => s.metrics?.ticks_per_chunk !== undefined);
    if (!engine) return { indexing: false, fraction: 0, ticks: null, ticksPerSecond: null, failed };
    const { current_tick: current, start_tick: start, ticks_per_chunk: perChunk, ticks_per_second: tps } = engine.metrics;
    const simulated = Math.max(0, current - start);
    const fraction = perChunk > 0 ? simulated / perChunk : 0;
    // The engine measures its rate over a time window; until the first window has passed it reports 0
    const ticksPerSecond = tps > 0 ? tps : null;
    return { indexing: fraction >= 1, fraction: Math.min(1, fraction), ticks: Math.max(0, current), ticksPerSecond, failed };
}

/**
 * Shows the start card while waiting for the data of a starting run, and removes it afterwards.
 * @param {RunWaiter} waiter
 * @param {string} runId
 * @param {function(): Promise<any>} check - resolves to the awaited data, or null while there is none
 * @returns {Promise<any>} the first non-null result of the check
 */
export async function waitWithStartNotice(waiter, runId, check) {
    showStartNotice(formatRunLabel(runId));
    try {
        return await waiter.wait(runId, {
            check,
            onProgress: pipeline => updateStartNotice(startProgress(pipeline))
        });
    } finally {
        hideStartNotice();
    }
}

/**
 * Shows what the user can do when there is nothing to show. For a run without data, the way to
 * the latest run is offered only if there is a run to open.
 * @param {RunUnavailableError} error
 * @returns {Promise<void>}
 */
export async function showRunUnavailableNotice(error) {
    const reload = { label: 'Reload', onClick: () => window.location.reload() };
    if (!error.runId) {
        showErrorNotice({
            title: 'No simulation runs found',
            text: 'Your configuration probably points to the wrong data location, or no simulation has been indexed yet.',
            actions: [{ ...reload, primary: true }]
        });
        return;
    }
    const actions = [reload];
    if (await hasRunToOpen()) {
        actions.push({
            label: 'Open latest run',
            primary: true,
            onClick: () => window.location.assign(window.location.pathname)
        });
    } else {
        reload.primary = true;
    }
    showErrorNotice({
        title: `Run ${formatRunLabel(error.runId)} has no data`,
        text: 'Your configuration probably points to the wrong data location, or this run has not been indexed yet.',
        actions
    });
}

/**
 * Shows that a page could not be loaded because of a technical fault; the user can only reload.
 * @param {string} title
 * @param {Error} error
 */
export function showLoadFailedNotice(title, error) {
    showErrorNotice({
        title,
        detail: error?.message || String(error),
        actions: [{ label: 'Reload', primary: true, onClick: () => window.location.reload() }]
    });
}

/** @private */
async function hasRunToOpen() {
    const [runs, pipeline] = await Promise.all([
        fetch('/analyzer/api/runs').then(r => r.ok ? r.json() : []).catch(() => []),
        fetchPipelineStatus()
    ]);
    return (Array.isArray(runs) && runs.length > 0) || startingRunId(pipeline) !== null;
}

/**
 * Waits for the data of a starting run.
 *
 * Every {@link WAIT_INTERVAL_MS} the check is run; the wait ends with its first non-null result.
 * It fails with a RunUnavailableError once the run is no longer starting, and with an AbortError
 * when cancelled. A failed pipeline service does not end the wait: the data this interface needs
 * may come from services that still run.
 */
export class RunWaiter {
    constructor() {
        this._timer = null;
        this._reject = null;
        this._token = null;
    }

    /**
     * @param {string} runId
     * @param {object} options
     * @param {function(): Promise<any>} options.check - resolves to the awaited data, or null while there is none
     * @param {function(object|null): void} [options.onProgress] - receives the pipeline status on every round
     * @returns {Promise<any>} the first non-null result of the check
     */
    wait(runId, { check, onProgress }) {
        this.cancel();
        return new Promise((resolve, reject) => {
            const token = {};
            this._token = token;
            this._reject = reject;
            const current = () => this._token === token;
            const round = async () => {
                try {
                    const value = await check();
                    if (!current()) return;
                    if (value !== null && value !== undefined) {
                        this._finish();
                        resolve(value);
                        return;
                    }
                    const pipeline = await fetchPipelineStatus();
                    if (!current()) return;
                    if (pipeline && !isRunStarting(pipeline, runId)) {
                        this._finish();
                        reject(new RunUnavailableError(runId));
                        return;
                    }
                    onProgress?.(pipeline);
                } catch (e) {
                    console.debug('RunWaiter round failed:', e);
                }
            };
            round();
            this._timer = setInterval(round, WAIT_INTERVAL_MS);
        });
    }

    /** @returns {boolean} whether a wait is in progress */
    isWaiting() {
        return this._token !== null;
    }

    /** Ends a running wait; its promise fails with an AbortError. */
    cancel() {
        const reject = this._reject;
        this._finish();
        if (reject) reject(new DOMException('Wait cancelled', 'AbortError'));
    }

    /** @private */
    _finish() {
        if (this._timer) {
            clearInterval(this._timer);
            this._timer = null;
        }
        this._reject = null;
        this._token = null;
    }
}
