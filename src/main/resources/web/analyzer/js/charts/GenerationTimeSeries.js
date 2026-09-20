import * as BirthLines from './BirthLines.js';

/**
 * Generation Time Series
 *
 * Derives the clock of evolution in a run: how long a line needs for one step, and how often a
 * birth leads anywhere at all.
 *
 * The generation time of an organism is the distance from its own birth to the birth of its first
 * child that itself has a child with a body. Children that lead nowhere - the bodiless ones, the
 * sterile ones, the output of a broken copier - do not end a generation, so they do not enter.
 * The time is placed at the tick that child was born at, because that is when the step was
 * completed and the state of the run it describes is the one of that moment.
 *
 * The bands over the run are the percentiles of those times per window; the series on the right
 * axis is the share of newborns that founded a line at all, which says how much of the population
 * the bands are drawn from.
 *
 * It reads the births table whose metric id the visualization config names under `birthsMetric`,
 * one row per birth, and resolves the classes of those births through `variationClasses`.
 *
 * @module GenerationTimeSeries
 */

/** How many windows of equal width the run is cut into. */
const WINDOW_COUNT = 40;

/** The percentiles the bands are drawn between, from the outermost pair to the median. */
const PERCENTILES = [
    { key: 'p10', rank: 0.1 },
    { key: 'p25', rank: 0.25 },
    { key: 'p50', rank: 0.5 },
    { key: 'p75', rank: 0.75 },
    { key: 'p90', rank: 0.9 }
];

/**
 * Finds the earliest child of every parent that carried the line on: a child with a body that
 * itself has a child with a body.
 *
 * @param {BirthLines.BirthTable} table - The births
 * @param {Map<number, Object>} firstChild - Per parent its earliest child with a body
 * @param {number} bodiless - The class of a birth without a body
 * @param {number} censorFrom - The first tick whose births are left out
 * @returns {Array<{childTick: number, parentBirthTick: number}>} One completed step per parent
 */
function completedSteps(table, firstChild, bodiless, censorFrom) {
    const steps = new Map();
    for (let index = 0; index < table.count; index++) {
        const tick = table.birthTick[index];
        if (table.variation[index] === bodiless || tick >= censorFrom
            || !firstChild.has(table.organismId[index])) {
            continue;
        }
        const parent = table.parentId[index];
        const seen = steps.get(parent);
        if (!seen || tick < seen.childTick) {
            steps.set(parent, { childTick: tick, parentBirthTick: table.parentBirthTick[index] });
        }
    }
    return [...steps.values()];
}

/**
 * Sorts the generation times into the windows they were completed in.
 *
 * @param {Array<{childTick: number, parentBirthTick: number}>} steps - The completed steps
 * @param {number} first - The start of the range
 * @param {number} width - The width of one window
 * @returns {Array<Array<number>>} Per window its generation times, in ascending order
 */
function timesByWindow(steps, first, width) {
    const times = Array.from({ length: WINDOW_COUNT }, () => []);
    for (const step of steps) {
        times[BirthLines.windowOf(step.childTick, first, width, WINDOW_COUNT)]
            .push(step.childTick - step.parentBirthTick);
    }
    for (const window of times) {
        window.sort((left, right) => left - right);
    }
    return times;
}

/**
 * Counts, per window, how many newborns with a body there were and how many of them founded a
 * line, each parent with weight one however many children it had in that window.
 *
 * @param {BirthLines.BirthTable} table - The births
 * @param {Set<number>} succeeded - The organisms that founded a line
 * @param {number} bodiless - The class of a birth without a body
 * @param {number} censorFrom - The first tick whose births are left out
 * @param {number} first - The start of the range
 * @param {number} width - The width of one window
 * @returns {Array<{births: number, founded: number}>} The weighted counts per window
 */
function foundedByWindow(table, succeeded, bodiless, censorFrom, first, width) {
    const counted = birth => table.variation[birth] !== bodiless
        && table.birthTick[birth] < censorFrom;
    const keyOf = birth => table.parentId[birth] * WINDOW_COUNT
        + BirthLines.windowOf(table.birthTick[birth], first, width, WINDOW_COUNT);

    const perParent = new Map();
    for (let index = 0; index < table.count; index++) {
        if (counted(index)) {
            const key = keyOf(index);
            perParent.set(key, (perParent.get(key) || 0) + 1);
        }
    }

    const windows = Array.from({ length: WINDOW_COUNT }, () => ({ births: 0, founded: 0 }));
    for (let index = 0; index < table.count; index++) {
        if (!counted(index)) {
            continue;
        }
        const window = windows[BirthLines.windowOf(table.birthTick[index], first, width, WINDOW_COUNT)];
        const weight = 1 / perParent.get(keyOf(index));
        window.births += weight;
        if (succeeded.has(table.organismId[index])) {
            window.founded += weight;
        }
    }
    return windows;
}

/**
 * Derives the rows of the generation time card from the births table.
 *
 * A window is drawn at its middle tick. One that lies at or after the tick from which births are
 * too young to be judged carries nothing: its bands would be drawn from the few lines that were
 * quick enough to be visible already, and its share would count every newborn still waiting for a
 * grandchild as one that found nothing.
 *
 * @param {Object<string, Object<string, ArrayLike<*>>>|null} companion - The companions' columns
 * @param {Object} config - Visualization config naming the births metric and the classes
 * @returns {Array<Object>} One row per window, or an empty list where there is nothing to draw
 */
export function derive(companion, config) {
    const table = BirthLines.readBirths(companion, config);
    const classes = BirthLines.classIndices(config, ['bodiless']);
    if (!table || !classes) {
        return [];
    }

    const firstChild = BirthLines.firstChildren(table, classes.bodiless);
    const succeeded = BirthLines.succeedingParents(table, firstChild, classes.bodiless);
    const censorFrom = BirthLines.censorFrom(table, firstChild);
    if (censorFrom <= table.first) {
        return [];
    }

    const width = (table.last - table.first) / WINDOW_COUNT;
    const steps = completedSteps(table, firstChild, classes.bodiless, censorFrom);
    const times = timesByWindow(steps, table.first, width);
    const founded = foundedByWindow(table, succeeded, classes.bodiless, censorFrom,
        table.first, width);

    const rows = [];
    for (let window = 0; window < WINDOW_COUNT; window++) {
        const tick = Math.round(table.first + (window + 0.5) * width);
        const judged = tick < censorFrom;
        const row = { tick };
        for (const percentile of PERCENTILES) {
            row[percentile.key] = judged
                ? BirthLines.quantile(times[window], percentile.rank) : null;
        }
        row.newborns_that_found_a_line =
            judged && founded[window].births >= BirthLines.MIN_WEIGHTED_BIRTHS
                ? 100 * founded[window].founded / founded[window].births : null;
        rows.push(row);
    }
    return rows;
}
