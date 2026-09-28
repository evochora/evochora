import { MinimapRenderer } from './MinimapRenderer.js';
import { MinimapNavigator } from './MinimapNavigator.js';
import { MinimapOrganismOverlay } from './MinimapOrganismOverlay.js';
import { ZOOM_LEVELS } from '../../interaction/ZoomLevels.js';

/**
 * Orchestrates minimap rendering and navigation as a collapsible panel.
 * Creates DOM elements similar to header panels but positioned at the bottom.
 *
 * The minimap toggle cycles through three overlay modes:
 * - **Org**: Cell type background + organism dot overlay
 * - **Own**: Background colored by dominant owner organism (no dots)
 * - **Off**: Cell type background, no dots
 *
 * The shown panel has two sizes, small and large, which its size button toggles; a click on the
 * controls row collapses it to a tab in either size, and the tab expands it to the size it had.
 * In large the server renders the picture at three times the small size; the controls row keeps
 * its place, width and layout, and the picture stands above it. When the size changes, the shown
 * picture is stretched to the new size at once and replaced when the picture rendered at that
 * size arrives.
 *
 * @class MinimapView
 */
export class MinimapView {

    /** Overlay mode labels for the toggle button. */
    static OVERLAY_MODES = ['org', 'own', 'off'];

    /** Display labels for each overlay mode. */
    static MODE_LABELS = { org: 'Org', own: 'Own', off: 'Off' };
    /** What each overlay mode shows, for the tooltip of the toggle. */
    static MODE_TITLES = {
        org: 'Organism dots — click for owner colours',
        own: 'Cells coloured by owner — click to turn the overlay off',
        off: 'Overlay off — click for organism dots',
    };

    /** Length in pixels of the longer edge of the small picture, the server's default size. */
    static DEFAULT_SIZE = 300;

    /** Length in pixels of the longer edge of the large picture: three times the small one. */
    static LARGE_SIZE = 900;

    /**
     * Creates a new MinimapView.
     *
     * @param {function(number, number): void} onNavigate - Callback when user navigates via minimap.
     * @param {function(number): void} onZoomChange - Callback when the zoom slider selects a level,
     *        with its size in pixels per cell.
     * @param {function(): void} [onSizeChange] - Callback when the size the picture is to be
     *        rendered at changes; see {@link requestedSize}.
     */
    constructor(onNavigate, onZoomChange, onSizeChange) {
        this.onNavigate = onNavigate;
        this.onZoomChange = onZoomChange;
        this.onSizeChange = onSizeChange;
        this.worldShape = null;
        this.lastMinimapData = null;
        this.viewportBounds = null;
        this.expanded = true;
        // Whether the panel shows the large picture when expanded; kept while it is collapsed
        this.large = false;
        this.visible = false;
        this.minimapUseful = true; // True when world is larger than viewport
        // While a zoom gesture runs, the minimap neither appears nor disappears
        this._gestureActive = false;

        this.createDOM();
        this.renderer = new MinimapRenderer(this.canvas);
        this.organismOverlay = new MinimapOrganismOverlay();
        this.currentOrganisms = null; // Cached for re-rendering
        this.selectedOrganismId = null; // Selected organism ID for highlight
        this._selectionAnimationId = null; // requestAnimationFrame ID
        this._selectionAnimationStart = 0;
        this.navigator = null; // Initialized when worldShape is set

        /** @type {'org'|'own'|'off'} */
        this.overlayMode = 'org';
        this.ownershipColorResolver = null;

        this.attachEvents();
    }

