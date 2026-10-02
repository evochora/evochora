import { DESCENT_TONES } from '../../DescentColours.js';
import { groupDigits, SPACE_GROUP } from '../../../../shared/tick/TickText.js';
import { ValueFormatter } from '../../utils/ValueFormatter.js';

/** Fewest and most columns the cells of the lines row are laid out in. */
const MIN_COLUMNS = 3;
const MAX_COLUMNS = 6;

/** Gap in CSS pixels between two columns of the lines row; the style sheet sets the same. */
const COLUMN_GAP_PX = 20;

/**
 * The descent section above the organism panel: the root the organisms are coloured against,
 * and the lines that descend from it.
 * <p>
 * Two rows. The first names the root, the steps upward from it, its state at the shown tick, its
 * life span and its birth place, and ends with the switch between a root that follows the common
 * ancestor of the living (auto) and a held one. The second holds a cell per coloured line, laid
 * out in columns, and below them one closing line with the other lines and the organisms of the
 * run outside the root's descent. While the ancestry of the run is still being read, the first row
 * shows the progress; when reading it failed, the error.
 * <p>
 * The section only shows and reports: every change of the root goes to the callbacks, and the
 * section is drawn again from the answer that follows.
 *
 * @class DescentSection
 */
export class DescentSection {
    /**
     * Initializes the section.
     * @param {object} options - Configuration options
     * @param {HTMLElement} options.container - The element the section is drawn into
     * @param {Function} options.onRootChange - Callback when the root is changed:
     *     (root) => void, root being an organism id, 'all', or 'auto' for the common ancestor of
     *     the living at every shown tick
     * @param {Function} options.onHoldRoot - Callback when the root that auto resolved is to be
     *     held from now on: () => void
     * @param {Function} options.onPositionClick - Callback when the birth place is clicked (x, y)
     */
    constructor({ container, onRootChange, onHoldRoot, onPositionClick }) {
        this.container = container;
        this.onRootChange = onRootChange;
        this.onHoldRoot = onHoldRoot;
        this.onPositionClick = onPositionClick;
    }

    /**
     * Empties the section, as before the first answer of a run.
     */
    clear() {
        if (this.container) {
            this.container.innerHTML = '';
        }
    }

    /**
     * Draws the section from the descent of one tick.
     *
     * @param {object} params
     * @param {object|null} params.descent - The `descent` object of the organisms-of-a-tick answer.
     * @param {import('../../DescentColours.js').DescentColouring} params.colouring - The colouring
     *     built from the same answer.
     * @param {Array<object>} params.organisms - The organisms of the tick.
     * @param {number} params.tick - The shown tick.
     * @param {boolean} params.auto - Whether the root follows the common ancestor of the living.
     */
    render({ descent, colouring, organisms, tick, auto }) {
        if (!this.container) return;
        if (!descent) {
            this.clear();
            return;
        }

        if (descent.state === 'failed') {
            this.container.innerHTML = this._rows(
                `<span class="descent-status descent-error" title="${escapeHtml(descent.error || '')}">`
                + `${escapeHtml(descent.error || 'reading the ancestry failed')}</span>`, '');
            return;
        }
        if (descent.state === 'loading' || !descent.root) {
            const percent = Math.floor((descent.progress || 0) * 100);
            this.container.innerHTML = this._rows(
                `<span class="descent-status">loading ancestry ${percent} %</span>`, '');
            return;
        }

        this.container.innerHTML = this._rows(
            this._rootRow(descent, tick, auto),
            this._linesRow(descent, colouring, organisms));
        this._bind(descent, auto);
    }

    /**
     * Wraps the contents of the two rows.
     * @param {string} rootRow - HTML of the first row.
     * @param {string} linesRow - HTML of the second row, the row element included; empty for none.
     * @returns {string} HTML of the section.
     * @private
     */
    _rows(rootRow, linesRow) {
        return `<div class="descent-row-root">${rootRow}</div>${linesRow}`;
    }

