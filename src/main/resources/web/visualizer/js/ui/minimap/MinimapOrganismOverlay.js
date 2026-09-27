/**
 * Renders organism positions as colored glow overlays on the minimap, grouped by their colour,
 * which the caller supplies per organism. Organisms of the same colour are density-aggregated
 * together.
 *
 * The sizes of the configuration hold for a canvas whose longer edge is {@link REFERENCE_EDGE}
 * pixels. Dots and selection rings are scaled by the canvas's longer edge over that length, so
 * that a picture rendered larger shows them in the same proportion.
 *
 * @class MinimapOrganismOverlay
 */
export class MinimapOrganismOverlay {

    /** Longer canvas edge in pixels at which the sizes of the configuration are drawn unscaled. */
    static REFERENCE_EDGE = 300;

    /** Default configuration */
    static DEFAULT_CONFIG = {
        coreSize: 3,                // Solid center size in pixels
        glowSizes: [6, 10, 14, 18], // Glow sprite sizes for density thresholds
        densityThresholds: [3, 10, 30], // count <= 3, <= 10, <= 30, > 30
        selectionMinRadius: 3,      // Pulse ring start radius
        selectionMaxRadius: 14,     // Pulse ring end radius
        selectionStrokeWidth: 2,    // Pulse ring stroke width
        enabled: true
    };

    /**
     * Creates a new MinimapOrganismOverlay.
     * @param {object} [config] - Configuration options (merged with defaults)
     */
    constructor(config = {}) {
        this.config = { ...MinimapOrganismOverlay.DEFAULT_CONFIG, ...config };
        this.enabled = this.config.enabled;
        this.spriteCache = new Map(); // hex color → glow sprite array
        this.spriteScale = 1;         // Scale the cached sprites were drawn at
    }

    /**
     * Returns the factor the configured sizes are scaled by on a canvas of the given size.
     * @param {{width: number, height: number}} canvasSize - Minimap canvas dimensions in pixels.
     * @returns {number} The factor, 1 on a canvas whose longer edge is {@link REFERENCE_EDGE} pixels
     *          or shorter: a world smaller than that is drawn at a pixel per cell and keeps the
     *          configured sizes.
     * @private
     */
    _scaleFor(canvasSize) {
        const edge = Math.max(canvasSize.width, canvasSize.height);
        return Math.max(1, edge / MinimapOrganismOverlay.REFERENCE_EDGE);
    }

    /**
     * Draws the sprites anew when the canvas they are drawn on has another scale than the cached
     * ones.
     * @param {number} scale - The scale of the canvas drawn on.
     * @private
     */
    _useScale(scale) {
        if (scale !== this.spriteScale) {
            this.spriteCache.clear();
            this.spriteScale = scale;
        }
    }

    /**
     * Returns (or lazily creates) glow sprites for a given color.
     * @param {string} hexColor - Hex color string (e.g., '#32cd32')
     * @returns {Array<OffscreenCanvas|HTMLCanvasElement>}
     * @private
     */
    _getOrCreateSprites(hexColor) {
        if (this.spriteCache.has(hexColor)) {
            return this.spriteCache.get(hexColor);
        }
        const scale = this.spriteScale;
        const coreSize = Math.max(1, Math.round(this.config.coreSize * scale));
        const sprites = this.config.glowSizes.map(size =>
            this._createGlowSprite(Math.max(1, Math.round(size * scale)), coreSize, hexColor));
        this.spriteCache.set(hexColor, sprites);
        return sprites;
    }

