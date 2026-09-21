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

/** What the keys beside a kind's own carry: the ends of its range, and the births behind it. */
const LOW_SUFFIX = '_low';
const HIGH_SUFFIX = '_high';
const BIRTHS_SUFFIX = '_births';

/** How many standard errors the drawn range reaches on each side. */
const SPREAD_SIGMA = 1.96;

/** The classes that form the control group: no mutation plugin touched these births. */
const CONTROL_CLASSES = ['unchanged', 'no_event'];

/** The kinds held apart, in the order the card draws them. */
const KINDS = ['duplication', 'deletion', 'instruction_insertion', 'label_insertion',
    'substitution'];

/** The narrowest and the widest the run may be cut, however rare or common the kinds are. */
/** How many windows the card is cut into where the config names no levels. */
const DEFAULT_WINDOWS = 40;

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
 * How far the drawn value could be off, as the factor between it and each end of its range.
 *
 * Both sides of the ratio are shares of births that founded a line, and a share is the less certain
 * the fewer births it rests on. The uncertainty of the ratio follows from both, taken on the
 * logarithm where the two add, and comes back as the factor the value is divided and multiplied by.
 * A window whose kind founded no line at all has no such factor: nothing says how far above zero
 * the truth lies.
 *
 * @param {number} rate - Share of the kind's births that founded a line
 * @param {number} births - The kind's births in the window, each parent counted once
 * @param {number} controlRate - The same share among the births no plugin touched
 * @param {number} controlBirths - Those births, counted the same way
 * @returns {number|null} The factor to the lower end, or null where there is none
 */
function spread(rate, births, controlRate, controlBirths) {
    if (!(rate > 0) || !(controlRate > 0) || !(births > 0) || !(controlBirths > 0)) {
        return null;
    }
    const variance = (1 - rate) / (births * rate) + (1 - controlRate) / (controlBirths * controlRate);
    return Math.exp(-SPREAD_SIGMA * Math.sqrt(variance));
}

/**
 * Counts, per window and group, the births and the ones that founded a line, each parent with
 * weight one per group and window however many children it had there.
 *
 * @param {BirthLines.BirthTable} table - The births
 * @param {Int32Array} groups - Per class the index of its group, -1 where the card leaves it out
 * @param {Set<number>} succeeded - The organisms that founded a line
 * @param {number} from - The first tick the card shows
 * @param {number} to - The first tick beyond what it shows
 * @param {number} width - The width of one window
 * @param {number} windows - How many windows the run is cut into
 * @returns {{births: Float64Array, founded: Float64Array}} The weighted counts, group by group and
 *          window by window, each parent counted once per group and window, and beside them the
 *          plain number of births of every cell; the control group comes first, the kinds follow
 */
function countByWindow(table, groups, succeeded, from, to, width, windows) {
    const groupCount = KINDS.length + 1;
    const counted = birth => table.birthTick[birth] >= from && table.birthTick[birth] < to
        && groups[table.variation[birth]] >= 0;
    const cellOf = birth => groups[table.variation[birth]] * windows
        + BirthLines.windowOf(table.birthTick[birth], from, width, windows);
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
    const raw = new Int32Array(groupCount * windows);
    for (let index = 0; index < table.count; index++) {
        if (!counted(index)) {
            continue;
        }
        const cell = cellOf(index);
        const weight = 1 / perParent.get(keyOf(index, cell));
        births[cell] += weight;
        raw[cell]++;
        if (succeeded.has(table.organismId[index])) {
            founded[cell] += weight;
        }
    }
    return { births, founded, raw };
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
 * @param {{from: number, to: number}|null} window - The ticks the card shows, or null for all
 * @param {number} points - How many windows to cut the shown ticks into
 * @returns {Array<Object>} One row per window, or an empty list where there is nothing to draw
 */
export function derive(companion, config, window, points) {
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

    // The card covers the ticks the window names, the same ones every other card covers: a window
    // whose births are too young to judge carries no value rather than shortening the axis
    const from = window?.from ?? table.first;
    const to = window?.to ?? table.last;
    if (!(to > from)) {
        return [];
    }

    // The level says how fine the card is cut. A window carrying too few births is drawn empty
    // rather than made wider: which level fits the card is the card's business, not the data's
    const windows = Math.max(2, Math.round(points || DEFAULT_WINDOWS));
    const classCount = config.variationClasses.length;
    const width = (to - from) / windows;
    const counts = countByWindow(table, groupsByClass(classes, classCount), succeeded, from,
        Math.min(to, censorFrom), width, windows);

    const rows = [];
    for (let index = 0; index < windows; index++) {
        const tick = Math.round(from + (index + 0.5) * width);
        const row = { tick };
        row[CONTROL_SERIES] = 1;
        if (tick >= censorFrom) {
            KINDS.forEach(name => {
                row[name] = null;
                row[name + LOW_SUFFIX] = null;
                row[name + HIGH_SUFFIX] = null;
            });
            rows.push(row);
            continue;
        }
        const controlBirths = counts.births[index];
        const controlFounded = counts.founded[index];
        const controlOdds = controlFounded > 0 ? controlFounded / controlBirths : null;
        const controlRate = controlOdds;
        // Every kind the window holds gets its value, the range that value could as well be, and
        // the births it rests on. The range is what makes a fine window readable: where two kinds
        // overlap, this window says nothing about their difference
        KINDS.forEach((name, kind) => {
            const cell = (kind + 1) * windows + index;
            const births = counts.births[cell];
            const value = controlOdds !== null && births > 0
                ? (counts.founded[cell] / births) / controlOdds : null;
            const range = value === null ? null
                : spread(counts.founded[cell] / births, births, controlRate, controlBirths);
            row[name] = value;
            row[name + LOW_SUFFIX] = range ? value * range : null;
            row[name + HIGH_SUFFIX] = range ? value / range : null;
            row[name + BIRTHS_SUFFIX] = counts.raw[cell];
        });
        rows.push(row);
    }
    return rows;
}
