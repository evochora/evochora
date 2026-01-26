/**
 * Run selector panel for choosing simulation runs.
 * Positioned at the bottom-right as a footer panel.
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
        this.isOpen = false;

        this.createDOM();
        this.attachEvents();
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
                <span class="run-selector-value"></span>
                <div class="run-selector-overlay"></div>
            </div>
        `;

        this.contentEl = this.element.querySelector('.run-selector-content');
        this.valueEl = this.element.querySelector('.run-selector-value');
        this.overlayEl = this.element.querySelector('.run-selector-overlay');

        document.body.appendChild(this.element);
        this.updateCurrent();
    }

    /**
     * Opens the run selector dropdown.
     */
    async open() {
        await this.ensureRunsLoaded();
        this.showInput('');
        this.renderOverlay('');
        this.isOpen = true;
        this.overlayEl.classList.add('visible');
        this.inputEl?.focus();
        this.inputEl?.select();
    }

    /**
     * Closes the run selector dropdown.
     */
    close() {
        this.isOpen = false;
        this.overlayEl.classList.remove('visible');
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
        input.addEventListener('input', (e) => this.renderOverlay(e.target.value));
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                this.close();
            } else if (e.key === 'Enter') {
                this.selectFirst();
            }
        });
        input.addEventListener('blur', () => {
            // Delay close to allow click on overlay items
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
     * Renders the overlay with filtered run list.
     * @param {string} filterText - Text to filter runs by
     * @private
     */
    renderOverlay(filterText) {
        const term = (filterText || '').toLowerCase();
        const matches = this.runs.filter(r => r.runId && r.runId.toLowerCase().includes(term));
        const listItems = matches.map(r => `
            <div class="run-selector-item" data-run="${r.runId}">
                <span class="run-id" title="${r.runId}">${this.formatDisplay(r.runId)}</span>
            </div>
        `).join('') || `<div class="run-selector-empty">No runs available</div>`;

        this.overlayEl.innerHTML = `
            <div class="run-selector-list">${listItems}</div>
        `;

        this.overlayEl.querySelectorAll('.run-selector-item').forEach(item => {
            item.addEventListener('click', () => {
                const runId = item.dataset.run;
                if (runId) {
                    Promise.resolve(this.onRunChange(runId)).finally(() => {
                        this.close();
                    });
                } else {
                    this.close();
                }
            });
        });
    }

    /**
     * Selects the first item in the filtered list.
     * @private
     */
    selectFirst() {
        const first = this.overlayEl.querySelector('.run-selector-item');
        if (first) {
            const runId = first.dataset.run;
            if (runId) {
                Promise.resolve(this.onRunChange(runId)).finally(() => {
                    this.close();
                });
                return;
            }
        }
        this.close();
    }

    /**
     * Updates the displayed current run ID.
     */
    updateCurrent() {
        if (!this.valueEl) return;
        const current = this.getCurrentRunId ? (this.getCurrentRunId() || '(none)') : '(none)';
        this.valueEl.textContent = this.formatDisplay(current);
        this.valueEl.title = current;
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
        this.contentEl.addEventListener('click', (e) => {
            e.stopPropagation();
            if (this.isOpen) {
                this.close();
            } else {
                this.open();
            }
        });

        this.overlayEl.addEventListener('click', (e) => {
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
}
