import {
    moleculeTypeEntry,
    EMPTY_CELL_COLOR,
    NO_DATA_COLOR,
    UNKNOWN_TYPE_NAME
} from '../../MoleculeTypePalette.js';

/**
 * Renders minimap data onto a canvas using putImageData for optimal performance.
 * This class is stateless except for the canvas reference and the resolved colour table.
 *
 * The server sends one byte per pixel: the raw index of a registered molecule type, or one of two
 * sentinels for what is not a type. A raw index is resolved to a type name through the run
 * metadata's `moleculeTypes` map, whose keys carry the type bits shifted by the metadata's
 * `moleculeTypeShift`, and coloured from {@link MOLECULE_TYPE_PALETTE}, so grid and minimap show
 * the same type in the same colour.
 *
 * @class MinimapRenderer
 */
export class MinimapRenderer {

    /** Minimap byte of a pixel holding no molecule. */
    static BYTE_EMPTY = 255;

    /** Minimap byte of a molecule whose type the server's registry does not know. */
    static BYTE_UNKNOWN = 254;

    /**
     * Creates a new MinimapRenderer.
     *
     * Until {@link setMoleculeTypes} has supplied the run's type map and shift, no molecule byte
     * can be resolved to a type name and every one of them is drawn in the UNKNOWN colour; the two
     * sentinels are coloured from the start.
     *
     * @param {HTMLCanvasElement} canvas - The canvas element to render onto.
     */
    constructor(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d', { alpha: false });
        this.moleculeTypes = null;
        this.typeShift = null;
        this.colorByByte = this.buildColorTable();
        this.lastMinimapData = null;

        // Off-screen canvas for caching background (environment + organisms)
        this._cacheCanvas = document.createElement('canvas');
        this._cacheCtx = this._cacheCanvas.getContext('2d', { alpha: false });
    }

    /**
     * Sets the run's molecule type map and the bit position its keys are shifted by, and rebuilds
     * the colour table from them.
     *
     * @param {object|null|undefined} moleculeTypes - Metadata map of the shifted type constant,
     *        as a string, to the type name, e.g. {"0": "CODE", "1048576": "DATA"}.
     * @param {number|null|undefined} typeShift - The metadata's `moleculeTypeShift`, the bit
     *        position of the type inside a packed molecule.
     */
    setMoleculeTypes(moleculeTypes, typeShift) {
        this.moleculeTypes = moleculeTypes || null;
        this.typeShift = Number.isInteger(typeShift) ? typeShift : null;
        this.colorByByte = this.buildColorTable();
        if (this.lastMinimapData) {
            this.render(this.lastMinimapData);
        }
    }

    /**
     * Builds the colour of every possible minimap byte. A byte that names a type the metadata
     * does not resolve is drawn in the UNKNOWN colour, so an unexpected type is visible.
     *
     * @returns {number[]} 256 colours as 0xRRGGBB integers, indexed by minimap byte.
     * @private
     */
    buildColorTable() {
        const unknownColor = moleculeTypeEntry(UNKNOWN_TYPE_NAME).bg;
        const table = new Array(256).fill(unknownColor);
        if (this.moleculeTypes && this.typeShift !== null) {
            for (let byte = 0; byte < MinimapRenderer.BYTE_UNKNOWN; byte++) {
                const typeName = this.moleculeTypes[String(byte << this.typeShift)];
                table[byte] = typeName ? moleculeTypeEntry(typeName).bg : unknownColor;
            }
        }
        table[MinimapRenderer.BYTE_UNKNOWN] = unknownColor;
        table[MinimapRenderer.BYTE_EMPTY] = EMPTY_CELL_COLOR;
        return table;
    }

    /**
     * Renders minimap cell data onto the canvas.
     * Uses putImageData for optimal performance (~0.5ms for 150x150 pixels).
     *
     * @param {{width: number, height: number, cellTypes: Uint8Array}} minimapData - Minimap data from server.
     */
    render(minimapData) {
        if (!minimapData || !minimapData.cellTypes) {
            return;
        }

        const { width, height, cellTypes } = minimapData;
        this.lastMinimapData = minimapData;

        // Resize canvas if needed
        if (this.canvas.width !== width || this.canvas.height !== height) {
            this.canvas.width = width;
            this.canvas.height = height;
        }

        // Create ImageData and fill pixels
        const imageData = this.ctx.createImageData(width, height);
        const pixels = imageData.data;

        for (let i = 0; i < cellTypes.length; i++) {
            const color = this.colorByByte[cellTypes[i]];

            const p = i << 2; // i * 4
            pixels[p]     = (color >> 16) & 0xFF; // R
            pixels[p + 1] = (color >> 8) & 0xFF;  // G
            pixels[p + 2] = color & 0xFF;         // B
            pixels[p + 3] = 255;                  // A (fully opaque)
        }

        this.ctx.putImageData(imageData, 0, 0);
    }