    /**
     * Creates the minimap panel DOM elements (collapsed tab + expanded panel).
     * @private
     */
    createDOM() {
        // Collapsed state - tab (with zoom slider)
        this.collapsedElement = document.createElement('div');
        this.collapsedElement.id = 'minimap-panel-collapsed';
        this.collapsedElement.className = 'footer-panel-collapsed hidden';
        this.collapsedElement.innerHTML = `
            <div class="minimap-collapsed-left">
                <span class="panel-label world-size">— × —</span>
            </div>
            <div class="minimap-collapsed-center">
                <input type="range" class="minimap-zoom-slider" min="1" max="${ZOOM_LEVELS.length}" value="1" title="Zoom level">
            </div>
            <div class="minimap-collapsed-right">
                <span class="panel-arrow minimap-expand-arrow" title="Show the minimap">▲</span>
            </div>
        `;

        // Expanded state - panel (content first, header at bottom)
        this.element = document.createElement('div');
        this.element.id = 'minimap-panel';
        this.element.className = 'footer-panel hidden';
        this.element.innerHTML = `
            <div class="minimap-content"></div>
            <div class="minimap-panel-header">
                <div class="minimap-panel-title">
                    <span class="world-size">— × —</span>
                </div>
                <div class="minimap-panel-center">
                    <input type="range" class="minimap-zoom-slider" min="1" max="${ZOOM_LEVELS.length}" value="1" title="Zoom level">
                </div>
                <div class="minimap-panel-controls">
                    <button class="minimap-organism-toggle active" title="Toggle organism overlay">Org</button>
                    <button class="panel-toggle minimap-size-toggle" title="Enlarge the minimap">⤢</button>
                </div>
            </div>
        `;

        // Canvas for rendering
        this.canvas = document.createElement('canvas');
        this.canvas.className = 'minimap-canvas';
        this.element.querySelector('.minimap-content').appendChild(this.canvas);

        // Get references
        this.zoomSlider = this.element.querySelector('.minimap-zoom-slider');
        this.zoomSliderCollapsed = this.collapsedElement.querySelector('.minimap-zoom-slider');
        this.collapseBtn = this.element.querySelector('.panel-toggle');
        this.organismToggleBtn = this.element.querySelector('.minimap-organism-toggle');
        this.panelHeader = this.element.querySelector('.minimap-panel-header');
        this.worldSizeExpanded = this.element.querySelector('.world-size');
        this.worldSizeCollapsed = this.collapsedElement.querySelector('.world-size');
        this.expandArrow = this.collapsedElement.querySelector('.minimap-expand-arrow');

        document.body.appendChild(this.collapsedElement);
        document.body.appendChild(this.element);
    }

    /**
     * Attaches event listeners.
     * @private
     */
    attachEvents() {
        // Collapsed panel click - expand (except interactive elements)
        this.collapsedElement.addEventListener('click', (e) => {
            if (e.target.closest('.minimap-zoom-slider') ||
                e.target.closest('.minimap-collapsed-center') ||
                e.target.closest('button')) return;
            this.expand();
        });

        // Panel header click - collapse
        this.panelHeader.addEventListener('click', (e) => {
            // Don't collapse if clicking on interactive elements
            if (e.target.closest('button') ||
                e.target.closest('input') ||
                e.target.closest('.minimap-panel-center')) return;
            this.collapse();
        });

        // Size button: small ⇄ large
        this.collapseBtn.addEventListener('click', () => this.toggleLarge());

        // Zoom slider change handler
        const handleZoomSliderChange = (e) => {
            // A slider left between two levels by a zoom gesture is dragged from there to a level
            const value = Math.round(Number(e.target.value));
            this.onZoomChange?.(ZOOM_LEVELS[value - 1]);
        };

        // Zoom slider (expanded panel)
        this.zoomSlider.addEventListener('input', handleZoomSliderChange);

        // Zoom slider (collapsed state)
        this.zoomSliderCollapsed.addEventListener('input', (e) => {
            e.stopPropagation();
            handleZoomSliderChange(e);
        });

        // Prevent slider clicks from triggering panel expand/collapse
        this.zoomSlider.addEventListener('click', (e) => e.stopPropagation());
        this.zoomSliderCollapsed.addEventListener('click', (e) => e.stopPropagation());
        this.zoomSlider.addEventListener('mousedown', (e) => e.stopPropagation());
        this.zoomSliderCollapsed.addEventListener('mousedown', (e) => e.stopPropagation());

        // Organism overlay toggle buttons (both panels)
        this.organismToggleBtn.addEventListener('click', () => {
            this.toggleOrganismOverlay();
        });
    }

