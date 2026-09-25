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

    /** Where the open state of the chart is remembered between sessions. */
    static OPEN_KEY = 'evochora-clade-panel-open';

    /**
     * How much of the chart's ground is mixed into a band's colour, darkening it.
     * <p>
     * The same colours carry the organisms in the environment, the list and the minimap, where a
     * few pixels have to be seen at a glance and every bit of brightness counts. Here they cover
     * broad areas right under the eye, and there they are quieter for it.
     */
    static MUTED = 0.30;

    /** How far a band's colour is drawn towards its own grey, taking the edge off the hue. */
    static DESATURATED = 0.16;

    /** What the field says about itself while it holds a genome this run has. */
    static FIELD_TITLE = 'The clade the colours stand for. '
        + 'Type a genome to enter it, clear to go back to all.';

    /**
     * @param {object} callbacks
     * @param {function(): Array<object>} callbacks.getRanges - The run's recorded tick ranges.
     * @param {function(): number} callbacks.getCurrentTick - The tick shown in the environment.
     * @param {function(): Array<object>} callbacks.getOrganisms - The organisms of the tick shown.
     * @param {function(): void} callbacks.onLevelChanged - Called after entering or leaving a clade.
     */
    constructor({ getRanges, getCurrentTick, getOrganisms, onLevelChanged }) {
        this._getRanges = getRanges;
        this._getCurrentTick = getCurrentTick;
        this._getOrganisms = getOrganisms;
        this._onLevelChanged = onLevelChanged;

        this._model = null;
        this._hovered = null;
        /** Whether the chart is shown; the strip and the field are always there. */
        this._open = localStorage.getItem(CladePanel.OPEN_KEY) !== 'false';

        this._field = document.getElementById('clade-input');
        this._fold = document.getElementById('clade-fold');
        this._ghost = document.getElementById('clade-ghost');

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

        this._fold?.addEventListener('click', () => this.toggle());
        this._field?.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                this._applyField();
            } else if (e.key === 'Tab' && this._ghost?.dataset.rest) {
                // Tab takes the completion instead of leaving the field
                e.preventDefault();
                this._field.value = this._ghost.dataset.whole;
                this._suggest();
            }
            e.stopPropagation();          // the tick shortcuts must not fire while typing a genome
        });
        this._field?.addEventListener('input', () => this._suggest());
        this._field?.addEventListener('blur', () => {
            this._clearSuggestion();
            this._applyField();
        });

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
        if (this._field && document.activeElement !== this._field) {
            this._field.value = this._model ? this._model.label : '';
            this._field.disabled = !this._model;
            this._clearSuggestion();
        }
        if (this._fold) {
            this._fold.textContent = this._open ? '\u25bc' : '\u25b2';
            this._fold.title = this._open ? 'Hide the clades over time' : 'Show the clades over time';
            this._fold.disabled = !this._model;
        }
        if (!this._model || !this._open || this._chartHeight() === null) {
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
        this._follow();
    }

    /** Whether a tree has been handed over. @returns {boolean} */
    get hasModel() {
        return this._model !== null;
    }

    /**
     * Enters the clade of a genome named by its label, as a click on one of its bands would.
     * Called from elsewhere in the view, where a genome is written and can be clicked.
     *
     * @param {string} label Six characters as a genome is written everywhere else
     */
    enterByLabel(label) {
        if (!this._model || !this._field) {
            return;
        }
        this._field.value = label;
        this._applyField();
    }

    /** Shows or hides the chart; the level that is entered is kept either way. */
    toggle() {
        this._open = !this._open;
        localStorage.setItem(CladePanel.OPEN_KEY, String(this._open));
        this._follow();
    }

    /**
     * Shows what the typed characters complete to, where only one genome of the run begins with
     * them. The rest stands behind the field in a dimmed tone; Tab takes it.
     * @private
     */
    _suggest() {
        if (!this._ghost || !this._field) {
            return;
        }
        const typed = this._field.value;
        const whole = this._model && typed ? this._model.completeLabel(typed) : null;
        if (!whole || whole === typed) {
            this._clearSuggestion();
            return;
        }
        this._ghost.dataset.rest = whole.slice(typed.length);
        this._ghost.dataset.whole = whole;
        // The field is monospace, so the offset of the rest is the number of characters typed
        this._ghost.textContent = ' '.repeat(typed.length) + this._ghost.dataset.rest;
    }

    /** @private */
    _clearSuggestion() {
        if (!this._ghost) {
            return;
        }
        this._ghost.textContent = '';
        delete this._ghost.dataset.rest;
        delete this._ghost.dataset.whole;
    }

    /** Takes what the field says and enters that clade, or reports that there is no such genome. */
    _applyField() {
        if (!this._model || !this._field) {
            return;
        }
        const wanted = this._field.value.trim();
        if (wanted === this._model.label) {
            return;
        }
        if (this._model.enterByLabel(wanted)) {
            this._clearSuggestion();
            this._field.classList.remove('unknown');
            this._field.title = CladePanel.FIELD_TITLE;
            this._hovered = null;
            this._follow();
            this._onLevelChanged();
        } else {
            // Not silently ignored: the field says what it could not do until it is corrected
            this._field.classList.add('unknown');
            this._field.title = `No genome ${wanted} in this run`;
        }
    }

    /** Draws the chart and the strip again, for a tick that has changed under them. */
    refresh() {
        if (!this._model || !this._open) {
            return;
        }
        this._drawStrip();
        this.redraw();
    }

    /** Draws the chart from the model's current level. */
    redraw() {
        if (!this._model || !this._open) {
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
            ctx.fillStyle = CladePanel._muted(bands[i].colour ?? OTHER_COLOUR);
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

    /**
     * A band's colour as the chart draws it: mixed with the ground it lies on.
     *
     * @param {string} hex The colour of the band, as the model gives it
     * @returns {string} The same colour, quieter
     * @private
     */
    static _muted(hex) {
        const value = parseInt(hex.slice(1), 16);
        let r = (value >> 16) & 0xff;
        let g = (value >> 8) & 0xff;
        let b = value & 0xff;

        // Towards its own brightness first, which takes the edge off without shifting the hue
        const grey = 0.299 * r + 0.587 * g + 0.114 * b;
        const towards = (channel) =>
            channel * (1 - CladePanel.DESATURATED) + grey * CladePanel.DESATURATED;
        r = towards(r);
        g = towards(g);
        b = towards(b);

        // Then towards the ground it lies on, which darkens it
        const mix = (channel, ground) =>
            Math.round(channel * (1 - CladePanel.MUTED) + ground * CladePanel.MUTED);
        return `rgb(${mix(r, 0x15)},${mix(g, 0x15)},${mix(b, 0x1d)})`;
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
        // What each step carries at the tick on screen, and how many clades stand under it
        const living = (this._getOrganisms() ?? [])
            .filter(organism => !organism.isDead)
            .map(organism => organism.genomeHash);
        const shares = this._model.sharesAlongPath(living);

        const step = (label, back, active) => {
            const share = shares[back];
            const under = this._model.cladesUnderStep(back);
            return `<div class="clade-step${active ? ' clickable' : ''}" data-back="${back}">`
                + `<span class="clade-share">${share > 0 ? Math.round(share * 100) + '%' : ''}</span>`
                + `<span class="clade-name">${label}</span>`
                + `<span class="clade-under">${under > 0 ? '\u203a' + under : ''}</span></div>`;
        };

        // The steps stand under each other and the strip scrolls where they do not fit, held at
        // its foot: what one needs is the way out of where one stands, not the way in
        this._strip.innerHTML = step('all', 0, path.length > 0)
            + path.map((genome, i) => step(ValueFormatter.formatGenomeHash(genome),
                i + 1, i < path.length - 1)).join('')
            + (this._hovered
                ? '<div class="clade-step hovered"><span class="clade-share"></span>'
                  + `<span class="clade-name">${ValueFormatter.formatGenomeHash(this._hovered.founder)}</span>`
                  + '<span class="clade-under"></span></div>'
                : '');
        this._strip.scrollTop = this._strip.scrollHeight;

        this._strip.querySelectorAll('.clade-step.clickable').forEach(el => {
            el.addEventListener('click', (e) => {
                e.stopPropagation();
                this._model.backTo(Number(el.dataset.back));
                this._hovered = null;
                this._follow();
                this._onLevelChanged();
            });
        });
    }

    /** Places the panel over the timeline, its chart exactly above the track. @private */
    _layout() {
        const track = this._trackRect();
        const timeline = document.getElementById('timeline-panel').getBoundingClientRect();
        const left = track.left - 1;
        this._panel.style.left = left + 'px';
        this._panel.style.width = (timeline.right - left + 1) + 'px';
        this._panel.style.bottom = (window.innerHeight - timeline.top + 4) + 'px';
        // The chart decides the height, not the strip beside it: a line appearing there, as the
        // hovered founder does, would otherwise push the panel upwards under the pointer
        const height = this._chartHeight();
        this._panel.style.height = height === null ? '' : (height + 2) + 'px';
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
        this._follow();
        this._onLevelChanged();
    }
}
