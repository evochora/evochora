/**
 * Renders minimap data onto a canvas using putImageData for optimal performance.
 * This class is stateless except for the canvas reference and color palette.
 *
 * @class MinimapRenderer
 */
export class MinimapRenderer {

    /**
     * Color palette matching the main environment grid colors.
     * Index corresponds to cell type: 0=CODE, 1=DATA, 2=ENERGY, 3=STRUCTURE, 4=LABEL,
     * 5=LABELREF, 6=REGISTER, 7=EMPTY.
     * Colors are stored as 0xRRGGBB integers for fast pixel manipulation.
     */
    static DEFAULT_PALETTE = {
        0: 0x3c5078,   // CODE - blue-gray
        1: 0x32323c,   // DATA - dark gray
        2: 0xffe664,   // ENERGY - yellow
        3: 0xff7878,   // STRUCTURE - red/pink (high visibility for organism boundaries)
        4: 0xa0a0a8,   // LABEL - light gray
        5: 0xa0a0a8,   // LABELREF - light gray (same as LABEL)
        6: 0x506080,   // REGISTER - medium blue-gray
        7: 0x1e1e28,   // EMPTY - slightly lighter than background (CODE with value 0)
        empty: 0x14141e // Background (no cell data)
    };

    /**
     * Creates a new MinimapRenderer.
     *
     * @param {HTMLCanvasElement} canvas - The canvas element to render onto.
     * @param {object} [palette=MinimapRenderer.DEFAULT_PALETTE] - Color palette for cell types.
     */
    constructor(canvas, palette = MinimapRenderer.DEFAULT_PALETTE) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d', { alpha: false });
        this.palette = palette;
        this.lastMinimapData = null;

        // Off-screen canvas for caching background (environment + organisms)
        this._cacheCanvas = document.createElement('canvas');
        this._cacheCtx = this._cacheCanvas.getContext('2d', { alpha: false });
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
            const type = cellTypes[i];
            // Check if type has a defined color in palette, otherwise use empty
            const color = (type in this.palette) ? this.palette[type] : this.palette.empty;

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
     * (unknown/dead organism) use the empty/background color.
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
        const emptyColor = this.palette.empty;

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
