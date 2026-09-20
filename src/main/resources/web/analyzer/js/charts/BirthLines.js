/**
 * Birth Lines
 *
 * What the cards derived from the births table have in common: reading that table, telling which
 * births led anywhere, and deciding which births are too young to be judged at all.
 *
 * Both cards ask the same question of the same rows - whether a birth founded a line that goes on -
 * and differ only in how they cut the run and what they hold the answer against. The rules they
 * share live here, so that a line means the same thing on either card.
 *
 * <strong>A birth without a body is no reproduction.</strong> Nothing was handed over, so such a
 * birth counts neither as a child of its parent nor as a carrier of anything of its own.
 *
 * <strong>Success is a line that goes on.</strong> An organism succeeded when it has a child with a
 * body that itself has a child with a body. A single defective copier that forks thousands of
 * sterile children is then no success, and none of those children is one either.
 *
 * <strong>A parent counts once.</strong> The births of one parent are not independent: they carry
 * the same genome and the same defect. Wherever births are counted, every parent enters a window
 * with weight one, split over the births it had there.
 *
 * @module BirthLines
 */

/**
 * The columns of the births table the derivations read, by the field they get on a table.
 *
 * The genome hashes are left out: they are 64 bits wide and would not survive the conversion to
 * JavaScript numbers, and no derivation here needs them.
 */
const COLUMNS = {
    birthTick: 'birth_tick',
    parentBirthTick: 'parent_birth_tick',
    organismId: 'organism_id',
    parentId: 'parent_id',
    variation: 'variation'
};

/**
 * How many weighted births a window must carry for a point to be drawn. Below that the point says
 * more about the handful of parents behind it than about the run.
 */
export const MIN_WEIGHTED_BIRTHS = 100;

/**
 * The births table as the cards read it.
 *
 * @typedef {Object} BirthTable
 * @property {number} count - How many births the table holds
 * @property {number} first - The earliest birth tick in the table
 * @property {number} last - The latest birth tick in the table
 * @property {Float64Array} birthTick - When each newborn was born
 * @property {Float64Array} parentBirthTick - When the parent of each newborn was born
 * @property {Float64Array} organismId - The newborn
 * @property {Float64Array} parentId - The organism it was replicated from
 * @property {Float64Array} variation - The index of the class the birth was sorted into
 */

/**
 * One parent's earliest child, with what is needed to measure the step from one to the other.
 *
 * @typedef {Object} FirstChild
 * @property {number} childTick - When that child was born
 * @property {number} parentBirthTick - When the parent itself was born
 */

/**
 * Converts one column into plain numbers.
 *
 * A 64-bit column arrives as BigInt values, which no arithmetic here accepts. Every column read
 * this way holds ticks or organism ids, far below the range a JavaScript number carries exactly,
 * so the conversion loses nothing - and it happens once per column rather than once per access.
 *
 * @param {ArrayLike<number|bigint>} column - The column as the query returned it
 * @returns {Float64Array} The same values as numbers
 */
function toNumbers(column) {
    const values = new Float64Array(column.length);
    for (let index = 0; index < column.length; index++) {
        values[index] = Number(column[index]);
    }
    return values;
}

/**
 * Reads the births table a card names as its companion.
 *
 * @param {Object<string, Object<string, ArrayLike<*>>>|null} companion - The companions' columns
 * @param {Object} config - Visualization config, naming the births metric under `birthsMetric`
 * @returns {BirthTable|null} The table, or null where the card has no births to read
 */
export function readBirths(companion, config) {
    const columns = companion?.[config.birthsMetric];
    if (!columns) {
        return null;
    }
    const table = {};
    for (const field of Object.keys(COLUMNS)) {
        const column = columns[COLUMNS[field]];
        if (!column || column.length === 0) {
            return null;
        }
        table[field] = toNumbers(column);
    }
    table.count = table.birthTick.length;
    let first = Infinity;
    let last = -Infinity;
    for (let index = 0; index < table.count; index++) {
        const tick = table.birthTick[index];
        if (tick < first) first = tick;
        if (tick > last) last = tick;
    }
    table.first = first;
    table.last = last;
    return table;
}

/**
 * Resolves class names to the indices the `variation` column holds.
 *
 * The manifest states the classes in the order they are numbered in, so that the browser keeps no
 * second list of them that could drift from the one the run was written with.
 *
 * @param {Object} config - Visualization config, carrying the classes under `variationClasses`
 * @param {Array<string>} names - The classes the caller needs
 * @returns {Object<string, number>|null} The index of each name, or null where one is unknown
 */
