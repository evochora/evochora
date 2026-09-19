/**
 * Camera arithmetic of the environment grid.
 *
 * The camera is the world pixel at the top left corner of the viewport, at the size in pixels per
 * cell that is on screen. A point of the viewport is given in pixels from its top left corner.
 *
 * All functions are pure.
 *
 * @module ViewportMath
 */

/**
 * Returns the camera after a change of size that keeps the world point under the anchor where it
 * is on screen.
 * @param {{x: number, y: number}} camera - Camera before the change.
 * @param {{x: number, y: number}} anchor - Point of the viewport that stays in place.
 * @param {number} oldSize - Pixels per cell before the change.
 * @param {number} newSize - Pixels per cell after the change.
 * @returns {{x: number, y: number}}
 */
export function zoomCamera(camera, anchor, oldSize, newSize) {
    const factor = newSize / oldSize;
    return {
        x: (camera.x + anchor.x) * factor - anchor.x,
        y: (camera.y + anchor.y) * factor - anchor.y,
    };
}

/**
 * Returns the camera kept inside the world. The camera may go beyond the world by a margin on
 * every side; where the world is smaller than the viewport it stays at the margin before it.
 * @param {{x: number, y: number}} camera - Camera to limit.
 * @param {{width: number, height: number}} world - World size in cells.
 * @param {number} size - Pixels per cell.
 * @param {{width: number, height: number}} viewport - Viewport size in pixels.
 * @param {{left: number, top: number, right: number, bottom: number}} margin - Pixels the camera
 *        may show beyond each edge of the world.
 * @returns {{x: number, y: number}}
 */
export function clampCamera(camera, world, size, viewport, margin) {
    const maxX = Math.max(0, world.width * size - viewport.width + margin.right);
    const maxY = Math.max(0, world.height * size - viewport.height + margin.bottom);
    return {
        x: Math.min(Math.max(camera.x, -margin.left), maxX),
        y: Math.min(Math.max(camera.y, -margin.top), maxY),
    };
}

/**
 * Returns the camera moved by one viewport, less the share that stays visible from the last one.
 * The result is not limited to the world; {@link clampCamera} stops it at the edge.
 * @param {{x: number, y: number}} camera - Camera before the move.
 * @param {{x: number, y: number}} direction - -1, 0 or 1 per axis.
 * @param {{width: number, height: number}} viewport - Viewport size in pixels.
 * @param {number} overlap - Share of the viewport, 0 to 1, that the old and the new view share.
 * @returns {{x: number, y: number}}
 */
export function pageCamera(camera, direction, viewport, overlap) {
    return {
        x: camera.x + direction.x * viewport.width * (1 - overlap),
        y: camera.y + direction.y * viewport.height * (1 - overlap),
    };
}