    /**
     * Builds the first row: the root, the steps upward, its state and life, the auto switch.
     * @param {object} descent - The descent of the tick.
     * @param {number} tick - The shown tick.
     * @param {boolean} auto - Whether the root follows the common ancestor of the living.
     * @returns {string} HTML of the row.
     * @private
     */
    _rootRow(descent, tick, auto) {
        const root = descent.root;
        const reset = auto
            ? '<button type="button" class="descent-reset active" title="auto: the root follows the '
                + 'common ancestor of the living — click to hold the current root">⟲</button>'
            : '<button type="button" class="descent-reset" title="click: the root follows the '
                + 'common ancestor of the living (auto)">⟲</button>';
        if (root.id === 0) {
            return '<span class="descent-root-id">all</span>'
                + '<span class="descent-life">all founder lines</span>' + reset;
        }

        const up = descent.up;
        let steps = '';
        if (up) {
            const target = up.oneStep ? `#${up.oneStep}` : 'all';
            steps += `<span class="descent-link descent-up-one" `
                + `title="one generation up: root becomes ${target}">↑</span>`;
            if (up.landing && up.landing.skipped > 0) {
                const landing = up.landing.root ? `#${up.landing.root}` : 'all';
                steps += `<span class="descent-link descent-up-skip" title="skip ${up.landing.skipped} `
                    + `generations up: root becomes ${landing}, where the living split">+${up.landing.skipped}</span>`;
            }
        }

        const died = root.deathTick !== null && root.deathTick !== undefined && root.deathTick >= 0;
        let life = '';
        if (root.birthTick !== null && root.birthTick !== undefined) {
            const state = tick < root.birthTick ? 'unborn' : (died && tick >= root.deathTick ? 'dead' : 'alive');
            const grouped = ValueFormatter.formatGroupedHtml;
            const span = `${grouped(root.birthTick)} – ${died ? grouped(root.deathTick) : '…'}`;
            const spelled = `born ${groupDigits(root.birthTick, SPACE_GROUP)}, `
                + (died ? `died ${groupDigits(root.deathTick, SPACE_GROUP)}` : 'alive at the end of the run');
            life = `<span class="descent-state-${state}">${state}</span>`
                + ` · <span title="${spelled}">${span}</span>`;
        }
        if (Array.isArray(root.position) && root.position.length >= 2) {
            const [x, y] = root.position;
            life += ` · <span class="clickable-position" data-x="${x}" data-y="${y}" `
                + `title="jump to the birth place of the root">${x}|${y}</span>`;
        }

        return `<span class="descent-root-id">#${root.id}</span>${steps}`
            + `<span class="descent-life">${life}</span>${reset}`;
    }

    /**
     * Builds the second row: a cell per coloured line in a grid, and below them the closing line
     * with the other lines and the outside.
     * <p>
     * The row is written with its number of columns already set, so it is never drawn in another
     * layout first. The font is monospace and every part of a cell is a character or the one
     * character wide swatch, so the widest cell measures its characters times the width of one
     * character; the columns are as many of those, with the gaps between them, as the row holds,
     * between {@link MIN_COLUMNS} and {@link MAX_COLUMNS}, and fewer only where not even the
     * minimum fits. A cell is never cut: wider cells mean fewer columns and more rows.
     *
     * @param {object} descent - The descent of the tick.
     * @param {import('../../DescentColours.js').DescentColouring} colouring - Its colouring.
     * @param {Array<object>} organisms - The organisms of the tick.
     * @returns {string} HTML of the row element; empty when there is nothing to show.
     * @private
     */
    _linesRow(descent, colouring, organisms) {
        const total = descent.root.descendants;
        const cells = [];
        let others = 0;
        let otherDescendants = 0;
        for (const line of descent.lines || []) {
            if (line.colour === null || line.colour === undefined) {
                others++;
                otherDescendants += line.descendants || 0;
            } else {
                cells.push(this._cell(line, colouring.lineColourOf(line.id), total));
            }
        }

        const closing = [];
        if (others > 0) {
            const share = total > 0
                ? ` ${shareHtml(otherDescendants, total, `share of the root's descendants over the whole run in the ${others === 1 ? 'line' : 'lines'} without a colour`)}`
                : '';
            closing.push(`<span class="descent-closing-item">${swatch(DESCENT_TONES.OTHER)} `
                + `${ValueFormatter.formatGroupedHtml(others)} more ${others === 1 ? 'line' : 'lines'}${share}</span>`);
        }
        const outside = this._outside(descent, total, colouring, organisms);
        if (outside !== null) {
            closing.push(outside);
        }
        if (descent.unreadLiving > 0) {
            closing.push(`<span class="descent-closing-item" title="their rows are not in the server's `
                + `ancestry yet; it reads them again, and the colours follow">`
                + `${swatch(DESCENT_TONES.UNKNOWN)} ${ValueFormatter.formatGroupedHtml(descent.unreadLiving)} `
                + `unread ${descent.unreadLiving === 1 ? 'organism' : 'organisms'}</span>`);
        }

        if (cells.length === 0 && closing.length === 0) {
            return '';
        }
        const columns = this._columns(Math.max(0, ...cells.map(cell => cell.chars)));
        const closingLine = closing.length > 0
            ? `<div class="descent-closing">${closing.join('')}</div>`
            : '';
        return `<div class="descent-row-lines" style="grid-template-columns: repeat(${columns}, max-content)">`
            + `${cells.map(cell => cell.html).join('')}${closingLine}</div>`;
    }

