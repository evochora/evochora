import { depthColour } from '../../DescentColours.js';
import { ValueFormatter } from '../../utils/ValueFormatter.js';

/** Background of the strip. */
const STRIP_BACKGROUND = '#222';

/** Bar of an ancestor that carries no genome change. */
const PLAIN_BAR = '#2a2a2a';

/**
 * The ancestry of the selected organism as a strip: one bar per ancestor, the founder on the
 * left, the direct parent on the right, drawn on a canvas that takes the width it is given, so
 * that a long ancestry condenses instead of overflowing.
 * <p>
 * An ancestor that carries a genome change is drawn in the colour of its genome depth, every
 * other one in a dark tone. Hovering names the ancestor under the pointer; a click on an ancestor
 * alive at the shown tick selects it.
 *
 * @class LineageStrip
 */
export class LineageStrip {
    /**
     * Creates the strip in a host element and keeps it sized to the host.
     * @param {HTMLElement} host - The element the canvas is drawn into; its size is the strip's.
     * @param {object} options
     * @param {Function} options.onSelect - Callback when a living ancestor is clicked: (organismId) => void
     */
    constructor(host, { onSelect }) {
        this.host = host;
        this.onSelect = onSelect;
        this.chain = null;
        this.ancestors = [];
        this.aliveIds = new Set();
        this.title = '';

        this.canvas = document.createElement('canvas');
        this.canvas.className = 'lineage-strip-canvas';
        host.appendChild(this.canvas);

        this._onMove = (e) => this._handleMove(e);
        this._onLeave = () => this._handleLeave();
        this._onClick = (e) => this._handleClick(e);
        host.addEventListener('mousemove', this._onMove);
        host.addEventListener('mouseleave', this._onLeave);
        host.addEventListener('click', this._onClick);

        this._resizeObserver = new ResizeObserver(() => this.draw());
        this._resizeObserver.observe(host);
    }

    /**
     * Shows the ancestry of an organism.
     * @param {{entries: Array<object>, changes: number}} chain - The genome chain of the organism,
     *     from `buildGenomeChain`: the organism first, its oldest ancestor last.
     * @param {Set<number>} aliveIds - Ids of the organisms alive at the shown tick.
     */
    render(chain, aliveIds) {
        this.chain = chain;
        this.aliveIds = aliveIds;
        // The organism itself is not a bar; the founder is drawn first
        this.ancestors = chain.entries.slice(1).reverse();
        this.title = `${this.ancestors.length} generations, genome depth ${chain.changes}`;
        this.host.title = this.title;
        this.draw();
    }

    /**
     * Draws the bars at the current size of the host.
     */
    draw() {
        const width = this.host.clientWidth;
        const height = this.host.clientHeight;
        if (width === 0 || height === 0 || !this.chain) return;

        const dpr = window.devicePixelRatio || 1;
        this.canvas.width = Math.round(width * dpr);
        this.canvas.height = Math.round(height * dpr);
        const ctx = this.canvas.getContext('2d');
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.fillStyle = STRIP_BACKGROUND;
        ctx.fillRect(0, 0, width, height);

        const count = this.ancestors.length;
        if (count === 0) return;
        const barWidth = width / count;
        this.ancestors.forEach((entry, index) => {
            ctx.fillStyle = entry.change ? depthColour(entry.depth, this.chain.changes) : PLAIN_BAR;
            ctx.fillRect(index * barWidth, 0, Math.max(barWidth, 1), height);
        });
    }

    /**
     * Stops following the host's size and releases the listeners.
     */
    destroy() {
        this._resizeObserver.disconnect();
        this.host.removeEventListener('mousemove', this._onMove);
        this.host.removeEventListener('mouseleave', this._onLeave);
        this.host.removeEventListener('click', this._onClick);
    }

    /**
     * Returns the ancestor under the pointer.
     * @param {MouseEvent} e - The pointer event.
     * @returns {object|null} The ancestor's chain entry, null when there is none.
     * @private
     */
    _ancestorAt(e) {
        const count = this.ancestors.length;
        if (count === 0) return null;
        const rect = this.host.getBoundingClientRect();
        if (rect.width === 0) return null;
        const index = Math.floor((e.clientX - rect.left) / rect.width * count);
        return this.ancestors[Math.min(count - 1, Math.max(0, index))];
    }

    /**
     * Names the ancestor under the pointer in the tooltip, and offers a click on a living one.
     * @param {MouseEvent} e - The pointer event.
     * @private
     */
    _handleMove(e) {
        const entry = this._ancestorAt(e);
        if (!entry) return;
        const alive = this.aliveIds.has(entry.organismId);
        this.host.title = `#${entry.organismId}  G-${entry.back}  ${ValueFormatter.formatGenomeHash(entry.genomeHash)}`
            + (alive ? '' : '  dead at this tick');
        this.host.classList.toggle('selectable', alive);
    }

    /**
     * Restores the strip's own tooltip when the pointer leaves it.
     * @private
     */
    _handleLeave() {
        this.host.title = this.title;
        this.host.classList.remove('selectable');
    }

    /**
     * Selects the ancestor under the pointer if it is alive at the shown tick.
     * @param {MouseEvent} e - The pointer event.
     * @private
     */
    _handleClick(e) {
        const entry = this._ancestorAt(e);
        if (entry && this.aliveIds.has(entry.organismId)) {
            e.stopPropagation();
            this.onSelect?.(entry.organismId);
        }
    }
}
