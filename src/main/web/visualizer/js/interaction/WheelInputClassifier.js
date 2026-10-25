/**
 * Decides whether a wheel event comes from a mouse wheel or from two fingers on a touchpad.
 *
 * The browser reports both as the same event and names no device, so the decision rests on what
 * the deltas look like:
 * - a mouse wheel reports lines, or whole notches of 120 pixels with no horizontal part;
 * - a touchpad reports fractional pixels, often with a horizontal part.
 * A delta that shows neither is ambiguous and leaves the device that was seen last.
 *
 * A gesture is a series of wheel events with no pause of {@link GESTURE_PAUSE_MS} in it. Its device
 * is decided at its first event and kept to its end, so that a gesture never changes what it does
 * halfway. Before any device has been seen, an ambiguous gesture counts as a touchpad: taking a
 * touchpad for a mouse zooms at every swipe, taking a mouse for a touchpad only scrolls.
 *
 * The user may also fix the device, in which case no delta is looked at.
 *
 * A zoom gesture of the touchpad (a pinch, reported with the control key) is not classified here:
 * it zooms whatever the device.
 *
 * @module WheelInputClassifier
 */

/** Milliseconds without a wheel event after which a gesture has ended. */
export const GESTURE_PAUSE_MS = 400;

/** The settings of the wheel input: detected per gesture, or fixed to one device. */
export const WHEEL_INPUT_MODES = Object.freeze(['auto', 'mouse', 'touchpad']);

/** Pixels a mouse wheel reports for one notch. */
const NOTCH_PIXELS = 120;

/** Largest distance from a whole number that still counts as whole: the browser adds float noise. */
const WHOLE_TOLERANCE = 0.01;

/** Value of WheelEvent.DOM_DELTA_PIXEL, stated here so that the module needs no DOM. */
const DELTA_PIXEL = 0;

/**
 * Returns the device the deltas of one wheel event point to.
 * @param {{deltaMode: number, deltaX: number, deltaY: number}} event - The wheel event; deltaMode
 *        must have been read before the deltas, which in Firefox decides the unit reported.
 * @returns {'mouse'|'touchpad'|null} The device, or null when the deltas show neither.
 */
export function deviceOf(event) {
    if (event.deltaMode !== DELTA_PIXEL) return 'mouse';
    if (event.deltaX !== 0) return 'touchpad';
    const distance = Math.abs(event.deltaY);
    const remainder = distance % NOTCH_PIXELS;
    const onNotch = Math.min(remainder, NOTCH_PIXELS - remainder) < WHOLE_TOLERANCE;
    if (distance > NOTCH_PIXELS - WHOLE_TOLERANCE && onNotch) return 'mouse';
    if (Math.abs(distance - Math.round(distance)) >= WHOLE_TOLERANCE) return 'touchpad';
    return null;
}

/**
 * Keeps the state that spans wheel events: the current gesture, its device and the device seen last.
 */
export class WheelInputClassifier {

    /**
     * @param {'auto'|'mouse'|'touchpad'} mode - The setting of the wheel input.
     */
    constructor(mode) {
        this.mode = mode;
        this._lastDevice = 'touchpad';
        this._gestureDevice = null;
        this._lastEventTime = -Infinity;
    }

    /**
     * Changes the setting. A gesture under way ends, so that the next event is decided afresh.
     * @param {'auto'|'mouse'|'touchpad'} mode
     */
    setMode(mode) {
        this.mode = mode;
        this._gestureDevice = null;
    }

    /**
     * Returns the device of a wheel event that is not a pinch.
     * @param {{deltaMode: number, deltaX: number, deltaY: number}} event - The wheel event.
     * @param {number} time - Milliseconds of the event on a steady clock.
     * @returns {'mouse'|'touchpad'}
     */
    classify(event, time) {
        if (time - this._lastEventTime >= GESTURE_PAUSE_MS) this._gestureDevice = null;
        this._lastEventTime = time;
        if (this.mode !== 'auto') return this.mode;
        if (this._gestureDevice === null) {
            this._lastDevice = deviceOf(event) ?? this._lastDevice;
            this._gestureDevice = this._lastDevice;
        }
        return this._gestureDevice;
    }
}
