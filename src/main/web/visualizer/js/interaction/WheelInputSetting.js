import { WHEEL_INPUT_MODES } from './WheelInputClassifier.js';

/**
 * The user's setting of the wheel input, kept in the browser for good.
 *
 * Storage may be unavailable, as in a private window or with site data blocked; the setting then
 * falls back to detecting the device and a change lasts for the page only.
 *
 * @module WheelInputSetting
 */

const STORAGE_KEY = 'evochora-wheel-input';

/**
 * Returns the stored setting, or 'auto' when none is stored or storage cannot be read.
 * @returns {'auto'|'mouse'|'touchpad'}
 */
export function readWheelInputMode() {
    try {
        const stored = localStorage.getItem(STORAGE_KEY);
        return WHEEL_INPUT_MODES.includes(stored) ? stored : 'auto';
    } catch {
        return 'auto';
    }
}

/**
 * Stores the setting. A storage that cannot be written leaves the setting to this page.
 * @param {'auto'|'mouse'|'touchpad'} mode
 */
export function writeWheelInputMode(mode) {
    try {
        localStorage.setItem(STORAGE_KEY, mode);
    } catch {
        // Storage is unavailable: the choice holds until the page is left
    }
}