    /**
     * Updates the minimap with new data from the server.
     * Should be called when environment data is loaded with minimap flag.
     *
     * @param {{width: number, height: number, cellTypes: Uint8Array, ownerIds: number[]}} minimapData - Minimap data.
     * @param {number[]} worldShape - World dimensions [width, height].
     */
    update(minimapData, worldShape) {
        if (!minimapData) {
            return;
        }

        this.worldShape = worldShape;
        this.lastMinimapData = minimapData;

        // Update world size display
        this.updateWorldSizeDisplay(worldShape);

        // Initialize navigator on first update
        if (!this.navigator && worldShape) {
            this.navigator = new MinimapNavigator(this.canvas, worldShape);
            this.navigator.addEventListener('navigate', (e) => {
                if (this.onNavigate) {
                    this.onNavigate(e.detail.worldX, e.detail.worldY);
                }
            });
        } else if (this.navigator && worldShape) {
            this.navigator.updateWorldShape(worldShape);
        }

        // Render minimap based on current overlay mode
        this._renderFullMinimap();

        // Sync collapsed panel width with expanded panel
        this.syncPanelWidths();
        this._placeLargePicture();

        // Check if minimap is useful (after worldShape is set)
        this.updateMinimapUsefulness();

        // Show the minimap
        this.show();
    }

    /**
     * Updates the viewport rectangle position.
     * Called when the main grid viewport changes (pan, zoom).
     *
     * @param {{x: number, y: number, width: number, height: number}} bounds - Viewport in world coordinates.
     */
    updateViewport(bounds) {
        this.viewportBounds = bounds;

        if (!this._selectionAnimationId && this.lastMinimapData && this.worldShape) {
            // Restore cached background and redraw only viewport rect (fast path)
            // When selection animation is active, the animation loop handles drawing.
            this.renderer.restoreBackground();
            this.renderer.drawViewportRect(bounds, this.worldShape);
        }

        // Check if minimap is useful (after viewportBounds is set)
        if (!this._gestureActive) {
            this.updateMinimapUsefulness();
        }
    }

    /**
     * Sets whether the world is a torus, whose viewport rectangle continues across the minimap's
     * edges.
     * @param {boolean} isTorus
     */
    setTorus(isTorus) {
        this.renderer.torus = isTorus;
        if (this.viewportBounds) this.updateViewport(this.viewportBounds);
    }

    /**
     * Marks the start or the end of a zoom gesture. While it runs, the minimap keeps its visibility;
     * at its end, visibility follows the viewport the gesture came to rest on.
     * @param {boolean} active - True while a zoom gesture runs.
     */
    setGestureActive(active) {
        this._gestureActive = active;
        if (!active) {
            this.updateMinimapUsefulness();
        }
    }

    /**
     * Updates the organism overlay with new organism data.
     * Should be called when organisms are loaded for the current tick.
     *
     * @param {Array} organisms - Array of organism objects with ip, dataPointers
     * @param {function(object): string} [colorOf] - Hex colour an organism is drawn in
     */
    updateOrganisms(organisms, colorOf) {
        this.currentOrganisms = organisms;
        this.organismColorOf = colorOf || null;

        // Re-render if we have minimap data (overlay draws on top of environment)
        if (this.lastMinimapData && this.worldShape) {
            this._renderFullMinimap();
        }
    }

    /**
     * Renders the organism overlay on the minimap canvas.
     * @private
     */
    _renderOrganismOverlay() {
        if (!this.currentOrganisms || !this.worldShape) {
            return;
        }

        const ctx = this.canvas.getContext('2d');
        const canvasSize = {
            width: this.canvas.width,
            height: this.canvas.height
        };

        this.organismOverlay.render(ctx, this.currentOrganisms, this.worldShape, canvasSize, this.organismColorOf);
    }