    /**
     * Creates a single glow sprite as an OffscreenCanvas.
     * @param {number} size - Total size of the sprite (including glow)
     * @param {number} coreSize - Size of the solid center
     * @param {string} color - Base color for the glow
     * @returns {OffscreenCanvas|HTMLCanvasElement}
     * @private
     */
    _createGlowSprite(size, coreSize, color) {
        const canvas = typeof OffscreenCanvas !== 'undefined'
            ? new OffscreenCanvas(size, size)
            : document.createElement('canvas');

        if (!(canvas instanceof OffscreenCanvas)) {
            canvas.width = size;
            canvas.height = size;
        }

        const ctx = canvas.getContext('2d');
        const center = size / 2;
        const glowRadius = size / 2;
        const coreRadius = coreSize / 2;

        const rgb = this._parseColor(color);
        const tint = (alpha) => `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, ${alpha})`;

        // The glow eases out over four stops, so the rim fades instead of ending; it starts inside
        // the core, which keeps the core's edge soft where the two meet.
        const gradient = ctx.createRadialGradient(center, center, coreRadius * 0.6, center, center, glowRadius);
        gradient.addColorStop(0, tint(0.85));
        gradient.addColorStop(0.25, tint(0.5));
        gradient.addColorStop(0.55, tint(0.22));
        gradient.addColorStop(0.85, tint(0.06));
        gradient.addColorStop(1, tint(0));

        ctx.fillStyle = gradient;
        ctx.fillRect(0, 0, size, size);

        // A round core: a square reads as a block once the sprite is drawn at three times its size
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.arc(center, center, coreRadius * 0.9, 0, Math.PI * 2);
        ctx.fill();

        return canvas;
    }

    /**
     * Parses a hex color to RGB components.
     * @param {string} color - Hex color string (e.g., '#32cd32')
     * @returns {{r: number, g: number, b: number}}
     * @private
     */
    _parseColor(color) {
        const hex = color.replace('#', '');
        return {
            r: parseInt(hex.substring(0, 2), 16),
            g: parseInt(hex.substring(2, 4), 16),
            b: parseInt(hex.substring(4, 6), 16)
        };
    }

    /**
     * Selects the appropriate glow sprite based on density count.
     * @param {Array} sprites - Glow sprite array for this color
     * @param {number} count - Number of organisms at this position
     * @returns {OffscreenCanvas|HTMLCanvasElement}
     * @private
     */
    _selectSprite(sprites, count) {
        const { densityThresholds } = this.config;

        for (let i = 0; i < densityThresholds.length; i++) {
            if (count <= densityThresholds[i]) {
                return sprites[i];
            }
        }
        return sprites[sprites.length - 1];
    }

    /**
     * Groups organisms by an arbitrary key and extracts positions per group.
     * @param {Array} organisms - Array of organism objects
     * @param {function(object): string} keyFn - Extracts the grouping key from an organism
     * @returns {Map<string, Array<[number, number]>>} key → positions
     * @private
     */
    _groupOrganisms(organisms, keyFn) {
        const groups = new Map();

        for (const org of organisms) {
            const key = keyFn(org);

            if (!groups.has(key)) {
                groups.set(key, []);
            }
            const positions = groups.get(key);

            // IP position
            if (org.ip && Array.isArray(org.ip) && org.ip.length >= 2) {
                positions.push([org.ip[0], org.ip[1]]);
            }

            // DP positions
            if (org.dataPointers && Array.isArray(org.dataPointers)) {
                for (const dp of org.dataPointers) {
                    if (dp && Array.isArray(dp) && dp.length >= 2) {
                        positions.push([dp[0], dp[1]]);
                    }
                }
            }
        }

        return groups;
    }

    /**
     * Calculates density grid from positions.
     * @param {Array<[number, number]>} positions - World positions
     * @param {number[]} worldShape - World dimensions [width, height]
     * @param {number} gridWidth - Minimap width
     * @param {number} gridHeight - Minimap height
     * @returns {Uint16Array} Density values per minimap pixel
     * @private
     */
    _calculateDensity(positions, worldShape, gridWidth, gridHeight) {
        const density = new Uint16Array(gridWidth * gridHeight);
        const worldWidth = worldShape[0];
        const worldHeight = worldShape[1];

        for (const [wx, wy] of positions) {
            const mx = Math.floor(wx / worldWidth * gridWidth);
            const my = Math.floor(wy / worldHeight * gridHeight);

            if (mx >= 0 && mx < gridWidth && my >= 0 && my < gridHeight) {
                density[my * gridWidth + mx]++;
            }
        }

        return density;
    }

