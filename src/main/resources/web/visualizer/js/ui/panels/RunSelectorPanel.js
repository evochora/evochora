import { PipelineStatusPoller } from '../../../../shared/pipeline/PipelineStatusPoller.js';

/**
 * Run selector panel for choosing simulation runs.
 * Positioned at the bottom-right as a footer panel.
 * Features keyboard navigation (ArrowUp/Down), filter input, and live pipeline status display.
 *
 * @class RunSelectorPanel
 */
export class RunSelectorPanel {
    /**
     * Creates a new RunSelectorPanel.
     * @param {Object} options
     * @param {Function} options.fetchRuns - Async function to fetch available runs
     * @param {Function} options.getCurrentRunId - Function returning current run ID
     * @param {Function} options.onRunChange - Callback when run is changed
     */
    constructor({ fetchRuns, getCurrentRunId, onRunChange }) {
        this.fetchRuns = fetchRuns;
        this.getCurrentRunId = getCurrentRunId;
        this.onRunChange = onRunChange;
        this.runs = [];
        this.filteredRuns = [];
        this.isOpen = false;
        this.selectedIndex = -1;

        this._poller = new PipelineStatusPoller();
        this._poller.onChange(() => this._onPipelineStatusChange());

        this.createDOM();
        this.attachEvents();
        this._poller.start();
    }

    /**
     * Creates the panel DOM elements.
     * @private
     */
    createDOM() {
        this.element = document.createElement('div');
        this.element.id = 'run-selector-panel';
        this.element.className = 'footer-panel';
        this.element.innerHTML = `
            <div class="run-selector-content">
                <span class="run-selector-label">Run:</span>
                <div class="run-selector-field">
                    <span class="run-selector-value"></span>
                    <div class="run-selector-list"></div>
                </div>
            </div>
        `;

        this.contentEl = this.element.querySelector('.run-selector-content');
        this.fieldEl = this.element.querySelector('.run-selector-field');
        this.valueEl = this.element.querySelector('.run-selector-value');
        this.listEl = this.element.querySelector('.run-selector-list');

        document.body.appendChild(this.element);
        this.updateCurrent();
    }

    /**
     * Opens the run selector dropdown.
     */
    async open() {
        await this.ensureRunsLoaded();
        this.showInput('');
        this.renderList('');
        this.isOpen = true;
        this.fieldEl.classList.add('open');
        this.selectedIndex = -1;
        this.inputEl?.focus();
    }

    /**
     * Closes the run selector dropdown.
     */
    close() {
        this.isOpen = false;
        this.fieldEl.classList.remove('open');
        this.teardownInput();
        this.updateCurrent();
    }

    /**
     * Shows the filter input field.
     * @param {string} prefill - Initial value for the input
     * @private
     */
    showInput(prefill) {
        const input = document.createElement('input');
        input.className = 'run-selector-input';
        input.type = 'text';
        input.value = prefill;
        input.placeholder = 'Filter...';
        input.addEventListener('input', (e) => {
            this.selectedIndex = -1;
            this.renderList(e.target.value);
        });
        input.addEventListener('keydown', (e) => this.handleKeyDown(e));
        input.addEventListener('blur', () => {
            // Delay close to allow click on list items
            setTimeout(() => {
                if (this.isOpen && !this.element.contains(document.activeElement)) {
                    this.close();
                }
            }, 150);
        });
        this.valueEl.replaceWith(input);
        this.inputEl = input;
    }

    /**
     * Handles keyboard navigation in the dropdown.
     * @param {KeyboardEvent} e - The keyboard event
     * @private
     */
    handleKeyDown(e) {
        if (e.key === 'Escape') {
            e.preventDefault();
            this.close();
        } else if (e.key === 'Enter') {
            e.preventDefault();
            this.selectCurrent();
        } else if (e.key === 'ArrowDown') {
            e.preventDefault();
            this.navigateList(1);
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            this.navigateList(-1);
        }
    }