export function classIndices(config, names) {
    const classes = config.variationClasses;
    if (!Array.isArray(classes)) {
        return null;
    }
    const indices = {};
    for (const name of names) {
        const index = classes.indexOf(name);
        if (index < 0) {
            return null;
        }
        indices[name] = index;
    }
    return indices;
}

/**
 * Reads a quantile from an ascending array.
 *
 * The same definition serves every card, so that a median on one reads like a median on the other.
 *
 * @param {ArrayLike<number>} sorted - The values in ascending order
 * @param {number} quantileRank - Between 0 and 1
 * @returns {number|null} The value at that rank, or null where there is none
 */
export function quantile(sorted, quantileRank) {
    if (sorted.length === 0) {
        return null;
    }
    return sorted[Math.min(sorted.length - 1, Math.floor(quantileRank * sorted.length))];
}

/**
 * The window a tick falls into, counted from the start of the range.
 *
 * A range of zero width - every birth of the table at one tick - puts everything into the first
 * window, where a division would give no index at all.
 *
 * @param {number} tick - The tick to place
 * @param {number} first - The start of the range
 * @param {number} width - The width of one window
 * @param {number} count - How many windows the range is cut into
 * @returns {number} The index of the window
 */
export function windowOf(tick, first, width, count) {
    if (!(width > 0)) {
        return 0;
    }
    return Math.min(count - 1, Math.floor((tick - first) / width));
}

/**
 * Finds the earliest child with a body of every parent.
 *
 * @param {BirthTable} table - The births
 * @param {number} bodiless - The class of a birth without a body
 * @returns {Map<number, FirstChild>} Per parent its earliest child; a parent without one is absent
 */
export function firstChildren(table, bodiless) {
    const first = new Map();
    for (let index = 0; index < table.count; index++) {
        if (table.variation[index] === bodiless) {
            continue;
        }
        const parent = table.parentId[index];
        const tick = table.birthTick[index];
        const seen = first.get(parent);
        if (!seen || tick < seen.childTick) {
            first.set(parent, { childTick: tick, parentBirthTick: table.parentBirthTick[index] });
        }
    }
    return first;
}

/**
 * Collects the organisms whose line goes on: those with a child with a body that itself has a
 * child with a body.
 *
 * @param {BirthTable} table - The births
 * @param {Map<number, FirstChild>} firstChild - Per parent its earliest child with a body
 * @param {number} bodiless - The class of a birth without a body
 * @returns {Set<number>} The organisms that founded a line
 */
export function succeedingParents(table, firstChild, bodiless) {
    const succeeded = new Set();
    for (let index = 0; index < table.count; index++) {
        if (table.variation[index] !== bodiless && firstChild.has(table.organismId[index])) {
            succeeded.add(table.parentId[index]);
        }
    }
    return succeeded;
}

/**
 * The tick from which the births of the table are too young to be judged.
 *
 * Whether a birth founded a line is only visible once a grandchild could have been born, which
 * takes about two steps from one generation to the next. The slow end of that step is taken from
 * the parents whose first child arrived in the last tenth of the table, so that a run whose pace
 * changed is measured by its present pace rather than by its beginning.
 *
 * The end of the range is the last birth tick <em>in the table</em>, not the last tick of the run
 * or of the tick window the card shows: a table may lag behind the run it comes from, and judging
 * against anything later would make the newest births of the table look sterile and draw a cliff
 * that is not there.
 *
 * @param {BirthTable} table - The births
 * @param {Map<number, FirstChild>} firstChild - Per parent its earliest child with a body
 * @returns {number} The first tick whose births are left out
 */
export function censorFrom(table, firstChild) {
    const lateTenth = table.last - (table.last - table.first) / 10;
    const steps = [];
    for (const child of firstChild.values()) {
        if (child.childTick > lateTenth) {
            steps.push(child.childTick - child.parentBirthTick);
        }
    }
    steps.sort((left, right) => left - right);
    const slowStep = quantile(steps, 0.9);
    // Without a single parent whose first child arrived late, nothing says how long judging takes,
    // and only the last tick of the table itself is cut
    return slowStep === null ? table.last : table.last - 2 * slowStep;
}
