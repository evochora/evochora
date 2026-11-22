/**
 * Whether this browser has seen the help of the visualizer.
 *
 * The help opens by itself for a visitor who has not closed it before. The browser's storage
 * holds the mark; a browser that refuses its storage, as some do in a private window, counts as
 * one that has not seen the help.
 *
 * @module HelpSeen
 */

/** The key the mark is stored under. */
export const HELP_SEEN_KEY = 'evochora-help-seen';

/**
 * @param {Storage|null|undefined} storage - The browser's storage, or none where it is refused
 * @returns {boolean} true when the help has been closed in this browser before
 */
export function wasHelpSeen(storage) {
    try {
        return !!storage && storage.getItem(HELP_SEEN_KEY) === 'true';
    } catch {
        return false;
    }
}

/**
 * Records that the help has been closed in this browser.
 *
 * @param {Storage|null|undefined} storage - The browser's storage, or none where it is refused
 */
export function markHelpSeen(storage) {
    try {
        storage?.setItem(HELP_SEEN_KEY, 'true');
    } catch {
        // A browser that refuses its storage shows the help again at the next visit
    }
}

/**
 * @returns {Storage|null} The storage of this browser, or null where reading it is refused
 */
export function browserStorage() {
    try {
        return window.localStorage;
    } catch {
        return null;
    }
}
