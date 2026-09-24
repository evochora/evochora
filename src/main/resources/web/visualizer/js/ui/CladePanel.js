import * as TickGrid from '../TickGrid.js';
import { ValueFormatter } from '../utils/ValueFormatter.js';
import { OTHER_COLOUR } from '../CladeModel.js';

/**
 * The panel above the timeline: a stacked area chart of the clades over the whole run, and beside
 * it the path into the tree.
 *
 * The chart uses the same x mapping as the timeline track below it and is exactly as wide, so the
 * mark of the current tick stands at the same pixel in both.
 *
 * @class CladePanel
 */
export class CladePanel {

    /** Width of the hatching that marks a band one cannot open, and of the stretch without data. */
    static HATCH_SPACING = 7;

    /** Height of one line in the strip beside the chart, in CSS pixels. */
    static STRIP_LINE_HEIGHT = 15;

    /**
     * @param {object} callbacks
     * @param {function(): Array<object>} callbacks.getRanges - The run's recorded tick ranges.
     * @param {function(): number} callbacks.getCurrentTick - The tick shown in the environment.
     * @param {function(): void} callbacks.onLevelChanged - Called after entering or leaving a clade.
     */
    constructor({ getRanges, getCurrentTick, onLevelChanged }) {
        this._getRanges = getRanges;
        this._getCurrentTick = getCurrentTick;
        this._onLevelChanged = onLevelChanged;

        this._model = null;
        this._hovered = null;
        this._collapsed = false;
        this._visible = false;

        this._panel = document.createElement('div');
        this._panel.id = 'clade-panel';
        this._panel.className = 'clade-panel';
        this._body = document.createElement('div');
        this._body.className = 'clade-panel-body';
        this._canvas = document.createElement('canvas');
        this._canvas.className = 'clade-chart';
        this._strip = document.createElement('div');
        this._strip.className = 'clade-strip';
        this._body.append(this._canvas, this._strip);
        this._panel.appendChild(this._body);
        document.body.appendChild(this._panel);

        this._canvas.addEventListener('mousemove', (e) => this._onMove(e));
        this._canvas.addEventListener('mouseleave', () => this._onLeave());
        this._canvas.addEventListener('click', (e) => this._onClick(e));
        window.addEventListener('resize', () => this._follow());
        // The panel's top edge meets the minimap's, so it follows whatever the minimap does:
        // collapsing it, expanding it, or simply being laid out after the panel first appeared
        if (typeof ResizeObserver !== 'undefined') {
            const observer = new ResizeObserver(() => this._follow());
            for (const id of ['minimap-panel', 'minimap-panel-collapsed', 'timeline-panel']) {
                const element = document.getElementById(id);
                if (element) {
                    observer.observe(element);
                }
            }
        }
    }

    /**
     * Lays the panel out again and redraws it, when it is on screen.
     * <p>
     * It becomes visible only once it has a height, which is to say once the minimap it aligns
     * with has one. Until then it stays out of the way rather than standing over the window.
     * @private
     */
    _follow() {
        if (!this._visible) {
            return;
        }
        if (this._chartHeight() === null) {
            this._panel.classList.remove('visible');
            return;
        }
        this._layout();
        this._drawStrip();
        this.redraw();
        this._panel.classList.add('visible');
    }

    /**
     * Hands the panel the tree it shows. Passing null leaves it empty until one arrives.
     *
     * @param {import('../CladeModel.js').CladeModel|null} model The clade tree of the current run
     */
    setModel(model) {
        this._model = model;
        this._hovered = null;
        if (this._visible) {
            this._layout();
            this._drawStrip();
            this.redraw();
        }
    }

    /** Whether a tree has been handed over. @returns {boolean} */
    get hasModel() {
        return this._model !== null;
    }

    /**
     * Shows the panel. It always opens at full size: one made small stays small only while it is
     * in use, so that switching away and back does not leave a bar with no chart.
     */
    show() {
        this._visible = true;
        this._collapsed = false;
        this._follow();
    }

    /** Hides the panel without forgetting the level that is entered. */
    hide() {
        this._visible = false;
        this._panel.classList.remove('visible');
    }

