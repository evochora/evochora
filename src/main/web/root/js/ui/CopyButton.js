/**
 * A small button that copies a value to the clipboard and says so for a moment.
 *
 * @module CopyButton
 */

const SVG = 'http://www.w3.org/2000/svg';

/** How long the button says that it has copied */
const NOTE_MS = 1400;

function icon() {
    const svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('viewBox', '0 0 16 16');
    const sheet = document.createElementNS(SVG, 'rect');
    for (const [name, value] of Object.entries({ x: 5.5, y: 5.5, width: 8, height: 8, rx: 1.5 })) sheet.setAttribute(name, value);
    const behind = document.createElementNS(SVG, 'path');
    behind.setAttribute('d', 'M10.5 5.5V4A1.5 1.5 0 0 0 9 2.5H4A1.5 1.5 0 0 0 2.5 4v5A1.5 1.5 0 0 0 4 10.5h1.5');
    svg.append(sheet, behind);
    return svg;
}

/**
 * @param {string} value - What a click copies
 * @param {string} what - What the value is, as the tooltip names it: "run ID", "revision"
 * @returns {HTMLButtonElement}
 */
export function copyButton(value, what) {
    const button = document.createElement('button');
    button.className = 'copy';
    button.type = 'button';
    button.title = 'Copy the ' + what + ' ' + value;
    button.setAttribute('aria-label', 'Copy the ' + what);
    button.append(icon());
    button.addEventListener('click', () => {
        if (!navigator.clipboard) return;
        navigator.clipboard.writeText(value).then(() => {
            const note = document.createElement('span');
            note.className = 'copied';
            note.textContent = 'Copied';
            button.classList.add('done');
            button.append(note);
            setTimeout(() => { button.classList.remove('done'); note.remove(); }, NOTE_MS);
        }, () => {});
    });
    return button;
}
