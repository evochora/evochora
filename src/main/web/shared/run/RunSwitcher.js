import { PipelineStatusPoller } from '../pipeline/PipelineStatusPoller.js';
import { includeStartingRun } from './RunAvailability.js';
import { filterRuns, formatRunId, sortRuns, stepIndex } from './RunList.js';

/**
 * How a run is chosen in a web interface: a field that shows the run on display and, once opened,
 * takes a text to filter by over a list of the runs.
 *
 * This is the behaviour only. The interface that uses it builds the elements and styles them: the
 * analyzer keeps the field in its footer bar, the visualizer in a panel of its own, and neither
 * looks like the other. What they hand over is described at {@link RunSwitcherView}.
 *
 * A run is chosen with a click on its entry, or with Enter on the entry the arrow keys marked;
 * without a marked one Enter takes the first. Escape closes the list. A click outside closes it
 * too, and so does the field losing the focus.
 *
 * Pressing the mouse button on anything of the switcher leaves the focus in the field. A click is
 * a press and a release, and a field that lost the focus at the press would close the list before
 * the release, so that a button held a moment too long chose nothing.
 */
export class RunSwitcher {

    /**
     * @typedef {Object} RunSwitcherView
     * @property {HTMLElement} root - The element holding the whole switcher; a click outside it
     *           closes the list
     * @property {HTMLElement} field - The element a click on which opens the list
     * @property {HTMLElement} value - The element showing the run on display; while the list is
     *           open the filter input stands in its place
     * @property {HTMLElement} list - The element the entries are put into
     * @property {{value: string, input: string, item: string, empty: string}} classes - The class
     *           each element the switcher creates gets
     * @property {HTMLElement} [titled] - An element that carries the whole ID of the run on
     *           display as its tooltip, beside the value element, which always does
     * @property {string} [placeholder] - What the empty filter input says
     * @property {boolean} [closesOnFieldClick] - Whether a click on the field closes an open list
     * @property {function(string): string} [wrapEntries] - Puts the entries' HTML into what the
     *           list element holds; without it the entries are put in as they are
     * @property {function(): void} [onOpened] - Called once the list is filled and open, before
     *           the filter input gets the focus
     * @property {function(): void} [onClosed] - Called once the list is closed
     */

    /**
     * @param {Object} options
     * @param {Function} options.fetchRuns - Async function fetching the runs there are
     * @param {Function} options.getCurrentRunId - Function returning the run on display
     * @param {Function} options.onRunChange - Called with the ID of the run that was chosen
     * @param {RunSwitcherView} options.view - The elements of the interface
     */
    constructor({ fetchRuns, getCurrentRunId, onRunChange, view }) {
        this.fetchRuns = fetchRuns;
        this.getCurrentRunId = getCurrentRunId;
        this.onRunChange = onRunChange;
        this.view = view;
        this.valueEl = view.value;
        this.inputEl = null;
        this.runs = [];
        this.shownRuns = [];
        this.isOpen = false;
        this.markedIndex = -1;

        this._poller = new PipelineStatusPoller();
        this._poller.onChange(() => this._onPipelineStatusChange());

        this._attachEvents();
        this.updateCurrent();
        this._poller.start();
    }

    /** Opens the list of runs with an empty filter. */
    async open() {
        await this._ensureRunsLoaded();
        this._showInput();
        this.markedIndex = -1;
        this._renderList('');
        this.isOpen = true;
        this.view.onOpened?.();
        this.inputEl?.focus();
        this.inputEl?.select();
    }

    /** Closes the list and shows the run on display again. */
    close() {
        this.isOpen = false;
        this._removeInput();
        this.view.onClosed?.();
        this.updateCurrent();
    }

    /** Shows the run on display in the field, with the state of the pipeline where it is its run. */
    updateCurrent() {
        if (!this.valueEl) return;
        const current = this.getCurrentRunId ? (this.getCurrentRunId() || '(none)') : '(none)';
        this.valueEl.textContent = formatRunId(current);
        this.valueEl.title = current;
        if (this.view.titled) {
            this.view.titled.title = current;
        }
        this._updateValueStatus();
    }

    /**
     * The pipeline state as last polled, which a page may use to decide whether data is still to
     * be expected.
     * @returns {{ activeRunId: string|null, status: string|null }}
     */
    pipelineState() {
        return { activeRunId: this._poller.activeRunId, status: this._poller.status };
    }

    /**
     * Registers a handler called whenever the polled pipeline state changes.
     * @param {function(): void} handler
     */
    onPipelineChange(handler) {
        this._poller.onChange(handler);
    }

    /** @private */
    _attachEvents() {
        const { root, field, list } = this.view;

        field.addEventListener('click', (e) => {
            e.stopPropagation();
            if (!this.isOpen) {
                this.open();
            } else if (this.view.closesOnFieldClick && !list.contains(e.target)
                    && e.target !== this.inputEl) {
                this.close();
            }
        });

        list.addEventListener('click', (e) => {
            e.stopPropagation();
            const item = e.target.closest(`.${this.view.classes.item}`);
            if (item && list.contains(item)) {
                this._choose(this.shownRuns[Number(item.dataset.index)]);
            }
        });

        list.addEventListener('mouseover', (e) => {
            const item = e.target.closest(`.${this.view.classes.item}`);
            if (item && list.contains(item)) {
                this.markedIndex = Number(item.dataset.index);
                this._showMarked();
            }
        });

        // The focus stays in the filter input while the button goes down on the switcher, so that
        // the list is still there when the button comes up and the click is complete
        root.addEventListener('mousedown', (e) => {
            if (this.isOpen && e.target !== this.inputEl) {
                e.preventDefault();
            }
        });

        document.addEventListener('click', (e) => {
            if (this.isOpen && !root.contains(e.target)) {
                this.close();
            }
        });
    }

