/**
 * What the start page asks of the node: reads of its state and its runs, and the requests that
 * pause a simulation and continue it.
 *
 * A read answers with the parsed response, or with null where the node does not answer or
 * answers with an error. A caller thus tells "not there" from a value without catching anything.
 *
 * @module NodeApi
 */

import { fetchPipelineStatus } from '/shared/run/RunAvailability.js';

/**
 * @param {string} url
 * @returns {Promise<object|Array|null>}
 */
const read = url => fetch(url).then(response => response.ok ? response.json() : null).catch(() => null);

const ofRun = runId => '?runId=' + encodeURIComponent(runId);

/** @returns {Promise<object|null>} The state of the pipeline with its services */
export const readPipelineStatus = fetchPipelineStatus;

/** @returns {Promise<Array<{runId: string, startTime: number}>|null>} The runs the node has indexed */
export const readRuns = () => read('/analyzer/api/runs');

/**
 * @param {string} runId
 * @returns {Promise<object|null>} What a run recorded about itself: environment, sampling, configuration
 */
export const readMetadata = runId => read('/visualizer/api/simulation/metadata' + ofRun(runId));

/**
 * @param {string} runId
 * @returns {Promise<{minTick: number, maxTick: number}|null>} The first and the last tick the run has indexed
 */
export const readTickRange = runId => read('/visualizer/api/organisms/ticks' + ofRun(runId));

/**
 * @param {string} runId
 * @param {number} tick
 * @returns {Promise<{organisms: Array, totalOrganismCount: number}|null>} The organisms of a recorded tick
 */
export const readOrganisms = (runId, tick) => read('/visualizer/api/organisms/' + tick + ofRun(runId));

/** @returns {Promise<object|null>} The API specification, which names the build of the node */
export const readApiSpecification = () => read('/api-docs/openapi.json');

/**
 * Asks the node to pause the simulation or to continue it. The node accepts every such request,
 * so the outcome is not in the answer: it shows in the state of the pipeline afterwards.
 *
 * @param {'pause'|'resume'} action
 * @returns {Promise<void>}
 */
export const requestSimulation = action => fetch('/pipeline/api/' + action, { method: 'POST' }).then(() => {}, () => {});
