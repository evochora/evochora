import { clampSize, levelPosition, nearestLevelIndex, stepLevel, ZOOM_LEVELS } from './ZoomLevels.js';
import { pageCamera } from './ViewportMath.js';
import { deviceOf, GESTURE_PAUSE_MS, WheelInputClassifier } from './WheelInputClassifier.js';
import { readWheelInputMode } from './WheelInputSetting.js';
import { isTextEntry } from './EditableTarget.js';

/** Pixels a pointer must travel before a press becomes a drag. */
const DRAG_THRESHOLD_PX = 5;

/** Growth of the zoom per pixel of a pinch: the size is multiplied by exp(-deltaY * rate). */
const PINCH_ZOOM_RATE = 0.01;

/** Pixels of wheel travel that make one zoom step when the wheel zooms. */
const NOTCH_PIXELS = 120;

/** Pixels one line of a wheel that reports lines is taken as when it scrolls. */
const LINE_PIXELS = 20;

/** Milliseconds a finger must rest on one spot before the cell tooltip appears. */
const LONG_PRESS_MS = 500;

/** Pixels the tooltip of a touch is lifted above the finger, so that the finger does not cover it. */
const TOUCH_TOOLTIP_LIFT_PX = 40;

/** Share of the viewport that stays visible when W, A, S or D moves the view by one viewport. */
const PAGE_OVERLAP = 0.1;

/** The keys that move the view by one viewport, by their place on the keyboard. */
const PAGE_KEYS = Object.freeze({
    KeyW: { x: 0, y: -1 },
    KeyA: { x: -1, y: 0 },
    KeyS: { x: 0, y: 1 },
    KeyD: { x: 1, y: 0 },
});

/**
 * Turns wheel, pointer and key input on the environment grid into panning and zooming.
 *
 * <p><strong>Wheel.</strong> A pinch on a touchpad reaches the page as a wheel event with the
 * control key; it zooms smoothly around the pointer. Any other wheel event zooms by levels when it
 * comes from a mouse wheel and pans when it comes from a touchpad, as {@link WheelInputClassifier}
 * decides.
 *
 * <p><strong>Pointers.</strong> A mouse, a pen or one finger drags the view. Two fingers pinch and
 * pan at once. A finger that rests shows the cell tooltip, which then follows the finger until it
 * is lifted and stays until the next touch.
 *
 * <p><strong>Keys.</strong> Plus and minus draw the next level around the centre at once, W, A, S
 * and D move the view by one viewport. A key held down acts once, keys typed into a text field do
 * nothing.
 *
 * <p>A zoom by wheel or fingers only scales the picture drawn so far. It comes to rest on the
 * nearest level, and the viewport is loaded and drawn anew, when the input has paused for
 * {@link GESTURE_PAUSE_MS} or the fingers of a pinch are lifted; the view moved by W, A, S and D is
 * loaded after the same pause. That way a quick series of steps costs one load, not one per step.
 */
export class ViewportInteraction {

    /**
     * @param {import('../EnvironmentGrid.js').EnvironmentGrid} grid - The grid to pan and zoom.
     * @param {HTMLCanvasElement} canvas - The canvas the grid is drawn on.
     */
    constructor(grid, canvas) {
        this.grid = grid;
        this.canvas = canvas;
        this.levels = ZOOM_LEVELS;
        this.classifier = new WheelInputClassifier(readWheelInputMode());

        this._zoomActive = false;
        this._zoomAnchor = null;
        this._zoomCommitTimer = null;
        this._notchRemainder = 0;
        this._pageLoadTimer = null;

        /** Pointers pressed on the canvas: pointerId -> {x, y} in client pixels. */
        this._pointers = new Map();
        this._drag = null;
        this._pinch = null;
        this._longPressTimer = null;
        this._touchTooltip = false;

        canvas.addEventListener('wheel', event => this._onWheel(event), { passive: false });
        canvas.addEventListener('pointerdown', event => this._onPointerDown(event));
        canvas.addEventListener('pointermove', event => this._onPointerMove(event));
        canvas.addEventListener('pointerup', event => this._onPointerUp(event));
        canvas.addEventListener('pointercancel', event => this._onPointerUp(event));
        canvas.addEventListener('contextmenu', event => event.preventDefault());
        document.addEventListener('keydown', event => this._onKeyDown(event));
    }

    /**
     * Changes what the wheel does: detected per gesture, or fixed to a mouse or a touchpad.
     * @param {'auto'|'mouse'|'touchpad'} mode
     */
    setWheelInputMode(mode) {
        this.classifier.setMode(mode);
    }

    // --- Wheel ---