    /** Draws the chart from the model's current level. */
    redraw() {
        if (!this._visible || this._collapsed || !this._model) {
            return;
        }
        const height = this._chartHeight();
        if (height === null) {
            return;
        }
        const track = this._trackRect();
        this._canvas.width = Math.max(1, Math.round(track.width));
        this._canvas.height = height;
        this._canvas.style.height = height + 'px';

        const ctx = this._canvas.getContext('2d');
        const width = this._canvas.width;
        const ranges = this._getRanges() ?? [];
        const first = TickGrid.firstTick(ranges) ?? 0;
        const last = TickGrid.lastTick(ranges) ?? 0;
        const span = Math.max(1, last - first);
        const x = (tick) => ((tick - first) / span) * width;

        ctx.fillStyle = '#15151d';
        ctx.fillRect(0, 0, width, height);

        const stacks = this._model.stacks();
        const bands = this._model.bands;
        for (let i = 0; i < bands.length; i++) {
            ctx.beginPath();
            stacks.forEach((stack, n) => {
                const y = height - (i === 0 ? 0 : stack.tops[i - 1]) * height;
                ctx[n ? 'lineTo' : 'moveTo'](x(stack.tick), y);
            });
            for (let n = stacks.length - 1; n >= 0; n--) {
                ctx.lineTo(x(stacks[n].tick), height - stacks[n].tops[i] * height);
            }
            ctx.closePath();
            ctx.fillStyle = bands[i].colour ?? OTHER_COLOUR;
            ctx.fill();
            if (bands[i].kind !== 'clade') {
                // Hatching marks the band that is not a clade one can open
                this._hatch(ctx, width, height, 'rgba(0,0,0,0.30)', 3);
            }
            if (bands[i] === this._hovered) {
                ctx.fillStyle = 'rgba(255,255,255,0.26)';
                ctx.fill();
                ctx.strokeStyle = 'rgba(255,255,255,0.75)';
                ctx.lineWidth = 1.5;
                ctx.stroke();
            }
        }

        this._drawUnanswered(ctx, stacks, x, width, height);

        const cx = x(this._getCurrentTick());
        ctx.strokeStyle = '#fff';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(cx, 0);
        ctx.lineTo(cx, height);
        ctx.stroke();
    }

    /**
     * Marks the stretch the samples do not reach yet.
     * <p>
     * The samples sit on a fixed grid while the timeline runs to the last indexed tick, so a
     * strip at the right holds no data. Left plain it would read as a population of zero.
     * @private
     */
    _drawUnanswered(ctx, stacks, x, width, height) {
        const lastSample = stacks.length ? stacks[stacks.length - 1].tick : null;
        if (lastSample === null) {
            return;
        }
        const from = x(lastSample);
        if (from >= width - 0.5) {
            return;
        }
        ctx.save();
        ctx.beginPath();
        ctx.rect(from, 0, width - from, height);
        ctx.clip();
        this._hatch(ctx, width, height, 'rgba(120,132,168,0.35)', 1);
        ctx.restore();
    }

    /** Diagonal lines over the current path or clip region. @private */
    _hatch(ctx, width, height, colour, lineWidth) {
        ctx.save();
        ctx.clip();
        ctx.strokeStyle = colour;
        ctx.lineWidth = lineWidth;
        ctx.beginPath();
        for (let at = -height; at < width + height; at += CladePanel.HATCH_SPACING) {
            ctx.moveTo(at, 0);
            ctx.lineTo(at + height, height);
        }
        ctx.stroke();
        ctx.restore();
    }

    /** The strip beside the chart: where one stands and the way back. @private */
    _drawStrip() {
        if (!this._model) {
            this._strip.innerHTML = '';
            return;
        }
        const path = this._model.path;
        const fold = `<button class="clade-fold" title="${this._collapsed ? 'Expand' : 'Collapse'}">`
            + (this._collapsed ? '▲' : '▼') + '</button>';
        const step = (label, back, active) =>
            `<div class="clade-step${active ? ' clickable' : ''}" data-back="${back}">${label}</div>`;

        if (this._collapsed) {
            this._strip.innerHTML = '<div class="clade-strip-head">'
                + '<div class="clade-step clickable" data-back="0">all</div>' + fold + '</div>';
        } else {
            // The steps stand under each other; a deep path keeps its start and its last steps.
            // One line is always left free for the founder under the pointer, which appears and
            // disappears while the panel stands still.
            const room = Math.floor(((this._chartHeight() ?? 60) - 40) / CladePanel.STRIP_LINE_HEIGHT);
            const fits = Math.max(2, room - 1);
            const shown = path.length > fits ? path.slice(-fits) : path;
            const skipped = path.length - shown.length;
            this._strip.innerHTML = '<div class="clade-strip-head">'
                + step('all', 0, path.length > 0) + fold + '</div>'
                + (skipped ? step('…', skipped, true) : '')
                + shown.map((genome, i) => step(ValueFormatter.formatGenomeHash(genome),
                    skipped + i + 1, i < shown.length - 1)).join('')
                + (this._hovered
                    ? `<div class="clade-step hovered">${ValueFormatter.formatGenomeHash(this._hovered.founder)}</div>`
                    : '');
        }

        this._strip.querySelectorAll('.clade-step.clickable').forEach(el => {
            el.addEventListener('click', (e) => {
                e.stopPropagation();
                this._model.backTo(Number(el.dataset.back));
                this._hovered = null;
                this._drawStrip();
                this.redraw();
                this._onLevelChanged();
            });
        });
        this._strip.querySelector('.clade-fold')?.addEventListener('click', (e) => {
            e.stopPropagation();
            this._collapsed = !this._collapsed;
            this._layout();
            this._drawStrip();
            this.redraw();
        });
    }