    /**
     * Renders the full minimap based on the current overlay mode.
     * Handles all three modes: 'org' (dots), 'own' (ownership coloring), 'off' (plain).
     * @private
     */
    _renderFullMinimap() {
        if (!this.lastMinimapData || !this.worldShape) return;

        if (this.overlayMode === 'own' && this.ownershipColorResolver) {
            this.renderer.renderOwnership(this.lastMinimapData, this.ownershipColorResolver);
        } else {
            this.renderer.render(this.lastMinimapData);
            if (this.overlayMode === 'org') {
                this._renderOrganismOverlay();
            }
        }

        this.renderer.cacheBackground();

        // Data rendered at another size than the one chosen is shown stretched to the chosen
        // size, until the picture rendered at that size arrives
        const picture = this._pictureSize(this.requestedSize() ?? MinimapView.DEFAULT_SIZE);
        if (picture) {
            this.renderer.scaleTo(picture.width, picture.height);
        }

        if (this.viewportBounds) {
            this.renderer.drawViewportRect(this.viewportBounds, this.worldShape);
        }
    }

    /**
     * Updates the selected organism ID and starts/stops the pulse animation.
     * @param {string|null} organismId - The selected organism ID, or null to clear
     */
    setSelectedOrganism(organismId) {
        this.selectedOrganismId = organismId;

        if (organismId) {
            this._startSelectionAnimation();
        } else {
            this._stopSelectionAnimation();
            // Restore clean state (no selection ring)
            if (this.lastMinimapData && this.worldShape) {
                this.renderer.restoreBackground();
                if (this.viewportBounds) {
                    this.renderer.drawViewportRect(this.viewportBounds, this.worldShape);
                }
            }
        }
    }

    /**
     * Starts the pulsing selection ring animation loop.
     * @private
     */
    _startSelectionAnimation() {
        if (this._selectionAnimationId) return;
        this._selectionAnimationStart = performance.now();

        const animate = () => {
            this._selectionAnimationId = requestAnimationFrame(animate);

            if (!this.lastMinimapData || !this.worldShape) return;

            const elapsed = performance.now() - this._selectionAnimationStart;
            const phase = (elapsed % 1500) / 1500;

            // Restore cached background (environment + organisms)
            this.renderer.restoreBackground();

            // Draw pulsing selection ring
            if (this.selectedOrganismId && this.currentOrganisms) {
                const selectedOrg = this.currentOrganisms.find(
                    o => String(o.organismId) === this.selectedOrganismId
                );
                if (selectedOrg) {
                    const ctx = this.canvas.getContext('2d');
                    const canvasSize = { width: this.canvas.width, height: this.canvas.height };
                    this.organismOverlay.renderSelection(
                        ctx, selectedOrg, this.worldShape, canvasSize, phase
                    );
                }
            }

            // Draw viewport rectangle on top
            if (this.viewportBounds) {
                this.renderer.drawViewportRect(this.viewportBounds, this.worldShape);
            }
        };

        animate();
    }

    /**
     * Stops the pulsing selection ring animation loop.
     * @private
     */
    _stopSelectionAnimation() {
        if (this._selectionAnimationId) {
            cancelAnimationFrame(this._selectionAnimationId);
            this._selectionAnimationId = null;
        }
    }

    /**
     * Cycles the overlay mode through: org → own → off → org.
     */
    toggleOrganismOverlay() {
        const modes = MinimapView.OVERLAY_MODES;
        const nextIndex = (modes.indexOf(this.overlayMode) + 1) % modes.length;
        this.setOverlayMode(modes[nextIndex]);
    }

