/**
 * The entries of the Live Demo: one per run of the node, worked out from what the node answers.
 *
 * Everything here takes data and gives data: it reads neither the node nor the page. The experiment
 * name of a run gives the title of its entry and, after its first line break, the description.
 *
 * @module Demo
 */

/**
 * Writes a whole number in groups of three.
 *
 * @param {number} value
 * @returns {string}
 */
const groupDigits = value => String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ',');

/** The name a run carries when its configuration gives it none */
const UNNAMED = 'unnamed';

/**
 * The runs in the order the demo lists them: the newest first.
 *
 * @param {Array<{runId: string, startTime: number}>} listed - The runs the node has indexed
 * @returns {string[]}
 */
export function orderRunIds(listed) {
    return [...listed].sort((a, b) => b.startTime - a.startTime).map(run => run.runId);
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
 * The day a run was started, read from its ID, which begins with the date.
 *
 * @param {string} runId
 * @returns {string} "2026-11-20", or the ID itself where it does not begin with a date
 */
export function startedOn(runId) {
    const m = runId.match(/^(\d{4})(\d{2})(\d{2})/);
    return m ? m[1] + '-' + m[2] + '-' + m[3] : runId;
}

/**
 * The title and the description a run was given in its configuration.
 *
 * @param {string} resolvedConfigJson - The configuration of the run as the node answers it
 * @returns {{name: string, description: string}} Both empty for a run without a readable
 *     configuration and for a run its configuration calls unnamed
 */
export function namingOf(resolvedConfigJson) {
    let experiment;
    try {
        experiment = (JSON.parse(resolvedConfigJson).metadata || {}).experiment;
    } catch (e) {
        return { name: '', description: '' };
    }
    const text = experiment === UNNAMED ? '' : String(experiment || '');
    const cut = text.indexOf('\n');
    return cut < 0 ? { name: text.trim(), description: '' } : { name: text.slice(0, cut).trim(), description: text.slice(cut + 1).trim() };
}

/**
 * How often a run recorded a tick, as the entry writes it.
 *
 * @param {number} sampling - A run records every n-th tick
 * @returns {string}
 */
export function samplingText(sampling) {
    return sampling === 1 ? 'every tick recorded' : 'every ' + groupDigits(sampling) + 'th tick recorded';
}

const plain = text => ({ text, strong: false });
const strong = text => ({ text, strong: true });

/**
 * The facts of an entry, each a short line of parts, the values among them emphasised. A fact
 * that is not known is left out.
 *
 * @param {object|null} metadata - The metadata of the run, or null where the node has none
 * @param {{minTick: number, maxTick: number}|null} tickRange - The recorded ticks, or null where
 *     there are none
 * @param {{totalOrganismCount: number}|null} organisms - The organisms of the last recorded tick,
 *     or null where they could not be read
 * @returns {Array<Array<{text: string, strong: boolean}>>}
 */
function factsOf(metadata, tickRange, organisms) {
    const facts = [];
    if (metadata) {
        const size = metadata.environment.shape.join(' × ');
        const topology = metadata.environment.topology;
        if (size) facts.push(topology ? [strong(size), plain(' ' + topology)] : [strong(size)]);
    }
    // A fork holds a stretch of its parent run: its first tick is not 0, and it is named by that stretch
    if (tickRange) {
        facts.push(tickRange.minTick > 0
            ? [plain('ticks '), strong(groupDigits(tickRange.minTick) + ' – ' + groupDigits(tickRange.maxTick))]
            : [strong(groupDigits(tickRange.maxTick)), plain(' ticks')]);
    }
    if (metadata && metadata.samplingInterval) facts.push([plain(samplingText(metadata.samplingInterval))]);
    if (organisms && organisms.totalOrganismCount !== null && organisms.totalOrganismCount !== undefined) {
        facts.push([strong(groupDigits(organisms.totalOrganismCount)), plain(' organisms born')]);
    }
    return facts;
}

/**
 * Describes the entry of a run.
 *
 * @param {string} runId
 * @param {object|null} metadata - What the run recorded about itself, or null where the node has none
 * @param {{minTick: number|null, maxTick: number|null}|null} tickRange - What the node answers
 *     for the recorded ticks
 * @param {{totalOrganismCount: number}|null} [organisms] - The organisms of the last recorded tick
 * @returns {{title: string, description: string, facts: Array<Array<{text: string, strong: boolean}>>,
 *     links: Array<{label: string, url: string}>}} A run without a name is titled by the day it was
 *     started; the description is empty where there is none
 */
export function describeEntry(runId, metadata, tickRange, organisms = null) {
    const { name, description } = metadata ? namingOf(metadata.resolvedConfigJson) : { name: '', description: '' };
    const query = '?runId=' + encodeURIComponent(runId);
    return {
        title: name || 'Run of ' + startedOn(runId),
        description,
        facts: factsOf(metadata, hasTicks(tickRange) ? tickRange : null, hasTicks(tickRange) ? organisms : null),
        links: [{ label: 'Open in Visualizer', url: '/visualizer/' + query }, { label: 'Open in Analyzer', url: '/analyzer/' + query }]
    };
}
