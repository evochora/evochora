/**
 * Tells whether an element takes typed text, so that keys pressed in it are not read as commands.
 *
 * A select, a slider, a checkbox or a button takes no text: the global keys keep working while one
 * of them has the focus, as a slider does after it has been dragged.
 *
 * @module EditableTarget
 */

/** Input types that take no typed text. */
const NON_TEXT_INPUT_TYPES = new Set([
    'button', 'checkbox', 'color', 'file', 'hidden', 'image', 'radio', 'range', 'reset', 'submit',
]);

/**
 * Returns whether the element takes typed text.
 * @param {?Element} element - Usually document.activeElement.
 * @returns {boolean}
 */
export function isTextEntry(element) {
    if (!element) return false;
    if (element.isContentEditable) return true;
    if (element.tagName === 'TEXTAREA') return true;
    return element.tagName === 'INPUT' && !NON_TEXT_INPUT_TYPES.has(element.type);
}