    /**
     * Sets the overlay mode ('org', 'own', or 'off').
     * @param {'org'|'own'|'off'} mode - The overlay mode to set.
     */
    setOverlayMode(mode) {
        this.overlayMode = mode;
        this.organismOverlay.setEnabled(mode === 'org');

        // Update button appearance
        this._updateToggleButton();

        // Persist
        localStorage.setItem('minimapOverlayMode', mode);

        // Re-render
        this._renderFullMinimap();
    }

    /**
     * Sets the color resolver function for ownership mode.
     * @param {function(number): number} resolverFn - Maps ownerId to 0xRRGGBB color.
     */
    setOwnershipColorResolver(resolverFn) {
        this.ownershipColorResolver = resolverFn;
    }

    /**
     * Passes the run's molecule type map and the bit position its keys are shifted by to the
     * renderer, which resolves the minimap's type bytes to type names through them.
     *
     * @param {object|null|undefined} moleculeTypes - Metadata map of the shifted type constant,
     *        as a string, to the type name.
     * @param {number|null|undefined} typeShift - The metadata's `moleculeTypeShift`, the bit
     *        position of the type inside a packed molecule.
     */
    setMoleculeTypes(moleculeTypes, typeShift) {
        this.renderer.setMoleculeTypes(moleculeTypes, typeShift);
    }

    /**
     * Returns the current overlay mode.
     * @returns {'org'|'own'|'off'}
     */
    getOverlayMode() {
        return this.overlayMode;
    }

    /**
     * Updates the toggle button text and active state.
     * @private
     */
    _updateToggleButton() {
        if (!this.organismToggleBtn) return;
        this.organismToggleBtn.textContent = MinimapView.MODE_LABELS[this.overlayMode];
        this.organismToggleBtn.title = MinimapView.MODE_TITLES[this.overlayMode];
        this.organismToggleBtn.classList.toggle('active', this.overlayMode !== 'off');
    }

    /**
     * Places the zoom slider on the level the zoom rests on.
     * @param {number} size - Pixels per cell, one of the zoom levels.
     */
    updateZoomButton(size) {
        // Slider value: 1 for the smallest level, one more for each level above it
        this._setSliders('1', ZOOM_LEVELS.indexOf(size) + 1);
    }

    /**
     * Places the zoom slider between two levels while a zoom gesture runs.
     * @param {number} position - Level index, 0 for the smallest level; a fraction lies between
     *        two levels.
     */
    showZoomPosition(position) {
        this._setSliders('any', position + 1);
    }

    /**
     * Sets step and value of both zoom sliders. The step comes first: a value off the step is
     * rounded to it.
     * @param {string} step - 'any' while a gesture runs, '1' at rest.
     * @param {number} value - Slider value, 1 to the number of zoom levels.
     * @private
     */
    _setSliders(step, value) {
        for (const slider of [this.zoomSlider, this.zoomSliderCollapsed]) {
            if (!slider) continue;
            slider.step = step;
            slider.value = value;
        }
    }

    /**
     * Updates the world size display in both panel states.
     * @param {number[]} worldShape - World dimensions [width, height].
     * @private
     */
    updateWorldSizeDisplay(worldShape) {
        if (!worldShape || worldShape.length < 2) return;
        const text = `${worldShape[0]} × ${worldShape[1]}`;
        if (this.worldSizeExpanded) {
            this.worldSizeExpanded.textContent = text;
        }
        if (this.worldSizeCollapsed) {
            this.worldSizeCollapsed.textContent = text;
        }
    }

