/**
 * What the start page says about the runs of a node, worked out from what the node answers.
 *
 * Everything here takes data and gives data: it reads neither the node nor the page. The table of
 * runs is described row by row, with the texts its cells show, and the page only draws that
 * description.
 *
 * @module Runs
 */

import { groupDigits } from '/shared/tick/TickText.js';

/** The name a run carries when its configuration gives it none */
const UNNAMED = 'unnamed';

/** What a cell shows while its value is not known */
export const NO_VALUE = '—';

const ENGINE = 'simulation-engine';

/** What the page says about the node in each of its states */
export const NODE_LABEL = {
    running: 'Simulation running', starting: 'Simulation starting', paused: 'Simulation paused',
    degraded: 'Pipeline degraded', none: 'No simulation running', unreachable: 'Node not reachable'
};

/** What the mark of a state explains where the state does not explain itself */
const STATE_HINT = {
    degraded: 'A service of the pipeline is not healthy',
    starting: 'The run is simulating, its first data is not recorded yet'
};

const numberText = value => value === null || value === undefined ? NO_VALUE : groupDigits(value);

/**
 * When a run was started, read from its ID, which begins with the date and the time.
 *
 * @param {string} runId
 * @returns {string} "2026-11-20 20:47", or the ID itself where it does not begin that way
 */
export function startedAt(runId) {
    const m = runId.match(/^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})/);
    return m ? m[1] + '-' + m[2] + '-' + m[3] + ' ' + m[4] + ':' + m[5] : runId;
}

/**
 * What never changes about a run, taken from its metadata.
 *
 * @param {object} metadata - The metadata the node answers for the run
 * @returns {{name: string, size: string, topology: string, sampling: number}} The name is empty
 *     for a run that was given none
 */
export function factsOf(metadata) {
    let name = '';
    try {
        name = (JSON.parse(metadata.resolvedConfigJson).metadata || {}).experiment || '';
    } catch (e) {
        // a run without a readable name has none
    }
    return {
        name: name === UNNAMED ? '' : name,
        size: metadata.environment.shape.join(' × '),
        topology: metadata.environment.topology,
        sampling: metadata.samplingInterval
    };
}

/**
 * Tells whether a run has recorded ticks.
 *
 * @param {{maxTick?: number|null}|null} tickRange
 * @returns {boolean}
 */
export function hasTicks(tickRange) {
    return !!tickRange && tickRange.maxTick !== null && tickRange.maxTick !== undefined;
}

/**
 * What a run has recorded so far.
 *
 * @param {{minTick: number, maxTick: number}} tickRange
 * @param {{organisms: Array, totalOrganismCount: number}|null} organisms - The organisms of the
 *     last tick, or null where they could not be read
 * @returns {{minTick: number, maxTick: number, alive: number|null, born: number|null}}
 */
export function recordedOf(tickRange, organisms) {
    return {
        minTick: tickRange.minTick,
        maxTick: tickRange.maxTick,
        alive: organisms ? organisms.organisms.length : null,
        born: organisms ? organisms.totalOrganismCount : null
    };
}

/**
 * The simulation engine among the services of the pipeline.
 *
 * @param {{services?: Array<{name: string}>}} status - The state of the pipeline
 * @returns {{state?: string, metrics?: object}} An empty object where the pipeline has none
 */
export function engineOf(status) {
    return (status.services || []).find(service => service.name === ENGINE) || {};
}

/**
 * The run the node simulates, also while it is paused.
 *
 * @param {{status: string, activeRunId?: string|null}} status - The state of the pipeline
 * @returns {string|null}
 */
export function activeRunIdOf(status) {
    const simulating = engineOf(status).state === 'PAUSED' || status.status === 'RUNNING' || status.status === 'DEGRADED';
    return simulating ? status.activeRunId || null : null;
}

/**
 * The state of the node as the page names it.
 *
 * @param {{status: string}} status - The state of the pipeline
 * @param {boolean} activeRunHasData - Whether the run the node simulates has recorded ticks
 * @returns {'paused'|'degraded'|'none'|'starting'|'running'}
 */
export function nodeStateOf(status, activeRunHasData) {
    if (engineOf(status).state === 'PAUSED') return 'paused';
    if (status.status === 'DEGRADED') return 'degraded';
    if (status.status !== 'RUNNING') return 'none';
    return activeRunIdOf(status) && !activeRunHasData ? 'starting' : 'running';
}

