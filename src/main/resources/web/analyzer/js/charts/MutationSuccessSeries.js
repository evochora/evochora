import * as BirthLines from './BirthLines.js';

/**
 * Mutation Success Series
 *
 * Derives what a mutation was worth: how often a birth that received a given kind founds a line
 * that goes on, held against the births no mutation plugin touched.
 *
 * Those untouched births are the control group - the classes `unchanged` and `no_event` together,
 * a genome the copier handed on as it was or changed by itself. They are what the run does without
 * a plugin, so the card draws them as a straight 1 and every kind as its odds divided by theirs. A
 * kind that stays far below 1 is a cliff: the mutation is made and the lines it makes end.
 *
 * Each of the five kinds a mutation plugin of this project reports forms its own group. Births
 * without a body are left out, since nothing was handed over; births of two kinds at once and
 * births from a plugin outside this project have no group here, because what would be measured is
 * not one mutation.
 *
 * The windows are as wide as the rarest kind needs: a point stands on a few hundred births or it
 * stands on nothing.
 *
 * It reads the births table whose metric id the visualization config names under `birthsMetric`,
 * one row per birth, and resolves the classes of those births through `variationClasses`.
 *
 * @module MutationSuccessSeries
 */

/** The series the kinds are measured against, constant 1. */
const CONTROL_SERIES = 'no_plugin_mutation';

/** The classes that form the control group: no mutation plugin touched these births. */
const CONTROL_CLASSES = ['unchanged', 'no_event'];

/** The kinds held apart, in the order the card draws them. */
const KINDS = ['duplication', 'deletion', 'instruction_insertion', 'label_insertion',
    'substitution'];

/** How many births of the rarest kind one window should stand on. */
const BIRTHS_PER_WINDOW = 300;

/** The narrowest and the widest the run may be cut, however rare or common the kinds are. */
const MIN_WINDOWS = 8;
const MAX_WINDOWS = 40;

/**
 * Maps every class of a birth to the group it counts in.
 *
 * @param {Object<string, number>} classes - The index of every class the card names
 * @param {number} classCount - How many classes there are
 * @returns {Int32Array} Per class the index of its group, -1 for a class the card leaves out
 */
function groupsByClass(classes, classCount) {
    const groups = new Int32Array(classCount).fill(-1);
    for (const name of CONTROL_CLASSES) {
        groups[classes[name]] = 0;
    }
    KINDS.forEach((name, kind) => {
        groups[classes[name]] = kind + 1;
    });
    return groups;
}

/**
 * Chooses how many windows the run is cut into: enough births of the rarest kind in each of them
 * that its point means something, and never so few windows or so many that the card stops showing
 * a course over time.
 *
 * @param {BirthLines.BirthTable} table - The births
 * @param {Object<string, number>} classes - The index of every class the card names
 * @param {number} classCount - How many classes there are
 * @param {number} censorFrom - The first tick whose births are left out
 * @returns {number} How many windows to cut
 */
function windowCount(table, classes, classCount, censorFrom) {
    const perClass = new Float64Array(classCount);
    for (let index = 0; index < table.count; index++) {
        if (table.birthTick[index] < censorFrom) {
            perClass[table.variation[index]]++;
        }
    }
    let rarest = Infinity;
    for (const name of KINDS) {
        rarest = Math.min(rarest, perClass[classes[name]]);
    }
    return Math.max(MIN_WINDOWS, Math.min(MAX_WINDOWS, Math.floor(rarest / BIRTHS_PER_WINDOW)));
}

/**
 * Counts, per window and group, the births and the ones that founded a line, each parent with
 * weight one per group and window however many children it had there.
 *
 * @param {BirthLines.BirthTable} table - The births
 * @param {Int32Array} groups - Per class the index of its group, -1 where the card leaves it out
 * @param {Set<number>} succeeded - The organisms that founded a line
 * @param {number} censorFrom - The first tick whose births are left out
 * @param {number} width - The width of one window
 * @param {number} windows - How many windows the run is cut into
 * @returns {{births: Float64Array, founded: Float64Array}} The weighted counts, group by group and
 *          window by window; the control group comes first, the kinds follow in their order
 */
function countByWindow(table, groups, succeeded, censorFrom, width, windows) {
    const groupCount = KINDS.length + 1;
    const counted = birth => table.birthTick[birth] < censorFrom
        && groups[table.variation[birth]] >= 0;
    const cellOf = birth => groups[table.variation[birth]] * windows
        + BirthLines.windowOf(table.birthTick[birth], table.first, width, windows);
    const keyOf = (birth, cell) => table.parentId[birth] * groupCount * windows + cell;

    const perParent = new Map();
    for (let index = 0; index < table.count; index++) {
        if (counted(index)) {
            const key = keyOf(index, cellOf(index));
            perParent.set(key, (perParent.get(key) || 0) + 1);
        }
    }

    const births = new Float64Array(groupCount * windows);
    const founded = new Float64Array(groupCount * windows);
    for (let index = 0; index < table.count; index++) {
        if (!counted(index)) {
            continue;
        }
        const cell = cellOf(index);
        const weight = 1 / perParent.get(keyOf(index, cell));
        births[cell] += weight;
        if (succeeded.has(table.organismId[index])) {
            founded[cell] += weight;
        }
    }
    return { births, founded };
}

/**
 * Derives the rows of the mutation success card from the births table.
 *
 * Each window is drawn at its middle tick. A kind whose window carries too few weighted births is
 * left out of it, and so is every kind of a window whose control group founded no line at all:
 * there is then nothing to be measured against.
 *
 * @param {Object<string, Object<string, ArrayLike<*>>>|null} companion - The companions' columns
 * @param {Object} config - Visualization config naming the births metric and the classes
 * @returns {Array<Object>} One row per window, or an empty list where there is nothing to draw
 */
export function derive(companion, config) {
    const table = BirthLines.readBirths(companion, config);
    const classes = BirthLines.classIndices(config, [...CONTROL_CLASSES, ...KINDS, 'bodiless']);
    if (!table || !classes) {
        return [];
    }

    const firstChild = BirthLines.firstChildren(table, classes.bodiless);
    const succeeded = BirthLines.succeedingParents(table, firstChild, classes.bodiless);
    const censorFrom = BirthLines.censorFrom(table, firstChild);
    if (censorFrom <= table.first) {
        return [];
    }

    const classCount = config.variationClasses.length;
    const windows = windowCount(table, classes, classCount, censorFrom);
    const width = (censorFrom - table.first) / windows;
    const counts = countByWindow(table, groupsByClass(classes, classCount), succeeded, censorFrom,
        width, windows);

    const rows = [];
    for (let window = 0; window < windows; window++) {
        const row = { tick: Math.round(table.first + (window + 0.5) * width) };
        row[CONTROL_SERIES] = 1;
        const controlBirths = counts.births[window];
        const controlFounded = counts.founded[window];
        const controlOdds = controlFounded > 0 ? controlFounded / controlBirths : null;
        KINDS.forEach((name, kind) => {
            const cell = (kind + 1) * windows + window;
            const births = counts.births[cell];
            row[name] = controlOdds !== null && births >= BirthLines.MIN_WEIGHTED_BIRTHS
                ? (counts.founded[cell] / births) / controlOdds : null;
        });
        rows.push(row);
    }
    return rows;
}