    /**
     * Puts the filter input in the place of the shown run.
     * @private
     */
    _showInput() {
        const input = document.createElement('input');
        input.className = this.view.classes.input;
        input.type = 'text';
        if (this.view.placeholder) {
            input.placeholder = this.view.placeholder;
        }
        input.addEventListener('input', (e) => {
            this.markedIndex = -1;
            this._renderList(e.target.value);
        });
        input.addEventListener('keydown', (e) => this._handleKeyDown(e));
        input.addEventListener('blur', (e) => {
            if (this.isOpen && !this.view.root.contains(e.relatedTarget)) {
                this.close();
            }
        });
        this.valueEl.replaceWith(input);
        this.inputEl = input;
    }

    /**
     * Puts the shown run back in the place of the filter input.
     * @private
     */
    _removeInput() {
        if (!this.inputEl) return;
        const span = document.createElement('span');
        span.className = this.view.classes.value;
        this.inputEl.replaceWith(span);
        this.valueEl = span;
        this.inputEl = null;
    }

    /**
     * @param {KeyboardEvent} e
     * @private
     */
    _handleKeyDown(e) {
        if (e.key === 'Escape') {
            e.preventDefault();
            this.close();
        } else if (e.key === 'Enter') {
            e.preventDefault();
            this._choose(this.shownRuns[this.markedIndex >= 0 ? this.markedIndex : 0]);
        } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            this.markedIndex = stepIndex(this.markedIndex, e.key === 'ArrowDown' ? 1 : -1,
                this.shownRuns.length);
            this._showMarked();
            this.view.list.querySelector(`.${this.view.classes.item}.selected`)
                ?.scrollIntoView({ block: 'nearest' });
        }
    }

    /**
     * Closes the list and hands the chosen run to the page. The list does not wait for the page:
     * loading a run takes as long as its data does, and a list standing open over it reads as a
     * click that did not work. Without a run - Enter on an empty list - the list only closes.
     *
     * @param {{runId: string}|undefined} run - The entry that was chosen
     * @private
     */
    _choose(run) {
        this.close();
        if (run) {
            this.onRunChange(run.runId);
        }
    }

    /**
     * Fills the list with the runs the filter text leaves.
     *
     * @param {string} filterText - What was typed into the filter
     * @private
     */
    _renderList(filterText) {
        const { classes, list } = this.view;
        const { activeRunId, status } = this.pipelineState();
        this.shownRuns = filterRuns(includeStartingRun(this.runs, { activeRunId, status }), filterText);
        const currentRunId = this.getCurrentRunId?.() || null;

        const entries = this.shownRuns.map((run, index) => {
            const itemClasses = [classes.item];
            if (index === this.markedIndex) itemClasses.push('selected');
            if (run.runId === currentRunId) itemClasses.push('current');
            if (run.runId === activeRunId) itemClasses.push(statusClass(status));
            return `<div class="${itemClasses.join(' ')}" data-index="${index}">`
                + `<span class="run-id" title="${run.runId}">${formatRunId(run.runId)}</span>`
                + `</div>`;
        }).join('') || `<div class="${classes.empty}">No runs available</div>`;

        list.innerHTML = this.view.wrapEntries ? this.view.wrapEntries(entries) : entries;
    }

    /**
     * Marks the entry the arrow keys or the pointer are on.
     * @private
     */
    _showMarked() {
        this.view.list.querySelectorAll(`.${this.view.classes.item}`).forEach((item, index) => {
            item.classList.toggle('selected', index === this.markedIndex);
        });
    }

    /**
     * Loads and sorts the runs, once.
     * @private
     */
    async _ensureRunsLoaded() {
        if (this.runs.length) return;
        try {
            this.runs = sortRuns(await this.fetchRuns() || []);
        } catch (error) {
            console.error('[RunSwitcher] Failed to fetch runs:', error);
            this.runs = [];
        }
    }

    /**
     * Gives the shown run the class of the pipeline's state where it is the pipeline's run.
     * @private
     */
    _updateValueStatus() {
        if (!this.valueEl) return;
        this.valueEl.classList.remove('pipeline-running', 'pipeline-degraded');
        const currentRunId = this.getCurrentRunId?.() || null;
        const { activeRunId, status } = this.pipelineState();
        if (currentRunId && currentRunId === activeRunId) {
            const cls = statusClass(status);
            if (cls) this.valueEl.classList.add(cls);
        }
    }

    /**
     * Called by the poller whenever the pipeline state changes.
     * @private
     */
    _onPipelineStatusChange() {
        this._updateValueStatus();
        if (this.isOpen) {
            this._renderList(this.inputEl?.value || '');
        }
    }
}

/**
 * The class an entry or the shown run gets for the state of the pipeline.
 *
 * @param {string|null} status - The pipeline's status
 * @returns {string} The class, or an empty string where the state has none
 */
function statusClass(status) {
    if (status === 'RUNNING') return 'pipeline-running';
    if (status === 'DEGRADED') return 'pipeline-degraded';
    return '';
}
