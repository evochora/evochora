/**
 * Tick Text
 *
 * How a tick is typed and written. A tick is a whole number, often a long one, so it can be typed
 * short - "28M", "28.5M", "500k", "9.9B" - and a long one is written in groups of three. The
 * point is the decimal mark, whatever the browser's locale. The groups are separated by a comma,
 * "1,041,924,159", unless a caller names another separator: the visualizer writes its ticks with a
 * space, "1 041 924 159". A tick is read in either writing, and without any grouping.
 *
 * A tick field holds only what a tick can be: digits, at most one decimal mark, at most one
 * suffix at the end. Whatever else is typed is dropped. The separators that group the digits belong
 * to the display alone: they cannot be typed, the caret crosses one in its way along with the
 * digit, and deleting beside one deletes the digit behind it.
 *
 * @module TickText
 */

const GROUP = ',';
const DECIMAL = '.';

/** What a suffix multiplies by. */
const SCALE = { k: 1e3, m: 1e6, b: 1e9 };

/** What a field may hold, without the grouping: digits, a decimal part, a suffix. */
const HELD = /^[0-9]*(?:\.[0-9]*)?[kMB]?$/;

/** Every character that may separate the groups of a written tick: comma and spaces. */
const SEPARATORS = /[, \u00a0\u202f]/g;

/**
 * A tick as written: digits grouped by commas, grouped by spaces, or plain, a decimal part only
 * with a suffix.
 */
const TICK = /^\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,3}(?:[ \u00a0\u202f][0-9]{3})+|[0-9]+)(\.[0-9]+)?\s*([kKmMbB])?\s*$/;

/** A whole number written in groups, by commas or by spaces, as pasted into a field. */
const GROUPED = /^[0-9]{1,3}(?:(?:,[0-9]{3})+|(?:[ \u00a0\u202f][0-9]{3})+)$/;

/**
 * Reads a tick as written.
 *
 * @param {string} text - What was typed
 * @returns {?number} The tick, or null if the text is none - a decimal part without a suffix
 *          is none, because a tick is a whole number
 */
export function parseTick(text) {
    const match = TICK.exec(text || '');
    if (!match || (match[2] && !match[3])) return null;
    const number = Number(match[1].replace(SEPARATORS, '') + (match[2] || ''));
    return Math.round(number * (match[3] ? SCALE[match[3].toLowerCase()] : 1));
}

/** The separator the visualizer groups the digits of its ticks with: a space. */
export const SPACE_GROUP = ' ';

/**
 * Writes a whole number in groups of three.
 *
 * @param {number|string} value - The number, or its digits
 * @param {string} [group=','] - What separates the groups
 * @returns {string}
 */
export function groupDigits(value, group = GROUP) {
    return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, group);
}

/**
 * Writes a tick so that it reads well and can be typed again, exactly: a round one short, any
 * other in groups of three.
 *
 * @param {number} tick
 * @param {string} [group=','] - What separates the groups of a tick that is not written short
 * @returns {string}
 */
export function formatTick(tick, group = GROUP) {
    if (tick !== 0 && tick % 1e6 === 0 && Math.abs(tick) >= 1e9) return `${tick / 1e9}B`;
    if (tick !== 0 && tick % 1e3 === 0 && Math.abs(tick) >= 1e6) return `${tick / 1e6}M`;
    if (tick !== 0 && tick % 1e3 === 0) return `${tick / 1e3}k`;
    return groupDigits(tick, group);
}

/**
 * Writes the digits of a text without the separators that group them.
 *
 * @param {string} text - A tick as written, or part of one
 * @returns {string}
 */
export function ungroupDigits(text) {
    return String(text).replace(SEPARATORS, '');
}

/** The text of a field without its grouping. */
function held(text) {
    return ungroupDigits(text);
}

