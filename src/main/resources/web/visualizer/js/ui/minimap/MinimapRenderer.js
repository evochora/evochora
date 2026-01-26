/**
 * Renders minimap data onto a canvas using putImageData for optimal performance.
 * This class is stateless except for the canvas reference and color palette.
 *
 * @class MinimapRenderer
 */
export class MinimapRenderer {

    /**
     * Color palette matching the main environment grid colors.
     * Index corresponds to cell type: 0=CODE, 1=DATA, 2=ENERGY, 3=STRUCTURE, 4=LABEL, 5=EMPTY.
     * Colors are stored as 0xRRGGBB integers for fast pixel manipulation.
     */
    static DEFAULT_PALETTE = {
        0: 0x3c5078,   // CODE - blue-gray
        1: 0x32323c,   // DATA - dark gray
        2: 0xffe664,   // ENERGY - yellow
        3: 0xff7878,   // STRUCTURE - red/pink (high visibility for organism boundaries)
        4: 0xa0a0a8,   // LABEL - light gray
        5: 0x1e1e28,   // EMPTY - slightly lighter than background (CODE with value 0)
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
     * @param {{x: number, y: number, width: number, height: number}} viewportBounds - Viewport in world coordinates.
     * @param {number[]} worldShape - World dimensions [width, height].
     */
    drawViewportRect(viewportBounds, worldShape) {
        if (!this.lastMinimapData || !worldShape || worldShape.length < 2) {
            return;
        }

        const { x, y, width, height } = viewportBounds;
        const [worldWidth, worldHeight] = worldShape;

        // Scale factors from world to minimap coordinates
        const scaleX = this.canvas.width / worldWidth;
        const scaleY = this.canvas.height / worldHeight;

        // Calculate rectangle position and size in minimap coordinates
        const rectX = x * scaleX;
        const rectY = y * scaleY;
        const rectW = width * scaleX;
        const rectH = height * scaleY;

        // Draw semi-transparent white rectangle with border
        this.ctx.strokeStyle = 'rgba(255, 255, 255, 0.9)';
        this.ctx.lineWidth = 2;
        this.ctx.strokeRect(rectX, rectY, rectW, rectH);

        // Inner subtle fill to make it more visible
        this.ctx.fillStyle = 'rgba(255, 255, 255, 0.1)';
        this.ctx.fillRect(rectX, rectY, rectW, rectH);
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
