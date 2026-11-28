import { RunSwitcher } from '../../../../shared/run/RunSwitcher.js';

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
        this.createDOM();

        this.runSwitcher = new RunSwitcher({
            fetchRuns,
            getCurrentRunId,
            onRunChange,
            view: {
                root: this.element,
                field: this.fieldEl,
                value: this.element.querySelector('.run-selector-value'),
                list: this.listEl,
                classes: {
                    value: 'run-selector-value',
                    input: 'run-selector-input',
                    item: 'run-selector-item',
                    empty: 'run-selector-empty'
                },
                placeholder: 'Filter...',
                onOpened: () => this.fieldEl.classList.add('open'),
                onClosed: () => this.fieldEl.classList.remove('open')
            }
        });
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
        this.listEl = this.element.querySelector('.run-selector-list');

        document.body.appendChild(this.element);
    }

    /**
     * Updates the displayed current run ID and pipeline status indicator.
     */
    updateCurrent() {
        this.runSwitcher.updateCurrent();
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