    /**
     * Checks if the minimap is useful (world larger than viewport) and updates UI accordingly.
     * When the world fits entirely in the viewport, the minimap provides no navigation value,
     * so we hide the minimap content and related controls, but keep the header with zoom slider.
     *
     * IMPORTANT: This method only updates visibility of minimap content and buttons,
     * it does NOT switch between panels to avoid breaking slider drag events.
     * @private
     */
    updateMinimapUsefulness() {
        // Need both worldShape and viewportBounds to determine usefulness
        if (!this.worldShape || !this.viewportBounds) {
            return;
        }

        const worldWidth = this.worldShape[0];
        const worldHeight = this.worldShape[1];
        const viewportWidth = this.viewportBounds.width;
        const viewportHeight = this.viewportBounds.height;

        // Minimap is useful when world is larger than viewport in any dimension
        const newUseful = viewportWidth < worldWidth || viewportHeight < worldHeight;

        if (newUseful !== this.minimapUseful) {
            this.minimapUseful = newUseful;
            this._applyMinimapUsefulness();
        }
    }

    /**
     * Applies visibility changes based on minimap usefulness.
     * Only shows/hides minimap content and related buttons, never switches panels.
     * Uses display:none for all elements since slider is now left-aligned and won't shift.
     * @private
     */
    _applyMinimapUsefulness() {
        // Update expand arrow visibility (collapsed panel)
        if (this.expandArrow) {
            this.expandArrow.style.display = this.minimapUseful ? '' : 'none';
        }

        // Update cursor style on collapsed panel (not clickable if not useful)
        if (this.collapsedElement) {
            this.collapsedElement.style.cursor = this.minimapUseful ? 'pointer' : 'default';
        }

        // Show/hide organism toggle and collapse buttons FIRST (before content)
        // so they disappear before the panel shrinks
        if (this.organismToggleBtn) {
            this.organismToggleBtn.style.display = this.minimapUseful ? '' : 'none';
        }
        if (this.collapseBtn) {
            this.collapseBtn.style.display = this.minimapUseful ? '' : 'none';
        }

        // Show/hide minimap content (canvas container) in expanded panel
        const minimapContent = this.element.querySelector('.minimap-content');
        if (minimapContent) {
            minimapContent.style.display = this.minimapUseful ? '' : 'none';
        }

        // Without the picture the small panel is as wide as its controls row
        this.syncPanelWidths();
    }

    /**
     * Sets the width of the expanded panel and of the collapsed tab: the same outer width in small
     * and in large, so that the controls row keeps its width and layout. It is the wider of the
     * controls row and the small picture with its padding; the small picture's width follows from
     * the world's shape as the server sizes it, so it is known in large as well.
     * <p>
     * The controls row is measured with the picture out of the flow, as in large, and its width
     * rounded up to whole pixels: a width rounded down would wrap its text.
     * @private
     */
    syncPanelWidths() {
        if (!this.collapsedElement || !this.element) return;

        // Temporarily show expanded panel to measure its width
        const wasHidden = this.element.classList.contains('hidden');
        if (wasHidden) {
            // Briefly show to measure (off-screen measurement trick)
            this.element.style.visibility = 'hidden';
            this.element.classList.remove('hidden');
        }

        this.element.style.width = '';
        this.element.classList.add('large');
        const rowWidth = Math.ceil(this.element.getBoundingClientRect().width);
        this.element.classList.toggle('large', this.large);
        const width = Math.max(rowWidth, this._smallPictureOuterWidth());
        if (width > 0) {
            this.element.style.width = `${width}px`;
        }

        // Restore hidden state if it was hidden
        if (wasHidden) {
            this.element.classList.add('hidden');
            this.element.style.visibility = '';
        }

        // Apply the same width to collapsed panel (uses border-box sizing)
        if (width > 0) {
            this.collapsedElement.style.width = `${width}px`;
        }
    }

    /**
     * Returns the outer width the expanded panel needs for the small picture: the picture, the
     * padding around it and the panel's borders.
     * @returns {number} The width in pixels; 0 while no picture is shown.
     * @private
     */
    _smallPictureOuterWidth() {
        if (!this.minimapUseful || !this.worldShape) {
            return 0;
        }
        const picture = this._pictureSize(MinimapView.DEFAULT_SIZE);
        if (!picture) {
            return 0;
        }
        const content = getComputedStyle(this.element.querySelector('.minimap-content'));
        const panel = getComputedStyle(this.element);
        return picture.width
            + (parseFloat(content.paddingLeft) || 0) + (parseFloat(content.paddingRight) || 0)
            + (parseFloat(panel.borderLeftWidth) || 0) + (parseFloat(panel.borderRightWidth) || 0);
    }

