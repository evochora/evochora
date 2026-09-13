/**
 * The ticks a run holds, as the ticks endpoint reports them: contiguous ranges of recorded
 * ticks, each with one step. A tick exists when it lies in a range and on that range's step; no
 * other tick does, so every navigation goes through these functions instead of assuming a grid
 * from zero.
 *
 * A range is `{ first, last, step }` with `first <= last`, `step >= 1` and `last - first` a
 * multiple of `step`. Ranges are sorted and do not overlap. All functions are pure.
 */

/**
 * Returns the first recorded tick of the run.
 * @param {Array<{first: number, last: number, step: number}>} ranges
 * @returns {number|null} The first tick, or null when nothing is recorded.
 */
export function firstTick(ranges) {
    return ranges.length > 0 ? ranges[0].first : null;
}

/**
 * Returns the last recorded tick of the run.
 * @param {Array<{first: number, last: number, step: number}>} ranges
 * @returns {number|null} The last tick, or null when nothing is recorded.
 */
export function lastTick(ranges) {
    return ranges.length > 0 ? ranges[ranges.length - 1].last : null;
}

/**
 * Returns the number of recorded ticks across all ranges.
 * @param {Array<{first: number, last: number, step: number}>} ranges
 * @returns {number}
 */
export function sampleCount(ranges) {
    return ranges.reduce((count, range) => count + (range.last - range.first) / range.step + 1, 0);
}

/**
 * Returns the step of the range that holds the tick, or of the nearest range when no range
 * holds it, so that a jump from a tick in a gap still has a size.
 * @param {Array<{first: number, last: number, step: number}>} ranges
 * @param {number} tick
 * @returns {number|null} The step, or null when nothing is recorded.
 */
export function stepAt(ranges, tick) {
    const range = nearestRange(ranges, tick);
    return range ? range.step : null;
}

/**
 * Returns the recorded tick nearest to the given tick. On a tie the lower tick wins.
 * @param {Array<{first: number, last: number, step: number}>} ranges
 * @param {number} tick
 * @returns {number|null} The nearest recorded tick, or null when nothing is recorded.
 */
export function snap(ranges, tick) {
    let best = null;
    for (const range of ranges) {
        const candidate = nearestInRange(range, tick);
        if (best === null || Math.abs(candidate - tick) < Math.abs(best - tick)
            || (Math.abs(candidate - tick) === Math.abs(best - tick) && candidate < best)) {
            best = candidate;
        }
    }
    return best;
}

/**
 * Returns the smallest recorded tick greater than the given tick.
 * @param {Array<{first: number, last: number, step: number}>} ranges
 * @param {number} tick
 * @returns {number|null} The next tick, or null when the given tick is at or past the end.
 */
export function next(ranges, tick) {
    for (const range of ranges) {
        if (tick < range.first) {
            return range.first;
        }
        if (tick < range.last) {
            return range.first + (Math.floor((tick - range.first) / range.step) + 1) * range.step;
        }
    }
    return null;
}

/**
 * Returns the largest recorded tick smaller than the given tick.
 * @param {Array<{first: number, last: number, step: number}>} ranges
 * @param {number} tick
 * @returns {number|null} The previous tick, or null when the given tick is at or before the start.
 */
export function previous(ranges, tick) {
    for (let i = ranges.length - 1; i >= 0; i--) {
        const range = ranges[i];
        if (tick > range.last) {
            return range.last;
        }
        if (tick > range.first) {
            return range.first + (Math.ceil((tick - range.first) / range.step) - 1) * range.step;
        }
    }
    return null;
}

/**
 * Moves by a number of ticks and lands on a recorded tick in the direction of travel: forward
 * on the first recorded tick at or after the target, backward on the last recorded tick at or
 * before it. Past either end the move stops at that end.
 * @param {Array<{first: number, last: number, step: number}>} ranges
 * @param {number} tick The tick the move starts from.
 * @param {number} delta The signed number of ticks to move by.
 * @returns {number|null} The tick the move lands on, or null when nothing is recorded.
 */
export function jump(ranges, tick, delta) {
    if (ranges.length === 0) {
        return null;
    }
    const target = tick + delta;
    if (delta >= 0) {
        const landing = contains(ranges, target) ? target : next(ranges, target);
        return landing === null ? lastTick(ranges) : landing;
    }
    const landing = contains(ranges, target) ? target : previous(ranges, target);
    return landing === null ? firstTick(ranges) : landing;
}

/**
 * Whether the tick is a recorded tick.
 * @param {Array<{first: number, last: number, step: number}>} ranges
 * @param {number} tick
 * @returns {boolean}
 */
export function contains(ranges, tick) {
    return ranges.some(range => tick >= range.first && tick <= range.last
        && (tick - range.first) % range.step === 0);
}

/**
 * Cuts the ranges down to the ticks up to and including a limit, dropping ranges that lie
 * beyond it entirely. Used to keep navigation within what every index has reached.
 * @param {Array<{first: number, last: number, step: number}>} ranges
 * @param {number} limit
 * @returns {Array<{first: number, last: number, step: number}>}
 */
export function clip(ranges, limit) {
    const clipped = [];
    for (const range of ranges) {
        if (range.first > limit) {
            break;
        }
        if (range.last <= limit) {
            clipped.push(range);
        } else {
            const last = range.first + Math.floor((limit - range.first) / range.step) * range.step;
            clipped.push({ first: range.first, last, step: range.step });
        }
    }
    return clipped;
}

/**
 * The recorded tick of one range nearest to the tick.
 * @param {{first: number, last: number, step: number}} range
 * @param {number} tick
 * @returns {number}
 */
function nearestInRange(range, tick) {
    if (tick <= range.first) {
        return range.first;
    }
    if (tick >= range.last) {
        return range.last;
    }
    const lower = range.first + Math.floor((tick - range.first) / range.step) * range.step;
    const upper = lower + range.step;
    return tick - lower <= upper - tick ? lower : upper;
}

/**
 * The range that holds the tick, or the one whose nearest tick is nearest.
 * @param {Array<{first: number, last: number, step: number}>} ranges
 * @param {number} tick
 * @returns {{first: number, last: number, step: number}|null}
 */
function nearestRange(ranges, tick) {
    let best = null;
    let bestDistance = Infinity;
    for (const range of ranges) {
        const distance = Math.abs(nearestInRange(range, tick) - tick);
        if (distance < bestDistance) {
            best = range;
            bestDistance = distance;
        }
    }
    return best;
}