/**
 * The runs in the order the table lists them: the newest first. A run that simulates but is not
 * indexed yet is listed all the same, at the top.
 *
 * @param {Array<{runId: string, startTime: number}>} listed - The runs the node has indexed
 * @param {string|null} activeRunId
 * @returns {string[]}
 */
export function orderRunIds(listed, activeRunId) {
    const runIds = [...listed].sort((a, b) => b.startTime - a.startTime).map(run => run.runId);
    if (activeRunId && !runIds.includes(activeRunId)) runIds.unshift(activeRunId);
    return runIds;
}

/**
 * The ticks of a run as the table writes them. A run that starts at tick 0 is named by its last
 * tick. A fork holds a stretch of its parent run and is named by that stretch.
 *
 * @param {{minTick: number, maxTick: number}|undefined} recorded
 * @returns {string}
 */
export function ticksText(recorded) {
    if (!recorded) return NO_VALUE;
    return recorded.minTick > 0
        ? groupDigits(recorded.minTick) + ' – ' + groupDigits(recorded.maxTick)
        : groupDigits(recorded.maxTick);
}

/**
 * How fast the node simulates, as the page writes it beside the state.
 *
 * @param {string} state - The state of the node
 * @param {{metrics?: {ticks_per_second?: number}}} engine
 * @returns {string} Empty where the node does not simulate: the engine reports a stale speed for
 *     some seconds after it was paused
 */
export function paceText(state, engine) {
    const speed = (engine.metrics || {}).ticks_per_second;
    return (state === 'running' || state === 'degraded') && speed !== undefined ? groupDigits(Math.round(speed)) + ' ticks per second' : '';
}

/**
 * Where the tools open a run. A tick and an organism the start page was opened with are passed on,
 * so that a link that names them leads through the start page to the same place.
 *
 * @param {string} runId
 * @param {{tick?: string|null, organism?: string|null}} carried - What the address of the start
 *     page names
 * @returns {Array<{label: string, url: string}>}
 */
export function toolLinks(runId, carried = {}) {
    const params = new URLSearchParams({ runId });
    for (const key of ['tick', 'organism']) if (carried[key]) params.set(key, carried[key]);
    return [{ label: 'Visualizer', url: '/visualizer/?' + params }, { label: 'Analyzer', url: '/analyzer/?' + params }];
}

/**
 * Describes the table of runs.
 *
 * @param {object} known
 * @param {string[]} known.runIds - The runs in the order of the table
 * @param {Object<string, {name: string, size: string, topology: string, sampling: number}>} known.facts
 * @param {Object<string, {minTick: number, maxTick: number, alive: number|null, born: number|null}>} known.recorded
 * @param {string|null} known.activeRunId - The run the node simulates
 * @param {string} known.state - The state of the node
 * @param {boolean} known.togglePending - Whether a request to pause or continue awaits its effect
 * @param {{tick?: string|null, organism?: string|null}} [known.carried] - What the links to the
 *     tools pass on
 * @returns {{named: boolean, rows: Array<object>}} named tells whether any run has a name, and
 *     with it whether the table has a column for names. A row holds the texts of its cells; mark
 *     and toggle are null for every run but the one the node simulates.
 */
export function describeRuns({ runIds, facts, recorded, activeRunId, state, togglePending, carried = {} }) {
    const named = runIds.some(runId => facts[runId] && facts[runId].name);
    const rows = runIds.map(runId => {
        const live = runId === activeRunId;
        const f = facts[runId], r = recorded[runId];
        const paused = state === 'paused';
        return {
            runId,
            started: startedAt(runId),
            live,
            state: live ? state : null,
            mark: live ? { state, hint: STATE_HINT[state] || '' } : null,
            name: f ? f.name : '',
            environment: f ? { size: f.size, topology: f.topology } : null,
            sampling: numberText(f ? f.sampling : null),
            ticks: ticksText(r),
            alive: numberText(r ? r.alive : null),
            born: numberText(r ? r.born : null),
            toggle: live ? { action: paused ? 'resume' : 'pause', label: paused ? 'Continue' : 'Pause', disabled: togglePending } : null,
            tools: toolLinks(runId, carried)
        };
    });
    return { named, rows };
}