    /** Places the panel over the timeline, its chart exactly above the track. @private */
    _layout() {
        const track = this._trackRect();
        const timeline = document.getElementById('timeline-panel').getBoundingClientRect();
        const left = this._collapsed ? track.right : track.left - 1;
        this._panel.style.left = left + 'px';
        this._panel.style.width = (timeline.right - left + 1) + 'px';
        this._panel.style.bottom = (window.innerHeight - timeline.top + (this._collapsed ? 0 : 4)) + 'px';
        // The chart decides the height, not the strip beside it: a line appearing there, as the
        // hovered founder does, would otherwise push the panel upwards under the pointer
        const height = this._chartHeight();
        this._panel.style.height = this._collapsed || height === null ? '' : (height + 2) + 'px';
        this._panel.classList.toggle('collapsed', this._collapsed);
        this._canvas.style.display = this._collapsed ? 'none' : 'block';
        this._canvas.style.width = track.width + 'px';
    }

    /**
     * Measured fresh every time: the switch beside the track shortens it, and a resize moves it.
     * @private
     */
    _trackRect() {
        return document.getElementById('timeline-track-container').getBoundingClientRect();
    }

    /**
     * Tall enough that the panel's top edge meets the minimap's, which sits beside it, or null
     * while the minimap has no place yet.
     * <p>
     * Right after a page load the minimap is in the document but not laid out, and its rectangle
     * reads as zero. Taken at face value that makes the panel as tall as the window.
     * @private
     */
    _chartHeight() {
        const minimap = document.getElementById('minimap-panel');
        const box = minimap && !minimap.classList.contains('hidden')
            ? minimap.getBoundingClientRect()
            : document.getElementById('minimap-panel-collapsed')?.getBoundingClientRect();
        if (!box || box.height === 0) {
            return null;
        }
        const bottom = document.getElementById('timeline-panel').getBoundingClientRect().top - 4;
        const border = 2;
        return Math.max(60, Math.round(bottom - box.top - border));
    }

    /** The band under the pointer, or null where a click would do nothing. @private */
    _bandAt(e) {
        if (!this._model) {
            return null;
        }
        const stacks = this._model.stacks();
        if (!stacks.length) {
            return null;
        }
        const box = this._canvas.getBoundingClientRect();
        const px = (e.clientX - box.left) / box.width * this._canvas.width;
        const share = 1 - (e.clientY - box.top) / box.height;
        const ranges = this._getRanges() ?? [];
        const first = TickGrid.firstTick(ranges) ?? 0;
        const span = Math.max(1, (TickGrid.lastTick(ranges) ?? 0) - first);
        const at = (tick) => (tick - first) / span * this._canvas.width;

        let nearest = stacks[0];
        for (const stack of stacks) {
            if (Math.abs(at(stack.tick) - px) < Math.abs(at(nearest.tick) - px)) {
                nearest = stack;
            }
        }
        const index = nearest.tops.findIndex(top => share <= top);
        const band = index >= 0 ? this._model.bands[index] : null;
        return band && band.kind === 'clade' ? band : null;
    }

    /** @private */
    _onMove(e) {
        const band = this._bandAt(e);
        this._canvas.style.cursor = band ? 'pointer' : 'default';
        if (band !== this._hovered) {
            this._hovered = band;
            this.redraw();
            this._drawStrip();
        }
    }

    /** @private */
    _onLeave() {
        if (this._hovered) {
            this._hovered = null;
            this.redraw();
            this._drawStrip();
        }
    }

    /** @private */
    _onClick(e) {
        // The container below navigates on a click of its own; entering a clade is not that
        e.stopPropagation();
        const band = this._bandAt(e);
        if (!band) {
            return;
        }
        this._model.enter(band);
        this._hovered = null;
        this._drawStrip();
        this.redraw();
        this._onLevelChanged();
    }
}
