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

/** Message shown when neither a run with data nor a starting run exists. */
export const NO_RUNS_MESSAGE = 'No simulation runs available yet.';

/**
 * Raised when an interface has nothing to show for a run, or no run at all. Its message is meant
 * for the user as it is.
 */
export class RunUnavailableError extends Error {
    constructor(message) {
        super(message);
        this.name = 'RunUnavailableError';
    }
}

/** Interval in which a waiting interface checks again whether its data has arrived. */
export const WAIT_INTERVAL_MS = 5000;

/**
 * Builds the message shown when a run has no data and none is to be expected.
 * @param {string} runId
 * @returns {string}
 */
export function runHasNoDataMessage(runId) {
    return `Run ${runId} has no data on this node.`;
}

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
 * Describes the progress of the simulation while an interface waits for its data.
 * @param {object|null} pipeline
 * @returns {string}
 */
export function waitingText(pipeline) {
    const source = (pipeline?.services || []).find(s => s.metrics?.current_tick !== undefined);
    if (!source) return 'Waiting for data';
    const ticks = Math.max(0, source.metrics.current_tick ?? 0).toLocaleString('en-US');
    const tps = Math.round(source.metrics.ticks_per_second ?? 0).toLocaleString('en-US');
    return `Waiting for data — ${ticks} ticks · ${tps} t/s`;
}

/**
 * Waits for the data of a starting run.
 *
 * Every {@link WAIT_INTERVAL_MS} the check is run; the wait ends with its first non-null result.
 * It fails with the no-data message once the run is no longer starting, and with an AbortError
 * when cancelled.
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
     * @param {function(string): void} [options.onProgress] - receives a progress text on every round
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
                        reject(new RunUnavailableError(runHasNoDataMessage(runId)));
                        return;
                    }
                    onProgress?.(waitingText(pipeline));
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