/** The text of a field with its integer digits grouped by the given separator. */
function shown(heldText, group) {
    const match = /^([0-9]*)(.*)$/.exec(heldText);
    return groupDigits(match[1], group) + match[2];
}

/**
 * The position in the shown text of a position in the held text. A grouping separator is no
 * position of its own: a caret that arrived moving right stands behind it, one that arrived moving
 * left stands before it - the separator in the direction of travel is crossed along with the digit.
 *
 * @param {string} heldText - The text without grouping
 * @param {number} heldIndex - The position in it
 * @param {number} direction - +1 when the caret moved right, -1 when it moved left
 * @param {string} group - The separator the field groups with
 */
function shownIndex(heldText, heldIndex, direction, group) {
    const text = shown(heldText, group);
    let index = 0;
    for (let seen = 0; index < text.length && seen < heldIndex; index++) {
        if (text[index] !== group) seen++;
    }
    if (direction > 0) {
        while (text[index] === group) index++;
    }
    return index;
}

/** The position in the held text of a position in the shown text. */
function heldIndex(shownText, index) {
    return held(shownText.slice(0, index)).length;
}

/**
 * Turns typed or pasted text into what a field may take: points and commas become the decimal
 * mark, a suffix becomes its letter, everything else - spaces included - is dropped. Pasted text
 * that is a whole number written in groups, by commas or by spaces, keeps its digits.
 */
function cleaned(data) {
    if (GROUPED.test(data)) return data.replace(SEPARATORS, '');
    return data.replace(/[.,]/g, DECIMAL).replace(/[kK]/g, 'k').replace(/[mM]/g, 'M').replace(/[bB]/g, 'B')
        .replace(/[^0-9.kMB]/g, '');
}

/**
 * Makes a text field a tick field. Its text always reads as a tick under way; the grouping
 * separators are display only.
 *
 * @param {HTMLInputElement} input - The field
 * @param {object} [options]
 * @param {string} [options.group=','] - What separates the groups of digits in the field
 */
export function bindTickField(input, { group = GROUP } = {}) {
    input.addEventListener('beforeinput', event => {
        const before = held(input.value);
        let start = heldIndex(input.value, input.selectionStart);
        let end = heldIndex(input.value, input.selectionEnd);
        let insert = '';

        let direction = 1;
        switch (event.inputType) {
            case 'insertText':
            case 'insertFromPaste':
            case 'insertFromDrop':
                insert = cleaned(event.data || '');
                break;
            case 'deleteContentBackward':
                if (start === end && start > 0) start--;
                direction = -1;
                break;
            case 'deleteContentForward':
                if (start === end && end < before.length) end++;
                break;
            case 'deleteWordBackward':
            case 'deleteSoftLineBackward':
            case 'deleteHardLineBackward':
                if (start === end) start = 0;
                break;
            case 'deleteWordForward':
            case 'deleteSoftLineForward':
            case 'deleteHardLineForward':
                if (start === end) end = before.length;
                break;
            default:
                event.preventDefault();
                return;
        }
        event.preventDefault();

        const after = before.slice(0, start) + insert + before.slice(end);
        if (!HELD.test(after)) return;
        input.value = shown(after, group);
        const caret = shownIndex(after, start + insert.length, direction, group);
        input.setSelectionRange(caret, caret);
        input.dispatchEvent(new Event('input', { bubbles: true }));
    });

    // The caret moves by one digit, and over a grouping separator in its way along with it
    input.addEventListener('keydown', event => {
        if (event.shiftKey || input.selectionStart !== input.selectionEnd) return;
        const step = event.key === 'ArrowLeft' ? -1 : event.key === 'ArrowRight' ? 1 : 0;
        if (!step) return;
        event.preventDefault();
        const heldText = held(input.value);
        const target = Math.max(0, Math.min(heldText.length, heldIndex(input.value, input.selectionStart) + step));
        const caret = shownIndex(heldText, target, step, group);
        input.setSelectionRange(caret, caret);
    });
}
