/**
 * Renders organism positions as a glow overlay on the minimap.
 * Shows IP and DP positions with density-based glow size.
 *
 * Designed for easy integration/removal - all organism rendering logic is isolated here.
 *
 * @class MinimapOrganismOverlay
 */
export class MinimapOrganismOverlay {

    /** Default configuration */
    static DEFAULT_CONFIG = {
        color: '#4a9a6a',           // Muted green - visible but not aggressive
        coreSize: 3,                // Solid center size in pixels
        glowSizes: [6, 10, 14, 18], // Glow sprite sizes for density thresholds
        densityThresholds: [3, 10, 30], // count <= 3, <= 10, <= 30, > 30
        enabled: true
    };

    /**
     * Creates a new MinimapOrganismOverlay.
     * @param {object} [config] - Configuration options (merged with defaults)
     */
    constructor(config = {}) {
        this.config = { ...MinimapOrganismOverlay.DEFAULT_CONFIG, ...config };
        this.enabled = this.config.enabled;
        this.glowSprites = null;

        this._initSprites();
    }

    /**
     * Pre-renders glow sprites for each density level.
     * Called once at initialization for performance.
     * @private
     */
    _initSprites() {
        const { color, coreSize, glowSizes } = this.config;
        this.glowSprites = glowSizes.map(size => this._createGlowSprite(size, coreSize, color));
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
        // Use OffscreenCanvas if available, fallback to regular canvas
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

        // Parse color to RGB for gradient
        const rgb = this._parseColor(color);

        // Draw glow (radial gradient)
        const gradient = ctx.createRadialGradient(center, center, coreRadius, center, center, glowRadius);
        gradient.addColorStop(0, `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, 0.6)`);
        gradient.addColorStop(0.5, `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, 0.3)`);
        gradient.addColorStop(1, `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, 0)`);

        ctx.fillStyle = gradient;
        ctx.fillRect(0, 0, size, size);

        // Draw solid core
        ctx.fillStyle = color;
        ctx.fillRect(center - coreRadius, center - coreRadius, coreSize, coreSize);

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
     * @param {number} count - Number of organisms at this position
     * @returns {OffscreenCanvas|HTMLCanvasElement}
     * @private
     */
    _selectSprite(count) {
        const { densityThresholds } = this.config;

        for (let i = 0; i < densityThresholds.length; i++) {
            if (count <= densityThresholds[i]) {
                return this.glowSprites[i];
            }
        }
        return this.glowSprites[this.glowSprites.length - 1];
    }

    /**
     * Extracts all relevant positions (IP + DPs) from organisms.
     * @param {Array} organisms - Array of organism objects
     * @returns {Array<[number, number]>} Array of [x, y] positions
     * @private
     */
    _extractPositions(organisms) {
        const positions = [];

        for (const org of organisms) {
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

        return positions;
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

            // Bounds check
            if (mx >= 0 && mx < gridWidth && my >= 0 && my < gridHeight) {
                density[my * gridWidth + mx]++;
            }
        }

        return density;
    }

    /**
     * Renders organism overlay onto the provided canvas context.
     * Call this AFTER rendering the environment minimap.
     *
     * @param {CanvasRenderingContext2D} ctx - Canvas context to draw on
     * @param {Array} organisms - Array of organism objects with ip and dataPointers
     * @param {number[]} worldShape - World dimensions [width, height]
     * @param {{width: number, height: number}} canvasSize - Minimap canvas dimensions
     */
    render(ctx, organisms, worldShape, canvasSize) {
        if (!this.enabled || !organisms || organisms.length === 0) {
            return;
        }

        if (!worldShape || worldShape.length < 2) {
            return;
        }

        const { width: canvasWidth, height: canvasHeight } = canvasSize;

        // Step 1: Extract all positions (IP + DPs)
        const positions = this._extractPositions(organisms);

        if (positions.length === 0) {
            return;
        }

        // Step 2: Calculate density grid
        const density = this._calculateDensity(positions, worldShape, canvasWidth, canvasHeight);

        // Step 3: Render sprites at each position with organisms
        for (let y = 0; y < canvasHeight; y++) {
            for (let x = 0; x < canvasWidth; x++) {
                const count = density[y * canvasWidth + x];
                if (count === 0) continue;

                const sprite = this._selectSprite(count);
                const drawX = x - sprite.width / 2 + 0.5;  // +0.5 to center on pixel
                const drawY = y - sprite.height / 2 + 0.5;

                ctx.drawImage(sprite, drawX, drawY);
            }
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
     * Updates the overlay color and regenerates sprites.
     * @param {string} color - New hex color
     */
    setColor(color) {
        this.config.color = color;
        this._initSprites();
    }

    /**
     * Cleans up resources.
     */
    destroy() {
        this.glowSprites = null;
    }
}