    /**
     * Renders organism overlay onto the provided canvas context. The organisms are grouped by
     * their colour, and each group is rendered separately with its own density.
     *
     * @param {CanvasRenderingContext2D} ctx - Canvas context to draw on
     * @param {Array} organisms - Array of organism objects with ip, dataPointers
     * @param {number[]} worldShape - World dimensions [width, height]
     * @param {{width: number, height: number}} canvasSize - Minimap canvas dimensions
     * @param {function(object): string} [colorOf] - Hex colour of an organism; without it every
     *        organism is drawn in one neutral colour
     */
    render(ctx, organisms, worldShape, canvasSize, colorOf) {
        if (!this.enabled || !organisms || organisms.length === 0) {
            return;
        }

        if (!worldShape || worldShape.length < 2) {
            return;
        }

        const { width: canvasWidth, height: canvasHeight } = canvasSize;
        this._useScale(this._scaleFor(canvasSize));

        // Group organisms by colour
        const groups = this._groupOrganisms(organisms, colorOf || (() => '#4a9a6a'));

        // Render each colour group with its own density
        for (const [hexColor, positions] of groups) {
            if (positions.length === 0) continue;

            const sprites = this._getOrCreateSprites(hexColor);
            const density = this._calculateDensity(positions, worldShape, canvasWidth, canvasHeight);

            for (let y = 0; y < canvasHeight; y++) {
                for (let x = 0; x < canvasWidth; x++) {
                    const count = density[y * canvasWidth + x];
                    if (count === 0) continue;

                    const sprite = this._selectSprite(sprites, count);
                    const drawX = x - sprite.width / 2 + 0.5;
                    const drawY = y - sprite.height / 2 + 0.5;

                    ctx.drawImage(sprite, drawX, drawY);
                }
            }
        }
    }

    /**
     * Renders a pulsing white selection ring at the IP and DP positions of the selected organism.
     * The ring expands from min to max radius while fading out, then repeats.
     *
     * @param {CanvasRenderingContext2D} ctx - Canvas context to draw on
     * @param {object} organism - The selected organism object with ip, dataPointers
     * @param {number[]} worldShape - World dimensions [width, height]
     * @param {{width: number, height: number}} canvasSize - Minimap canvas dimensions
     * @param {number} phase - Animation phase (0 to 1, where 0 = start, 1 = fully expanded)
     */
    renderSelection(ctx, organism, worldShape, canvasSize, phase) {
        if (!organism || !worldShape || worldShape.length < 2) {
            return;
        }

        const { width: canvasWidth, height: canvasHeight } = canvasSize;
        const worldWidth = worldShape[0];
        const worldHeight = worldShape[1];
        const scale = this._scaleFor(canvasSize);
        const selectionMinRadius = this.config.selectionMinRadius * scale;
        const selectionMaxRadius = this.config.selectionMaxRadius * scale;
        const selectionStrokeWidth = this.config.selectionStrokeWidth * scale;

        const radius = selectionMinRadius + (selectionMaxRadius - selectionMinRadius) * phase;
        const alpha = 1.0 - phase;

        const positions = [];

        // IP position
        if (organism.ip && Array.isArray(organism.ip) && organism.ip.length >= 2) {
            positions.push([organism.ip[0], organism.ip[1]]);
        }

        // DP positions
        if (organism.dataPointers && Array.isArray(organism.dataPointers)) {
            for (const dp of organism.dataPointers) {
                if (dp && Array.isArray(dp) && dp.length >= 2) {
                    positions.push([dp[0], dp[1]]);
                }
            }
        }

        for (const [wx, wy] of positions) {
            const mx = Math.floor(wx / worldWidth * canvasWidth) + 0.5;
            const my = Math.floor(wy / worldHeight * canvasHeight) + 0.5;

            ctx.beginPath();
            ctx.arc(mx, my, radius, 0, Math.PI * 2);
            ctx.fillStyle = `rgba(255, 255, 255, ${0.15 * alpha})`;
            ctx.fill();
            ctx.strokeStyle = `rgba(255, 255, 255, ${alpha})`;
            ctx.lineWidth = selectionStrokeWidth;
            ctx.stroke();
        }
    }

    /**
     * Enables or disables the overlay.
     * @param {boolean} enabled - Whether to render the overlay
     */
    setEnabled(enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns whether the overlay is currently enabled.
     * @returns {boolean}
     */
    isEnabled() {
        return this.enabled;
    }

    /**
     * Clears the sprite cache (call when palette/colors change, e.g. run switch).
     */
    clearSpriteCache() {
        this.spriteCache.clear();
    }

    /**
     * Cleans up resources.
     */
    destroy() {
        this.spriteCache.clear();
    }
}