    /**
     * Builds the outside entry of the closing line: the share of all organisms of the run that do
     * not descend from the root. While the number of organisms in the run is not known, the entry
     * is shown without a share when living organisms of the tick lie outside.
     * @param {object} descent - The descent of the tick.
     * @param {number|undefined} total - Descendants of the root over the whole run.
     * @param {import('../../DescentColours.js').DescentColouring} colouring - Its colouring.
     * @param {Array<object>} organisms - The organisms of the tick.
     * @returns {string|null} HTML of the entry, null when nothing lies outside.
     * @private
     */
    _outside(descent, total, colouring, organisms) {
        const inRun = descent.organismsInRun;
        if (inRun > 0 && total >= 0) {
            const outside = inRun - total;
            if (outside <= 0) {
                return null;
            }
            return `<span class="descent-closing-item">${swatch(DESCENT_TONES.OUTSIDE)} outside `
                + `${shareHtml(outside, inRun, 'share of all organisms of the run that do not descend from the root')}</span>`;
        }
        // The root maps to its own line, so it never counts as outside
        const livingOutside = (organisms || []).some(o => !o.isDead && colouring.isOutside(o.organismId));
        return livingOutside
            ? `<span class="descent-closing-item">${swatch(DESCENT_TONES.OUTSIDE)} outside</span>`
            : null;
    }

    /**
     * Builds the cell of one coloured line: swatch, id, the skip below it, the share.
     * @param {object} line - The line as the answer lists it.
     * @param {string} colour - The line's colour.
     * @param {number|undefined} total - Descendants of the root over the whole run.
     * @returns {{html: string, chars: number}} HTML of the cell and its width in characters.
     * @private
     */
    _cell(line, colour, total) {
        const extinct = line.living > 0 ? '' : ' extinct';
        // The swatch counts as one character, and every part is set off by one space
        let chars = 1 + 1 + `#${line.id}`.length;
        let skip = '';
        if (line.landing && line.landing.skipped > 0) {
            const text = `+${line.landing.skipped}`;
            chars += 1 + text.length;
            skip = ` <span class="descent-link descent-skip" data-root="${line.landing.root}" `
                + `title="skip ${line.landing.skipped} generations: root becomes #${line.landing.root}, `
                + `where the living of this line split">${text}</span>`;
        }
        let share = '';
        if (total > 0) {
            const text = `${formatShare(line.descendants / total * 100)}%`;
            chars += 1 + text.length;
            share = ` ${shareHtml(line.descendants, total, "share of the root's descendants over the whole run")}`;
        }
        const html = `<span class="descent-cell${extinct}">`
            + `<span class="descent-chip-id" data-root="${line.id}" title="root becomes #${line.id}">`
            + `${swatch(colour)} #${line.id}</span>${skip}${share}</span>`;
        return { html, chars };
    }

