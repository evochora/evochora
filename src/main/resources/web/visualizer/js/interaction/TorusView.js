/**
 * Arithmetic of a toroidal world on screen.
 *
 * A torus has no edge: past the last column lies the first. The view is a window onto an endless
 * repetition of the world, but each cell is shown once — where the view is wider than the world,
 * the world is shown once around the view's centre and its seam wanders through it as the view
 * moves. A stretch of the view is therefore split at the seam into stretches of the world, each of
 * which can be loaded and drawn like a part of a bounded world.
 *
 * All functions are pure.
 *
 * @module TorusView
 */

/**
 * Returns the value wrapped into [0, period).
 * @param {number} value
 * @param {number} period - Positive.
 * @returns {number}
 */
export function wrap(value, period) {
    return ((value % period) + period) % period;
}

/**
 * Returns the stretches of the world, each within [0, period), that a stretch of the view covers.
 * A view stretch that crosses the seam gives two; one at least as long as the world gives the
 * whole world once.
 * @param {number} start - Start of the view stretch, in world units, unwrapped.
 * @param {number} end - End of the view stretch, exclusive, greater than start.
 * @param {number} period - Length of the world.
 * @returns {Array<[number, number]>} Stretches [from, to), to exclusive.
 */
export function splitSpan(start, end, period) {
    if (end - start >= period) return [[0, period]];
    const from = wrap(start, period);
    const to = from + (end - start);
    if (to <= period) return [[from, to]];
    return [[from, period], [0, to - period]];
}

/**
 * Returns the regions of the world that a region of the view covers: up to four at a corner of
 * the world, where both axes cross the seam.
 * @param {{x1: number, x2: number, y1: number, y2: number}} region - View region in cells,
 *        unwrapped, x2 and y2 exclusive.
 * @param {number} width - World width in cells.
 * @param {number} height - World height in cells.
 * @returns {Array<{x1: number, x2: number, y1: number, y2: number}>}
 */
export function splitRegion(region, width, height) {
    const pieces = [];
    for (const [x1, x2] of splitSpan(region.x1, region.x2, width)) {
        for (const [y1, y2] of splitSpan(region.y1, region.y2, height)) {
            pieces.push({ x1, x2, y1, y2 });
        }
    }
    return pieces;
}

/**
 * Returns the shorter distance between two positions on a circle of the given length.
 * @param {number} a
 * @param {number} b
 * @param {number} period - Length of the circle.
 * @returns {number} From 0 to period / 2.
 */
export function circularDistance(a, b, period) {
    const d = wrap(a - b, period);
    return Math.min(d, period - d);
}