    _onWheel(event) {
        event.preventDefault();
        // deltaMode is read before the deltas: in Firefox this order decides the unit reported
        const wheel = { deltaMode: event.deltaMode, deltaX: event.deltaX, deltaY: event.deltaY };
        const anchor = this._viewportPoint(event.clientX, event.clientY);

        if (event.ctrlKey) {
            // A pinch on a touchpad, or a wheel turned with the control key held
            if (deviceOf(wheel) === 'mouse') {
                this._zoomByNotches(wheel, anchor);
            } else {
                this._previewZoom(this.grid.getDisplayCellSize() * Math.exp(-this._pixels(wheel).y * PINCH_ZOOM_RATE), anchor);
            }
            this._scheduleZoomCommit();
            return;
        }

        if (this.classifier.classify(wheel, event.timeStamp) === 'mouse') {
            this._zoomByNotches(wheel, anchor);
            this._scheduleZoomCommit();
        } else {
            const pixels = this._pixels(wheel);
            this.grid.panBy(pixels.x, pixels.y);
        }
    }

    /**
     * Zooms one level per notch. A wheel that reports pixels is counted in notches of
     * {@link NOTCH_PIXELS}, so that a finely stepped wheel or a touchpad taken for a mouse zooms by
     * whole levels too; a wheel that reports lines or pages makes one step per event.
     */
    _zoomByNotches(wheel, anchor) {
        let steps;
        if (wheel.deltaMode !== 0) {
            steps = Math.sign(wheel.deltaY);
        } else {
            this._notchRemainder += wheel.deltaY;
            steps = Math.trunc(this._notchRemainder / NOTCH_PIXELS);
            this._notchRemainder -= steps * NOTCH_PIXELS;
        }
        let size = this.grid.getDisplayCellSize();
        // A wheel turned towards the user reports a positive delta and zooms out
        for (let i = 0; i < Math.abs(steps); i++) {
            size = stepLevel(this.levels, size, -Math.sign(steps));
        }
        if (steps !== 0) this._previewZoom(size, anchor);
    }

    /** Returns the deltas of a wheel event in pixels. */
    _pixels(wheel) {
        if (wheel.deltaMode === 0) return { x: wheel.deltaX, y: wheel.deltaY };
        if (wheel.deltaMode === 1) return { x: wheel.deltaX * LINE_PIXELS, y: wheel.deltaY * LINE_PIXELS };
        return { x: wheel.deltaX * this.grid.viewportWidth, y: wheel.deltaY * this.grid.viewportHeight };
    }

    // --- Zoom ---

    /** Shows the grid at a size within the levels, the anchor staying in place, until the zoom comes to rest. */
    _previewZoom(size, anchor) {
        if (!this._zoomActive) {
            this._zoomActive = true;
            this.grid.onZoomGestureStart?.();
        }
        const clamped = clampSize(this.levels, size);
        this._zoomAnchor = anchor;
        this.grid.previewZoom(clamped, anchor);
        this.grid.onZoomPreview?.(levelPosition(this.levels, clamped));
    }

    _scheduleZoomCommit() {
        clearTimeout(this._zoomCommitTimer);
        this._zoomCommitTimer = setTimeout(() => this._commitZoom(), GESTURE_PAUSE_MS);
    }

    /** Brings the zoom to rest on the level nearest to the size shown. */
    _commitZoom() {
        const active = this._zoomActive;
        this._endZoomGesture();
        if (active) this._applyZoom(this.levels[nearestLevelIndex(this.levels, this.grid.getDisplayCellSize())], this._zoomAnchor);
    }

    _endZoomGesture() {
        clearTimeout(this._zoomCommitTimer);
        this._zoomCommitTimer = null;
        this._notchRemainder = 0;
        this._zoomActive = false;
    }

    /** Draws the grid at a level, the anchor staying in place; a zoom gesture under way ends there. */
    _applyZoom(size, anchor) {
        if (this.grid.onZoomCommit) {
            this.grid.onZoomCommit(size, anchor);
        } else {
            this.grid.applyZoom(size, anchor);
        }
    }

    // --- Pointers ---

