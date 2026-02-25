import { PipelineStatusPoller } from '../pipeline/PipelineStatusPoller.js';

export class Footer {
    /**
     * Creates a new Footer.
     * @param {Object} options
     * @param {HTMLElement} options.element - Container element
     * @param {Function} options.fetchRuns - Async function to fetch available runs
     * @param {Function} options.getCurrentRunId - Function returning current run ID
     * @param {Function} options.onRunChange - Callback when run is changed
     */
    constructor({ element, fetchRuns, getCurrentRunId, onRunChange }) {
        if (!element) {
            throw new Error('Footer requires a target element.');
        }
        this.element = element;
        this.fetchRuns = fetchRuns;
        this.getCurrentRunId = getCurrentRunId;
        this.onRunChange = onRunChange;
        this.runs = [];
        this.isOpen = false;

        this._poller = new PipelineStatusPoller();
        this._poller.onChange(() => this._onPipelineStatusChange());

        this.render();
        this.attachEvents();
        this._poller.start();
    }

    render() {
        this.element.innerHTML = `
            <div class="footer-bar">
                <div class="footer-left"></div>
                <div class="footer-right">
                    <div class="footer-run" title="">
                        <span class="footer-run-label">Run:</span>
                        <span class="footer-run-value"></span>
                        <div class="footer-overlay"></div>
                    </div>
                </div>
            </div>
        `;
        this.barEl = this.element.querySelector('.footer-bar');
        this.runEl = this.element.querySelector('.footer-run');
        this.valueEl = this.element.querySelector('.footer-run-value');
        this.overlayEl = this.element.querySelector('.footer-overlay');
        this.updateCurrent();
    }

    async open() {
        await this.ensureRunsLoaded();
        this.showInput('');
        this.renderOverlay('');
        this.positionOverlay();
        this.isOpen = true;
        this.overlayEl.classList.add('visible');
        this.inputEl?.focus();
        this.inputEl?.select();
    }

    close() {
        this.isOpen = false;
        this.overlayEl.classList.remove('visible');
        this.teardownInput();
        this.updateCurrent();
    }

    showInput(prefill) {
        const input = document.createElement('input');
        input.className = 'footer-run-input';
        input.value = prefill;
        input.addEventListener('input', (e) => this.renderOverlay(e.target.value));
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                this.close();
            } else if (e.key === 'Enter') {
                this.selectFirst();
            }
        });
        input.addEventListener('blur', (e) => {
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

    teardownInput() {
        if (!this.inputEl) return;
        const span = document.createElement('span');
        span.className = 'footer-run-value';
        this.inputEl.replaceWith(span);
        this.valueEl = span;
        this.inputEl = null;
    }

    renderOverlay(filterText) {
        const term = (filterText || '').toLowerCase();
        const matches = this.runs.filter(r => r.runId && r.runId.toLowerCase().includes(term));

        const currentRunId = this.getCurrentRunId?.() || null;
        const { activeRunId, status } = this._pipelineState();

        const listItems = matches.map(r => {
            const classes = ['footer-run-item'];
            if (r.runId === currentRunId) classes.push('current');
            if (r.runId === activeRunId) classes.push(this._statusClass(status));
            return `<div class="${classes.join(' ')}" data-run="${r.runId}">` +
                `<span class="run-id" title="${r.runId}">${this.formatDisplay(r.runId)}</span>` +
                `</div>`;
        }).join('') || `<div class="footer-empty">No runs available</div>`;

        this.overlayEl.innerHTML = `
            <div class="footer-run-list">${listItems}</div>
        `;

        this.overlayEl.querySelectorAll('.footer-run-item').forEach(item => {
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

    positionOverlay() {
        if (!this.overlayEl || !this.runEl) return;
        // Sync width and position with input or value element
        const el = this.inputEl || this.valueEl;
        if (el) {
            const runRect = this.runEl.getBoundingClientRect();
            const elRect = el.getBoundingClientRect();
            const left = elRect.left - runRect.left;
            this.overlayEl.style.left = `${left}px`;
            this.overlayEl.style.width = `${el.offsetWidth}px`;
        }
    }

    selectFirst() {
        const first = this.overlayEl.querySelector('.footer-run-item');
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

    updateCurrent() {
        if (!this.valueEl) return;
        const current = this.getCurrentRunId ? (this.getCurrentRunId() || '(none)') : '(none)';
        this.valueEl.textContent = this.formatDisplay(current);
        this.valueEl.title = current;
        if (this.runEl) {
            this.runEl.title = current;
        }
        this._updateValueStatus();
    }

    short(id) {
        const value = id || '';
        return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value;
    }

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

    sortRuns(runs) {
        const score = (r) => {
            if (r.startTime) return r.startTime;
            const m = (r.runId || '').match(/^(\d{8})-(\d{8})/);
            if (m) {
                // Combine date and time digits to a sortable number: YYYYMMDDHHMMSSxx
                return Number(m[1] + m[2]);
            }
            return 0;
        };
        return [...runs].sort((a, b) => score(b) - score(a));
    }

    /**
     * Ensures runs are loaded and sorted once.
     * @private
     */
    async ensureRunsLoaded() {
        if (this.runs.length) return;
        try {
            const runs = await this.fetchRuns();
            this.runs = this.sortRuns(runs || []);
        } catch (error) {
            console.error('[Footer] Failed to fetch runs:', error);
            this.runs = [];
        }
    }

    attachEvents() {
        this.runEl.addEventListener('click', (e) => {
            e.stopPropagation();
            if (this.isOpen) {
                this.close();
            } else {
                this.open();
            }
        });

        this.overlayEl.addEventListener('click', (e) => {
            // Prevent overlay clicks from bubbling to document
            e.stopPropagation();
        });

        window.addEventListener('resize', () => {
            if (this.isOpen) {
                this.positionOverlay();
            }
        });

        document.addEventListener('click', (e) => {
            if (this.isOpen && !this.element.contains(e.target)) {
                this.close();
            }
        });
    }

    // ── Pipeline status ──────────────────────────────────────

    /** @returns {{ activeRunId: string|null, status: string|null }} */
    _pipelineState() {
        return { activeRunId: this._poller.activeRunId, status: this._poller.status };
    }

    /** Maps pipeline status to a CSS class for a list item. */
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
            const filterText = this.inputEl?.value || '';
            this.renderOverlay(filterText);
        }
    }
}