    /**
     * Returns the size of the picture the server renders for a requested length of the longer
     * edge: the world's aspect ratio, never more pixels than cells.
     * @param {number} size - Requested length in pixels of the longer edge.
     * @returns {{width: number, height: number}|null} The picture's size, null without a world.
     * @private
     */
    _pictureSize(size) {
        const [worldWidth, worldHeight] = this.worldShape || [];
        if (!(worldWidth > 0) || !(worldHeight > 0)) {
            return null;
        }
        const edge = Math.min(size, Math.max(worldWidth, worldHeight));
        return worldWidth >= worldHeight
            ? { width: edge, height: Math.max(1, Math.round(edge * worldHeight / worldWidth)) }
            : { width: Math.max(1, Math.round(edge * worldWidth / worldHeight)), height: edge };
    }

    /**
     * Stretches the shown picture to the size it is rendered at now, until the picture rendered
     * at that size arrives; the viewport rectangle is drawn anew on top.
     * @private
     */
    _showStretchedPicture() {
        const picture = this._pictureSize(this.requestedSize() ?? MinimapView.DEFAULT_SIZE);
        if (!picture || !this.lastMinimapData) {
            return;
        }
        this.renderer.scaleTo(picture.width, picture.height);
        if (this.viewportBounds && !this._selectionAnimationId) {
            this.renderer.drawViewportRect(this.viewportBounds, this.worldShape);
        }
    }

    /**
     * Lifts the large picture so that its lower edge clears the timeline panel beside the
     * controls row, when that panel stands taller than the row.
     * @private
     */
    _placeLargePicture() {
        if (!this.large || this.element.classList.contains('hidden')) {
            this.element.style.removeProperty('--minimap-lift');
            return;
        }
        const timeline = document.getElementById('timeline-panel');
        const panelTop = this.element.getBoundingClientRect().top;
        const timelineTop = timeline ? timeline.getBoundingClientRect().top : panelTop;
        const lift = Math.max(0, Math.ceil(panelTop - timelineTop));
        this.element.style.setProperty('--minimap-lift', `${lift}px`);
    }

    /**
     * Returns the size the picture is to be rendered at.
     * @returns {number|null} The length in pixels of the longer edge in large, null for the
     *     server's default size in small and while collapsed.
     */
    requestedSize() {
        return this.expanded && this.large ? MinimapView.LARGE_SIZE : null;
    }

    /**
     * Applies a size: the panel's class, the size button, the stored preferences, and, when the
     * size the picture is rendered at changed, the shown picture stretched to it and a new one.
     * Whether the panel is expanded and whether it is large are stored apart, so that a collapsed
     * panel expands to the size it had.
     * @param {boolean} expanded - Whether the panel is expanded.
     * @param {boolean} large - Whether the panel shows the large picture when expanded.
     * @private
     */
    _setSize(expanded, large) {
        const sizeBefore = this.requestedSize();
        this.expanded = expanded;
        this.large = large;
        this.element.classList.toggle('large', this.large);
        this._updateSizeButton();
        localStorage.setItem('minimapExpanded', expanded ? 'true' : 'false');
        localStorage.setItem('minimapLarge', this.large ? 'true' : 'false');
        const sizeChanged = this.requestedSize() !== sizeBefore;
        if (sizeChanged) {
            this._showStretchedPicture();
        }
        this.syncPanelWidths();
        this._placeLargePicture();
        if (sizeChanged) {
            this.onSizeChange?.();
        }
    }

