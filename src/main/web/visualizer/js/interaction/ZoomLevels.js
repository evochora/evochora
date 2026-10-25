/**
 * The zoom levels of the environment grid, as pixels per cell.
 *
 * The overview draws a few pixels per cell into a pixel buffer, the detail view draws every cell
 * with its text, at one of a few cell sizes. A gesture may leave the zoom between two levels; it
 * comes to rest on the level nearest to it. Distances between sizes are measured as ratios, so
 * that 9 to 10 pixels is a smaller step than 1 to 2.
 *
 * All functions are pure.
 *
 * @module ZoomLevels
 */

/**
 * Pixels per cell of the overview levels, smallest first. Each level is 1.25 to 2 times the one
 * before it, so that every step of the wheel changes the view by about as much as the last.
 */
export const OVERVIEW_SIZES = Object.freeze([1, 2, 3, 4, 6, 8, 10]);

/** Pixels per cell of the detail levels, smallest first; all larger than the overview sizes. */
export const DETAIL_SIZES = Object.freeze([16, 22, 32]);

/** All zoom levels, smallest first. */
export const ZOOM_LEVELS = Object.freeze([...OVERVIEW_SIZES, ...DETAIL_SIZES]);

/**
 * Returns whether a size belongs to the overview rather than to the detail view.
 * @param {number} size - Pixels per cell.
 * @returns {boolean}
 */
export function isOverviewSize(size) {
    return size <= OVERVIEW_SIZES[OVERVIEW_SIZES.length - 1];
}

/**
 * Returns the size limited to the smallest and the largest level.
 * @param {number[]} levels - Zoom levels, smallest first.
 * @param {number} size - Pixels per cell.
 * @returns {number}
 */
export function clampSize(levels, size) {
    return Math.min(Math.max(size, levels[0]), levels[levels.length - 1]);
}

/**
 * Returns the index of the level nearest to the size, measured as a ratio.
 * @param {number[]} levels - Zoom levels, smallest first.
 * @param {number} size - Pixels per cell.
 * @returns {number}
 */
export function nearestLevelIndex(levels, size) {
    let best = 0;
    for (let i = 1; i < levels.length; i++) {
        if (Math.abs(Math.log(size / levels[i])) < Math.abs(Math.log(size / levels[best]))) best = i;
    }
    return best;
}

/**
 * Returns where the size lies on the scale of level indices: an integer on a level, a fraction
 * between two levels, interpolated by ratio. Used to place the zoom slider while a gesture runs.
 * @param {number[]} levels - Zoom levels, smallest first.
 * @param {number} size - Pixels per cell.
 * @returns {number} A value from 0 to levels.length - 1.
 */
export function levelPosition(levels, size) {
    const clamped = clampSize(levels, size);
    for (let i = 0; i < levels.length - 1; i++) {
        if (clamped <= levels[i + 1]) {
            return i + Math.log(clamped / levels[i]) / Math.log(levels[i + 1] / levels[i]);
        }
    }
    return levels.length - 1;
}

/**
 * Returns the level one step from the size. From a size between two levels the step ends on the
 * next level in its direction; at either end the size stays on the last level.
 * @param {number[]} levels - Zoom levels, smallest first.
 * @param {number} size - Pixels per cell.
 * @param {number} direction - Positive to zoom in, negative to zoom out.
 * @returns {number} The size of the level stepped to.
 */
export function stepLevel(levels, size, direction) {
    // A size within a thousandth of a level counts as that level
    const tolerance = 1.001;
    if (direction > 0) {
        return levels.find(level => level > size * tolerance) ?? levels[levels.length - 1];
    }
    return [...levels].reverse().find(level => level < size / tolerance) ?? levels[0];
}
