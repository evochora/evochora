/**
 * What the homepage asks of the Evochora node behind it: the runs of its Live Demo.
 *
 * @module NodeApi
 */

import { readJson } from './Http.js';

const ofRun = runId => '?runId=' + encodeURIComponent(runId);

/** @returns {Promise<Array<{runId: string, startTime: number}>|null>} The runs the node has indexed */
export const readRuns = () => readJson('/analyzer/api/runs');

/**
 * @param {string} runId
 * @returns {Promise<object|null>} What a run recorded about itself: environment, sampling, configuration
 */
export const readMetadata = runId => readJson('/visualizer/api/simulation/metadata' + ofRun(runId));

/**
 * @param {string} runId
 * @returns {Promise<{minTick: number|null, maxTick: number|null}|null>} The first and the last tick
 *     the run has indexed
 */
export const readTickRange = runId => readJson('/visualizer/api/organisms/ticks' + ofRun(runId));

/**
 * @param {string} runId
 * @param {number} tick
 * @returns {Promise<{organisms: Array, totalOrganismCount: number}|null>} The organisms of a recorded tick
 */
export const readOrganisms = (runId, tick) => readJson('/visualizer/api/organisms/' + tick + ofRun(runId));
