/**
 * Tick Text
 *
 * How a tick is typed and written. A tick is a whole number, often a long one, so it can be typed
 * short - "28M", "28.5M", "500k", "9.9B" - and a long one is written in groups of three:
 * "1,041,924,159". Numbers are written the en_US way everywhere, in the visualizer and the
 * analyzer alike: the comma groups, the point is the decimal mark, whatever the browser's locale.
 *
 * A tick field holds only what a tick can be: digits, at most one decimal mark, at most one
 * suffix at the end. Whatever else is typed is dropped. The commas that group the digits belong to
 * the display alone: they cannot be typed, the caret crosses one in its way along with the digit,
 * and deleting beside one deletes the digit behind it.
 *
 * @module TickText
 */

const GROUP = ',';
const DECIMAL = '.';

/** What a suffix multiplies by. */
const SCALE = { k: 1e3, m: 1e6, b: 1e9 };

/** What a field may hold, without the grouping: digits, a decimal part, a suffix. */
const HELD = /^[0-9]*(?:\.[0-9]*)?[kMB]?$/;

/** A tick as written: grouped or plain digits, a decimal part only with a suffix. */
const TICK = /^\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(\.[0-9]+)?\s*([kKmMbB])?\s*$/;

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
    const number = Number(match[1].replace(/,/g, '') + (match[2] || ''));
    return Math.round(number * (match[3] ? SCALE[match[3].toLowerCase()] : 1));
}

/**
 * Writes a whole number in groups of three.
 *
 * @param {number|string} value - The number, or its digits
 * @returns {string}
 */
export function groupDigits(value) {
    return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, GROUP);
}

/**
 * Writes a tick so that it reads well and can be typed again, exactly: a round one short, any
 * other in groups of three.
 *
 * @param {number} tick
 * @returns {string}
 */
export function formatTick(tick) {
    if (tick !== 0 && tick % 1e6 === 0 && Math.abs(tick) >= 1e9) return `${tick / 1e9}B`;
    if (tick !== 0 && tick % 1e3 === 0 && Math.abs(tick) >= 1e6) return `${tick / 1e6}M`;
    if (tick !== 0 && tick % 1e3 === 0) return `${tick / 1e3}k`;
    return groupDigits(tick);
}

/** The text of a field without its grouping. */
function held(text) {
    return text.replace(/,/g, '');
}

/** The text of a field with its integer digits grouped. */
function shown(heldText) {
    const match = /^([0-9]*)(.*)$/.exec(heldText);
    return groupDigits(match[1]) + match[2];
}

/**
 * The position in the shown text of a position in the held text. A grouping comma is no position
 * of its own: a caret that arrived moving right stands behind it, one that arrived moving left
 * stands before it - the comma in the direction of travel is crossed along with the digit.
 *
 * @param {string} heldText - The text without grouping
 * @param {number} heldIndex - The position in it
 * @param {number} direction - +1 when the caret moved right, -1 when it moved left
 */
function shownIndex(heldText, heldIndex, direction) {
    const text = shown(heldText);
    let index = 0;
    for (let seen = 0; index < text.length && seen < heldIndex; index++) {
        if (text[index] !== GROUP) seen++;
    }
    if (direction > 0) {
        while (text[index] === GROUP) index++;
    }
    return index;
}

/** The position in the held text of a position in the shown text. */
function heldIndex(shownText, index) {
    return held(shownText.slice(0, index)).length;
}

/**
 * Turns typed or pasted text into what a field may take: points and commas become the decimal
 * mark, a suffix becomes its letter, everything else is dropped. Pasted text that is a whole
 * number written in groups keeps its digits.
 */
function cleaned(data) {
    if (/^[0-9]{1,3}(,[0-9]{3})+$/.test(data)) return data.replace(/,/g, '');
    return data.replace(/[.,]/g, DECIMAL).replace(/[kK]/g, 'k').replace(/[mM]/g, 'M').replace(/[bB]/g, 'B')
        .replace(/[^0-9.kMB]/g, '');
}

/**
 * Makes a text field a tick field. Its text always reads as a tick under way; the grouping
 * commas are display only.
 *
 * @param {HTMLInputElement} input - The field
 */
export function bindTickField(input) {
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
        input.value = shown(after);
        const caret = shownIndex(after, start + insert.length, direction);
        input.setSelectionRange(caret, caret);
        input.dispatchEvent(new Event('input', { bubbles: true }));
    });

    // The caret moves by one digit, and over a grouping comma in its way along with it
    input.addEventListener('keydown', event => {
        if (event.shiftKey || input.selectionStart !== input.selectionEnd) return;
        const step = event.key === 'ArrowLeft' ? -1 : event.key === 'ArrowRight' ? 1 : 0;
        if (!step) return;
        event.preventDefault();
        const heldText = held(input.value);
        const target = Math.max(0, Math.min(heldText.length, heldIndex(input.value, input.selectionStart) + step));
        const caret = shownIndex(heldText, target, step);
        input.setSelectionRange(caret, caret);
    });
}