    /**
     * Shows the size button's glyph and title for the current size.
     * @private
     */
    _updateSizeButton() {
        if (!this.collapseBtn) return;
        this.collapseBtn.textContent = this.large ? '⤡' : '⤢';
        this.collapseBtn.title = this.large ? 'Shrink the minimap' : 'Enlarge the minimap';
    }

    /**
     * Toggles the shown panel between the small and the large picture.
     * Does nothing if minimap is not useful (world fits in viewport).
     */
    toggleLarge() {
        if (!this.minimapUseful) {
            return;
        }
        this._setSize(true, !this.large);
    }

    /**
     * Expands the minimap panel to the size it had before it was collapsed.
     * Does nothing if minimap is not useful (world fits in viewport).
     */
    expand() {
        // Don't expand if minimap is not useful
        if (!this.minimapUseful) {
            return;
        }

        this.collapsedElement.classList.add('hidden');
        if (this.visible) {
            this.element.classList.remove('hidden');
            this._applyMinimapUsefulness();
        }
        this._setSize(true, this.large);
    }

    /**
     * Collapses the minimap panel to just the tab; the size it had is kept for the next expand.
     */
    collapse() {
        this.element.classList.add('hidden');
        if (this.visible) {
            this.collapsedElement.classList.remove('hidden');
            this._applyMinimapUsefulness();
        }
        this._setSize(false, this.large);
    }

    /**
     * Shows the minimap (either collapsed tab or expanded panel).
     * Respects the user's expanded preference and applies usefulness visibility.
     */
    show() {
        this.visible = true;

        // Show panel based on user's expanded preference
        if (this.expanded) {
            this.element.classList.remove('hidden');
            this.collapsedElement.classList.add('hidden');
        } else {
            this.element.classList.add('hidden');
            this.collapsedElement.classList.remove('hidden');
        }

        // Apply usefulness state (hides/shows content within current panel)
        this._applyMinimapUsefulness();
        this._placeLargePicture();
    }

    /**
     * Hides the minimap completely.
     */
    hide() {
        this.visible = false;
        this.element.classList.add('hidden');
        this.collapsedElement.classList.add('hidden');
    }

    /**
     * Clears the minimap state (e.g., when changing runs).
     */
    clear() {
        this.lastMinimapData = null;
        this.viewportBounds = null;
        this.currentOrganisms = null;
        this.hide();
    }

    /**
     * Restores the size (collapsed, small, large) and overlay mode from localStorage.
     */
    restoreState() {
        const expanded = localStorage.getItem('minimapExpanded');
        if (expanded === 'false') {
            this.expanded = false;
        }
        this.large = localStorage.getItem('minimapLarge') === 'true';
        this.element.classList.toggle('large', this.large);
        this._updateSizeButton();

        // Restore overlay mode (default: 'org')
        const savedMode = localStorage.getItem('minimapOverlayMode');
        if (savedMode && MinimapView.OVERLAY_MODES.includes(savedMode)) {
            this.overlayMode = savedMode;
            this.organismOverlay.setEnabled(savedMode === 'org');
            this._updateToggleButton();
        } else {
            // Backward compat: migrate old boolean setting
            const oldSetting = localStorage.getItem('minimapOrganismOverlay');
            if (oldSetting !== null) {
                this.overlayMode = oldSetting === 'false' ? 'off' : 'org';
                this.organismOverlay.setEnabled(this.overlayMode === 'org');
                this._updateToggleButton();
                localStorage.setItem('minimapOverlayMode', this.overlayMode);
                localStorage.removeItem('minimapOrganismOverlay');
            }
        }
    }

    /**
     * Cleans up resources.
     */
    destroy() {
        if (this.navigator) {
            this.navigator.destroy();
        }
        if (this.organismOverlay) {
            this.organismOverlay.destroy();
        }
        if (this.element && this.element.parentNode) {
            this.element.parentNode.removeChild(this.element);
        }
        if (this.collapsedElement && this.collapsedElement.parentNode) {
            this.collapsedElement.parentNode.removeChild(this.collapsedElement);
        }
    }
}