    /**
     * Draws the viewport rectangle showing the currently visible area.
     * Should be called after render() to overlay the rectangle.
     *
     * Uses the same floating-point scale calculation as MinimapAggregator.java on the server
     * to ensure the rect aligns correctly with the minimap background.
     *
     * @param {{x: number, y: number, width: number, height: number}} viewportBounds - Viewport in world coordinates.
     * @param {number[]} worldShape - World dimensions [width, height].
     */
    drawViewportRect(viewportBounds, worldShape) {
        if (!this.lastMinimapData || !worldShape || worldShape.length < 2) {
            return;
        }

        const { x, y, width, height } = viewportBounds;
        const [worldWidth, worldHeight] = worldShape;
        const minimapWidth = this.canvas.width;
        const minimapHeight = this.canvas.height;

        // Use the SAME floating-point scale calculation as MinimapAggregator.java:
        // scaleX = worldWidth / minimapWidth (float division)
        // This ensures the entire world maps to the minimap without clipping.
        const scaleX = worldWidth / minimapWidth;
        const scaleY = worldHeight / minimapHeight;

        // Clamp viewport bounds to world bounds (minimap only shows the world, not margin areas)
        const clampedX1 = Math.max(0, x);
        const clampedY1 = Math.max(0, y);
        const clampedX2 = Math.min(worldWidth, x + width);
        const clampedY2 = Math.min(worldHeight, y + height);

        // Skip if viewport is entirely outside the world
        if (clampedX1 >= clampedX2 || clampedY1 >= clampedY2) {
            return;
        }

        // Map world coordinates to minimap pixels using float scale (same as server)
        const rectX = clampedX1 / scaleX;
        const rectY = clampedY1 / scaleY;
        const rectX2 = clampedX2 / scaleX;
        const rectY2 = clampedY2 / scaleY;
        const rectW = rectX2 - rectX;
        const rectH = rectY2 - rectY;

        // Draw semi-transparent white rectangle with border
        this.ctx.strokeStyle = 'rgba(255, 255, 255, 0.9)';
        this.ctx.lineWidth = 2;
        this.ctx.strokeRect(rectX, rectY, rectW, rectH);

        // Inner subtle fill to make it more visible
        this.ctx.fillStyle = 'rgba(255, 255, 255, 0.1)';
        this.ctx.fillRect(rectX, rectY, rectW, rectH);
    }

    /**
     * Caches the current canvas content (environment + organisms) for fast restoration.
     * Call this after rendering environment and organism overlay, before drawing viewport rect.
     */
    cacheBackground() {
        if (this._cacheCanvas.width !== this.canvas.width ||
            this._cacheCanvas.height !== this.canvas.height) {
            this._cacheCanvas.width = this.canvas.width;
            this._cacheCanvas.height = this.canvas.height;
        }
        this._cacheCtx.drawImage(this.canvas, 0, 0);
    }

    /**
     * Restores the cached background (environment + organisms) onto the canvas.
     * Use this before drawing the viewport rect to avoid full re-render.
     */
    restoreBackground() {
        this.ctx.drawImage(this._cacheCanvas, 0, 0);
    }

    /**
     * Renders minimap with ownership coloring instead of cell type coloring.
     * Each pixel is colored by the dominant owner organism at that location.
     * Unowned pixels (ownerId=0) and pixels where the resolver returns -1
     * (unknown/dead organism) use the no-data background color.
     *
     * @param {{width: number, height: number, ownerIds: number[]}} minimapData - Minimap data with owner IDs.
     * @param {function(number): number} colorResolverFn - Maps ownerId to 0xRRGGBB color integer, or -1 for unknown.
     */
    renderOwnership(minimapData, colorResolverFn) {
        if (!minimapData || !minimapData.ownerIds) {
            return;
        }

        const { width, height, ownerIds } = minimapData;
        this.lastMinimapData = minimapData;

        if (this.canvas.width !== width || this.canvas.height !== height) {
            this.canvas.width = width;
            this.canvas.height = height;
        }

        const imageData = this.ctx.createImageData(width, height);
        const pixels = imageData.data;
        const emptyColor = NO_DATA_COLOR;

        for (let i = 0; i < ownerIds.length; i++) {
            const ownerId = ownerIds[i];
            let color = emptyColor;
            if (ownerId > 0) {
                const resolved = colorResolverFn(ownerId);
                if (resolved >= 0) color = resolved;
            }

            const p = i << 2;
            pixels[p]     = (color >> 16) & 0xFF;
            pixels[p + 1] = (color >> 8) & 0xFF;
            pixels[p + 2] = color & 0xFF;
            pixels[p + 3] = 255;
        }

        this.ctx.putImageData(imageData, 0, 0);
    }

    /**
     * Re-renders the last minimap data with the viewport rectangle.
     * Useful when only the viewport position changes (panning).
     *
     * @param {{x: number, y: number, width: number, height: number}} viewportBounds - Viewport in world coordinates.
     * @param {number[]} worldShape - World dimensions [width, height].
     */
    updateViewportRect(viewportBounds, worldShape) {
        if (this.lastMinimapData) {
            this.render(this.lastMinimapData);
            this.drawViewportRect(viewportBounds, worldShape);
        }
    }
}