    /**
     * Navigates the list selection by delta.
     * @param {number} delta - Direction to move (+1 or -1)
     * @private
     */
    navigateList(delta) {
        if (this.filteredRuns.length === 0) return;

        const newIndex = this.selectedIndex + delta;
        if (newIndex < 0) {
            this.selectedIndex = this.filteredRuns.length - 1;
        } else if (newIndex >= this.filteredRuns.length) {
            this.selectedIndex = 0;
        } else {
            this.selectedIndex = newIndex;
        }

        this.updateListSelection();
        this.scrollToSelected();
    }

    /**
     * Updates the visual selection in the list.
     * @private
     */
    updateListSelection() {
        const items = this.listEl.querySelectorAll('.run-selector-item');
        items.forEach((item, index) => {
            item.classList.toggle('selected', index === this.selectedIndex);
        });
    }

    /**
     * Scrolls the list to make the selected item visible.
     * @private
     */
    scrollToSelected() {
        const selected = this.listEl.querySelector('.run-selector-item.selected');
        if (selected) {
            selected.scrollIntoView({ block: 'nearest' });
        }
    }

    /**
     * Selects the currently highlighted item or the first item.
     * @private
     */
    selectCurrent() {
        const index = this.selectedIndex >= 0 ? this.selectedIndex : 0;
        const run = this.filteredRuns[index];
        if (run) {
            Promise.resolve(this.onRunChange(run.runId)).finally(() => {
                this.close();
            });
        } else {
            this.close();
        }
    }

    /**
     * Removes the filter input and restores the value display.
     * @private
     */
    teardownInput() {
        if (!this.inputEl) return;
        const span = document.createElement('span');
        span.className = 'run-selector-value';
        this.inputEl.replaceWith(span);
        this.valueEl = span;
        this.inputEl = null;
    }

    /**
     * Renders the list with filtered runs.
     * @param {string} filterText - Text to filter runs by
     * @private
     */
    renderList(filterText) {
        const term = (filterText || '').toLowerCase();
        this.filteredRuns = this.runs.filter(r => r.runId && r.runId.toLowerCase().includes(term));

        if (this.filteredRuns.length === 0) {
            this.listEl.innerHTML = `<div class="run-selector-empty">No runs available</div>`;
            return;
        }

        const currentRunId = this.getCurrentRunId?.() || null;
        const { activeRunId, status } = this._pipelineState();

        this.listEl.innerHTML = this.filteredRuns.map((r, index) => {
            const classes = ['run-selector-item'];
            if (index === this.selectedIndex) classes.push('selected');
            if (r.runId === currentRunId) classes.push('current');
            if (r.runId === activeRunId) classes.push(this._statusClass(status));
            return `<div class="${classes.join(' ')}" data-index="${index}">` +
                `<span class="run-id" title="${r.runId}">${this.formatDisplay(r.runId)}</span>` +
                `</div>`;
        }).join('');

        this.listEl.querySelectorAll('.run-selector-item').forEach(item => {
            item.addEventListener('click', () => {
                const index = parseInt(item.dataset.index, 10);
                const run = this.filteredRuns[index];
                if (run) {
                    Promise.resolve(this.onRunChange(run.runId)).finally(() => {
                        this.close();
                    });
                }
            });
            item.addEventListener('mouseenter', () => {
                this.selectedIndex = parseInt(item.dataset.index, 10);
                this.updateListSelection();
            });
        });
    }

    /**
     * Updates the displayed current run ID and pipeline status indicator.
     */
    updateCurrent() {
        if (!this.valueEl) return;
        const current = this.getCurrentRunId ? (this.getCurrentRunId() || '(none)') : '(none)';
        this.valueEl.textContent = this.formatDisplay(current);
        this.valueEl.title = current;
        this._updateValueStatus();
    }

    /**
     * Shortens a run ID for display.
     * @param {string} id - The run ID
     * @returns {string} Shortened ID
     * @private
     */
    short(id) {
        const value = id || '';
        return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value;
    }

