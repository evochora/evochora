import { RunSwitcher } from '../../../shared/run/RunSwitcher.js';

/**
 * The footer bar of the analyzer: on the left what says which part of the run is shown, on the
 * right the field that shows the run and lets another one be chosen.
 */
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
        this.render();

        this.runSwitcher = new RunSwitcher({
            fetchRuns,
            getCurrentRunId,
            onRunChange,
            view: {
                root: this.runEl,
                field: this.runEl,
                value: this.element.querySelector('.footer-run-value'),
                list: this.overlayEl,
                titled: this.runEl,
                classes: {
                    value: 'footer-run-value',
                    input: 'footer-run-input',
                    item: 'footer-run-item',
                    empty: 'footer-empty'
                },
                closesOnFieldClick: true,
                wrapEntries: entries => `<div class="footer-run-list">${entries}</div>`,
                onOpened: () => {
                    this.positionOverlay();
                    this.overlayEl.classList.add('visible');
                },
                onClosed: () => this.overlayEl.classList.remove('visible')
            }
        });

        window.addEventListener('resize', () => {
            if (this.runSwitcher.isOpen) {
                this.positionOverlay();
            }
        });
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
        this.leftEl = this.element.querySelector('.footer-left');
        this.runEl = this.element.querySelector('.footer-run');
        this.overlayEl = this.element.querySelector('.footer-overlay');
    }

    /**
     * The left part of the bar, which the page fills with what says which part of the run is shown.
     * @returns {HTMLElement}
     */
    get leftSlot() {
        return this.leftEl;
    }

    /**
     * Puts the list of runs under the field that shows the run, as wide as that field.
     */
    positionOverlay() {
        const field = this.runEl.querySelector('.footer-run-input, .footer-run-value');
        if (field) {
            const runRect = this.runEl.getBoundingClientRect();
            const fieldRect = field.getBoundingClientRect();
            this.overlayEl.style.left = `${fieldRect.left - runRect.left}px`;
            this.overlayEl.style.width = `${field.offsetWidth}px`;
        }
    }

    /** Shows the run on display in the field. */
    updateCurrent() {
        this.runSwitcher.updateCurrent();
    }

    /**
     * Returns the pipeline state as last polled, which the page may use to decide whether data is
     * still to be expected.
     * @returns {{ activeRunId: string|null, status: string|null }}
     */
    pipelineState() {
        return this.runSwitcher.pipelineState();
    }

    /**
     * Registers a handler called whenever the polled pipeline state changes.
     * @param {function(): void} handler
     */
    onPipelineChange(handler) {
        this.runSwitcher.onPipelineChange(handler);
    }
}
