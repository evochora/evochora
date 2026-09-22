import { isTextEntry } from '../interaction/EditableTarget.js';

/** The key that opens the help, on every layout that writes it with a shift. */
const HELP_KEY = '?';

/**
 * The help: a panel beside the logo that opens a window listing the keys and what they do.
 *
 * <p>The window is a dialog over the whole page. While it is open it takes every key for itself,
 * so that the timeline does not step and the view does not move behind it; the help key and the
 * escape key close it again. The panel keeps to the right of the element it is anchored to,
 * whose width follows its content, and matches its height.
 *
 * @class HelpOverlay
 */
export class HelpOverlay {
    /** Pixels between the anchor and the panel. */
    static GAP = 8;

    /**
     * @param {object} options
     * @param {HTMLElement} options.button - The panel that opens and closes the help.
     * @param {HTMLElement} options.overlay - The dialog, hidden until the help is opened.
     * @param {HTMLElement} options.anchor - The panel the button keeps beside.
     */
    constructor({ button, overlay, anchor }) {
        this.button = button;
        this.overlay = overlay;
        this.anchor = anchor;
        this.dialog = overlay.querySelector('.help-dialog');
        this.lastFocused = null;

        button.addEventListener('click', () => this.toggle());
        overlay.addEventListener('click', event => {
            if (event.target === overlay) this.close();
        });
        window.addEventListener('keydown', event => this._onKeyDown(event), true);
        // The anchor grows with the switcher button rendered into it and with the font of the
        // logo once it has loaded, and the panel follows it
        new ResizeObserver(() => this.place()).observe(anchor);

        this.place();
    }

    /** Whether the help is open. */
    isOpen() {
        return !this.overlay.hidden;
    }

    /** Opens the help, keeping the element that had the focus so that it can be given back. */
    open() {
        if (this.isOpen()) return;
        this.lastFocused = document.activeElement;
        this.overlay.hidden = false;
        this.button.setAttribute('aria-expanded', 'true');
        this.dialog.focus();
    }

    /** Closes the help and returns the focus where it was. */
    close() {
        if (!this.isOpen()) return;
        this.overlay.hidden = true;
        this.button.setAttribute('aria-expanded', 'false');
        if (this.lastFocused?.isConnected) this.lastFocused.focus();
        this.lastFocused = null;
    }

    /** Opens the help when it is closed, closes it when it is open. */
    toggle() {
        if (this.isOpen()) this.close(); else this.open();
    }

    /**
     * Puts the panel beside the anchor, at its height. The anchor holds the logo, whose width
     * depends on the font it is drawn in, so the place is read from the anchor rather than set.
     */
    place() {
        const box = this.anchor.getBoundingClientRect();
        const frame = parseFloat(getComputedStyle(this.button).borderBottomWidth) || 0;
        this.button.style.left = `${box.right + HelpOverlay.GAP}px`;
        this.button.style.height = `${box.height - frame}px`;
    }

    /**
     * Takes the keys of the help: the help key opens it, and while it is open every key belongs
     * to it. Keys typed into a text field are text, not shortcuts.
     * @param {KeyboardEvent} event
     * @private
     */
    _onKeyDown(event) {
        if (this.isOpen()) {
            event.stopPropagation();
            if (event.key === 'Escape' || event.key === HELP_KEY) {
                event.preventDefault();
                this.close();
            }
            return;
        }
        if (event.key === HELP_KEY && !isTextEntry(document.activeElement)) {
            event.preventDefault();
            event.stopPropagation();
            this.open();
        }
    }
}