    /**
     * Computes how many columns the cells of the lines row are laid out in.
     * <p>
     * The row's width is the section's inner width; the width of one character is measured on a
     * probe in the section, which also lays the section out while it is still empty. Both are read
     * before the row is written, so the row is drawn in its columns from the start.
     *
     * @param {number} cellChars - Width of the widest cell in characters.
     * @returns {number} The number of columns.
     * @private
     */
    _columns(cellChars) {
        const container = this.container;
        const probe = document.createElement('span');
        probe.className = 'descent-measure';
        container.appendChild(probe);
        const charWidth = probe.getBoundingClientRect().width / 100;
        const style = getComputedStyle(container);
        const rowWidth = container.clientWidth
            - (parseFloat(style.paddingLeft) || 0) - (parseFloat(style.paddingRight) || 0);
        probe.remove();

        if (!(rowWidth > 0) || !(charWidth > 0) || cellChars <= 0) {
            return MIN_COLUMNS;
        }
        const fit = Math.floor((rowWidth + COLUMN_GAP_PX) / (cellChars * charWidth + COLUMN_GAP_PX));
        // Fewer than the minimum only where the minimum would overflow the row
        return Math.max(1, Math.min(MAX_COLUMNS, fit));
    }

    /**
     * Binds the controls of the drawn section.
     * @param {object} descent - The descent the section was drawn from.
     * @param {boolean} auto - Whether the root follows the common ancestor of the living.
     * @private
     */
    _bind(descent, auto) {
        const container = this.container;
        container.querySelectorAll('.descent-chip-id, .descent-skip').forEach(el => {
            el.addEventListener('click', () => this.onRootChange?.(parseInt(el.dataset.root, 10)));
        });
        const up = descent.up;
        container.querySelector('.descent-up-one')?.addEventListener('click', () => {
            this.onRootChange?.(up.oneStep || 'all');
        });
        container.querySelector('.descent-up-skip')?.addEventListener('click', () => {
            this.onRootChange?.(up.landing.root || 'all');
        });
        container.querySelector('.descent-reset')?.addEventListener('click', () => {
            if (auto) {
                this.onHoldRoot?.();
            } else {
                this.onRootChange?.('auto');
            }
        });
        container.querySelectorAll('.clickable-position').forEach(el => {
            el.addEventListener('click', () => {
                const x = parseInt(el.dataset.x, 10);
                const y = parseInt(el.dataset.y, 10);
                if (!isNaN(x) && !isNaN(y)) {
                    this.onPositionClick?.(x, y);
                }
            });
        });
    }
}

/**
 * Builds a colour swatch: a square one character wide.
 * @param {string} colour - CSS colour of the swatch.
 * @returns {string} HTML of the swatch.
 */
function swatch(colour) {
    return `<span class="descent-swatch" style="background-color:${colour}"></span>`;
}

/**
 * Builds a share as the rows show it, with the exact value and the counts in its tooltip.
 * @param {number} part - The organisms counted.
 * @param {number} whole - The organisms they are a share of; greater than 0.
 * @param {string} meaning - What the share is, for the tooltip.
 * @returns {string} HTML of the share.
 */
function shareHtml(part, whole, meaning) {
    const percent = part / whole * 100;
    const exact = `${Number(percent.toPrecision(6))} % (${groupDigits(part, SPACE_GROUP)} of ${groupDigits(whole, SPACE_GROUP)})`;
    return `<span class="descent-share" title="${escapeHtml(`${meaning}: ${exact}`)}">`
        + `${escapeHtml(formatShare(percent))}%</span>`;
}

/**
 * Writes a percentage for the rows: "<1" below 1 %, one decimal place below 10 %, a whole number
 * from 10 % on.
 * @param {number} percent - The percentage.
 * @returns {string} The number, without the percent sign.
 */
function formatShare(percent) {
    if (percent < 1) {
        return '<1';
    }
    const tenths = Math.round(percent * 10) / 10;
    return tenths < 10 ? tenths.toFixed(1) : String(Math.round(percent));
}

/**
 * Escapes a text for use in HTML content and attribute values.
 * @param {string} text - The text.
 * @returns {string} The escaped text.
 */
function escapeHtml(text) {
    return String(text).replace(/[&<>"']/g, (c) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[c]);
}
