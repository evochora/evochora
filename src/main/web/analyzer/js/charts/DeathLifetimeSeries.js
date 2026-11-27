import * as BirthLines from './BirthLines.js';

/**
 * Death Lifetime Series
 *
 * Derives how long the organisms that died had lived, as percentiles over the run.
 *
 * A lifetime is the distance from an organism's birth to its death. The death stands in the deaths
 * table and the birth in the births table, one row per organism in each, and the two are joined by
 * the organism. The lifetime is placed at the tick the organism died at, because that is the
 * moment the population lost it.
 *
 * The bands are the percentiles of the lifetimes of all the deaths of a window; the series on the
 * right axis is how many deaths that window holds, which says how much the bands rest on. Many
 * organisms dying at exactly the same age - the children of a copier that forks what cannot live -
 * show as the bands collapsing onto one value.
 *
 * A death whose organism has no row in the births table counts as a death and enters no band: its
 * lifetime is unknown, and a guess would be drawn as a lifetime somebody lived. That is the case
 * for the organisms a run already held when its recording began.
 *
 * It reads the two tables whose metric ids the visualization config names under `deathsMetric` and
 * `birthsMetric`.
 *
 * @module DeathLifetimeSeries
 */

/** How many windows of equal width the shown ticks are cut into where the card names none. */
const DEFAULT_WINDOWS = 40;

/** The percentiles the bands are drawn between, by the key their value gets on a row. */
const PERCENTILES = [
    { key: 'death_lifetime_p10', rank: 0.1 },
    { key: 'death_lifetime_p25', rank: 0.25 },
    { key: 'death_lifetime_p50', rank: 0.5 },
    { key: 'death_lifetime_p75', rank: 0.75 },
    { key: 'death_lifetime_p90', rank: 0.9 }
];

/**
 * Reads when every organism of the births table was born.
 *
 * @param {Object<string, ArrayLike<number|bigint>>} births - The columns `organism_id` and
 *        `birth_tick` of the births table
 * @returns {Map<number, number>} The birth tick of each organism, by its id
 */
function birthTicks(births) {
    const ticks = new Map();
    for (let index = 0; index < births.organism_id.length; index++) {
        ticks.set(Number(births.organism_id[index]), Number(births.birth_tick[index]));
    }
    return ticks;
}

/**
 * Derives the rows of the death lifetimes card from the deaths and the births table.
 *
 * A window is drawn at its middle tick. One without a death carries no percentile and a count of
 * zero: nobody died there, which a band drawn through it would hide.
 *
 * @param {Object<string, Object<string, ArrayLike<*>>>|null} companion - The companions' columns
 * @param {Object} config - Visualization config naming the deaths and the births metric
 * @param {{from: number, to: number}|null} window - The ticks the card shows, or null for all
 * @param {number} points - How many windows to cut the shown ticks into
 * @returns {Array<Object>} One row per window, or an empty list where there is nothing to draw
 */
export function derive(companion, config, window, points) {
    const deaths = companion?.[config.deathsMetric];
    const births = companion?.[config.birthsMetric];
    if (!deaths?.organism_id || !deaths.death_tick || deaths.organism_id.length === 0
        || !births?.organism_id || !births.birth_tick) {
        return [];
    }

    const deathTick = Float64Array.from(deaths.death_tick, Number);
    let first = Infinity;
    let last = -Infinity;
    for (const tick of deathTick) {
        if (tick < first) first = tick;
        if (tick > last) last = tick;
    }
    // The card covers the ticks the window names, the same ones every other card covers
    const from = window?.from ?? first;
    const to = window?.to ?? last;
    if (!(to > from)) {
        return [];
    }

    const count = Math.max(2, Math.round(points || DEFAULT_WINDOWS));
    const width = (to - from) / count;
    const born = birthTicks(births);
    const lifetimes = Array.from({ length: count }, () => []);
    const died = new Array(count).fill(0);
    for (let index = 0; index < deathTick.length; index++) {
        const tick = deathTick[index];
        if (tick < from || tick > to) {
            continue;
        }
        const slot = BirthLines.windowOf(tick, from, width, count);
        died[slot]++;
        const birth = born.get(Number(deaths.organism_id[index]));
        if (birth !== undefined) {
            lifetimes[slot].push(tick - birth);
        }
    }

    const rows = [];
    for (let index = 0; index < count; index++) {
        lifetimes[index].sort((left, right) => left - right);
        const row = { tick: Math.round(from + (index + 0.5) * width), deaths: died[index] };
        for (const percentile of PERCENTILES) {
            row[percentile.key] = BirthLines.quantile(lifetimes[index], percentile.rank);
        }
        rows.push(row);
    }
    return rows;
}