    /**
     * Formats a run ID for display (extracts date/time if possible).
     * @param {string} runId - The run ID to format
     * @returns {string} Formatted display string
     */
    formatDisplay(runId) {
        if (!runId) return '(none)';
        // Pattern: YYYYMMDD-HHMMSSxx-uuid
        const match = runId.match(/^(\d{8})-(\d{8})-([0-9a-fA-F-]+)$/);
        if (match) {
            const dateDigits = match[1];
            const timeDigits = match[2];
            const uuid = match[3];
            const yyyy = dateDigits.slice(0, 4);
            const mm = dateDigits.slice(4, 6);
            const dd = dateDigits.slice(6, 8);
            const hh = timeDigits.slice(0, 2);
            const mi = timeDigits.slice(2, 4);
            const ss = timeDigits.slice(4, 6);
            const last4 = uuid.replace(/-/g, '').slice(-4);
            return `${yyyy}-${mm}-${dd}/${hh}:${mi}:${ss}-${last4}`;
        }
        // Fallback: try generic date/time with separators
        const sepMatch = runId.match(/(\d{4}-\d{2}-\d{2})[T_\- ]?(?:(\d{2})[:\-_]?(\d{2})[:\-_]?(\d{2}))?/);
        if (sepMatch) {
            const date = sepMatch[1];
            const hh = sepMatch[2] || '00';
            const mi = sepMatch[3] || '00';
            const ss = sepMatch[4] || '00';
            const last4 = runId.replace(/-/g, '').slice(-4);
            return `${date}/${hh}:${mi}:${ss}-${last4}`;
        }
        return this.short(runId);
    }

    /**
     * Sorts runs by start time (newest first).
     * @param {Array} runs - Array of run objects
     * @returns {Array} Sorted runs
     * @private
     */
    sortRuns(runs) {
        const score = (r) => {
            if (r.startTime) return r.startTime;
            const m = (r.runId || '').match(/^(\d{8})-(\d{8})/);
            if (m) {
                return Number(m[1] + m[2]);
            }
            return 0;
        };
        return [...runs].sort((a, b) => score(b) - score(a));
    }

    /**
     * Ensures runs are loaded and sorted.
     * @private
     */
    async ensureRunsLoaded() {
        if (this.runs.length) return;
        try {
            const runs = await this.fetchRuns();
            this.runs = this.sortRuns(runs || []);
        } catch (error) {
            console.error('[RunSelectorPanel] Failed to fetch runs:', error);
            this.runs = [];
        }
    }

    /**
     * Attaches event listeners.
     * @private
     */
    attachEvents() {
        this.fieldEl.addEventListener('click', (e) => {
            e.stopPropagation();
            if (!this.isOpen) {
                this.open();
            }
        });

        this.listEl.addEventListener('click', (e) => {
            e.stopPropagation();
        });

        document.addEventListener('click', (e) => {
            if (this.isOpen && !this.element.contains(e.target)) {
                this.close();
            }
        });
    }

    /**
     * Shows the panel.
     */
    show() {
        this.element.classList.remove('hidden');
    }

    /**
     * Hides the panel.
     */
    hide() {
        this.element.classList.add('hidden');
    }

    // ── Pipeline status ──────────────────────────────────────

    /** @returns {{ activeRunId: string|null, status: string|null }} */
    _pipelineState() {
        return { activeRunId: this._poller.activeRunId, status: this._poller.status };
    }

    /** Maps pipeline status to a CSS class for the list item. */
    _statusClass(status) {
        if (status === 'RUNNING') return 'pipeline-running';
        if (status === 'DEGRADED') return 'pipeline-degraded';
        return '';
    }

    /** Updates the collapsed value element's status class. */
    _updateValueStatus() {
        if (!this.valueEl) return;
        this.valueEl.classList.remove('pipeline-running', 'pipeline-degraded');
        const currentRunId = this.getCurrentRunId?.() || null;
        const { activeRunId, status } = this._pipelineState();
        if (currentRunId && currentRunId === activeRunId) {
            const cls = this._statusClass(status);
            if (cls) this.valueEl.classList.add(cls);
        }
    }

    /** Called by the poller whenever pipeline status changes. */
    _onPipelineStatusChange() {
        this._updateValueStatus();
        if (this.isOpen) {
            // Re-render to update status classes on list items
            const filterText = this.inputEl?.value || '';
            this.renderList(filterText);
        }
    }
}