    _onPointerDown(event) {
        if (event.pointerType === 'mouse' && event.button !== 0) return;
        event.preventDefault();

        // Remove focus from any input field when the grid is pressed
        if (document.activeElement && document.activeElement.tagName === 'INPUT') {
            document.activeElement.blur();
        }
        // A tooltip a finger brought up stays until the next touch
        if (this._touchTooltip) {
            this._touchTooltip = false;
            this.grid.hideTooltip();
        }

        this.canvas.setPointerCapture(event.pointerId);
        this._pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });

        if (this._pointers.size === 1) {
            // A new press: a tap selects again unless this press becomes a drag, pinch or long press
            this.grid.tapBlocked = false;
            this._startDrag(event.clientX, event.clientY);
            if (event.pointerType === 'touch') this._startLongPress(event.clientX, event.clientY);
        } else if (this._pointers.size === 2 && event.pointerType === 'touch') {
            this._cancelLongPress();
            this._drag = null;
            this.grid.tapBlocked = true;
            this.grid.hideTooltip();
            this._touchTooltip = false;
            this._pinch = { ...this._fingers(), size: this.grid.getDisplayCellSize() };
        }
    }

    _onPointerMove(event) {
        const pointer = this._pointers.get(event.pointerId);
        if (!pointer) return;
        pointer.x = event.clientX;
        pointer.y = event.clientY;

        if (this._pinch) {
            this._updatePinch();
        } else if (this._touchTooltip) {
            this.grid.showTooltipAtPoint(event.clientX, event.clientY, TOUCH_TOOLTIP_LIFT_PX);
        } else if (this._drag) {
            this._updateDrag(event.clientX, event.clientY);
        }
    }

    _onPointerUp(event) {
        if (!this._pointers.has(event.pointerId)) return;
        // On a torus the grid selects on a tap itself; elsewhere PIXI reports the tap on the organism
        if (this.grid.torus && this._pointers.size === 1 && !this.grid.tapBlocked && event.type === 'pointerup') {
            this.grid.tapAt(event.clientX, event.clientY);
        }
        this._pointers.delete(event.pointerId);
        if (this._pinch && this._pointers.size < 2) {
            this._pinch = null;
            this._commitZoom();
            // The finger left on the glass drags on from where it is
            const [rest] = this._pointers.values();
            this._drag = null;
            if (rest) this._startDrag(rest.x, rest.y);
        }
        if (this._pointers.size === 0) {
            this._cancelLongPress();
            this._drag = null;
        }
    }

    _startDrag(clientX, clientY) {
        this._drag = { startX: clientX, startY: clientY, cameraX: this.grid.cameraX, cameraY: this.grid.cameraY, moving: false };
    }

    _updateDrag(clientX, clientY) {
        const dx = clientX - this._drag.startX;
        const dy = clientY - this._drag.startY;
        if (!this._drag.moving && Math.hypot(dx, dy) > DRAG_THRESHOLD_PX) {
            this._drag.moving = true;
            this.grid.tapBlocked = true;
            this._cancelLongPress();
        }
        if (this._drag.moving) {
            this.grid.moveCameraTo(this._drag.cameraX - dx, this._drag.cameraY - dy);
        }
    }

    /** Returns the distance between the two fingers and their midpoint in viewport pixels. */
    _fingers() {
        const [a, b] = [...this._pointers.values()];
        const mid = this._viewportPoint((a.x + b.x) / 2, (a.y + b.y) / 2);
        return { distance: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)), mid };
    }

    /**
     * Pans by the travel of the fingers' midpoint and zooms around it by the change of distance
     * between them, so that the world point between the fingers stays between them.
     */
    _updatePinch() {
        const { distance, mid } = this._fingers();
        const size = clampSize(this.levels, this._pinch.size * distance / this._pinch.distance);
        this.grid.panBy(this._pinch.mid.x - mid.x, this._pinch.mid.y - mid.y);
        this._previewZoom(size, mid);
        this._pinch = { distance, mid, size };
    }

    _startLongPress(clientX, clientY) {
        this._cancelLongPress();
        this._longPressTimer = setTimeout(() => {
            this._longPressTimer = null;
            this._drag = null;
            this._touchTooltip = true;
            this.grid.tapBlocked = true;
            const pointer = [...this._pointers.values()][0] ?? { x: clientX, y: clientY };
            this.grid.showTooltipAtPoint(pointer.x, pointer.y, TOUCH_TOOLTIP_LIFT_PX);
        }, LONG_PRESS_MS);
    }

    _cancelLongPress() {
        clearTimeout(this._longPressTimer);
        this._longPressTimer = null;
    }

    /** Converts client pixels to pixels from the top left corner of the viewport. */
    _viewportPoint(clientX, clientY) {
        const rect = this.canvas.getBoundingClientRect();
        return { x: clientX - rect.left, y: clientY - rect.top };
    }

    // --- Keys ---

    _onKeyDown(event) {
        if (event.repeat || event.ctrlKey || event.altKey || event.metaKey) return;
        if (isTextEntry(document.activeElement)) return;

        if (event.key === '+' || event.key === '=' || event.key === '-') {
            event.preventDefault();
            const direction = event.key === '-' ? -1 : 1;
            const size = stepLevel(this.levels, this.grid.getDisplayCellSize(), direction);
            this._endZoomGesture();
            this._applyZoom(size, this.grid.viewportCenter());
            return;
        }

        const direction = PAGE_KEYS[event.code];
        if (direction) {
            event.preventDefault();
            const camera = pageCamera({ x: this.grid.cameraX, y: this.grid.cameraY }, direction,
                { width: this.grid.viewportWidth, height: this.grid.viewportHeight }, PAGE_OVERLAP);
            this.grid.moveCameraTo(camera.x, camera.y, false);
            clearTimeout(this._pageLoadTimer);
            this._pageLoadTimer = setTimeout(() => this.grid.requestViewportLoad(), GESTURE_PAUSE_MS);
        }
    }
}
