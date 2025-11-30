/**
 * Manages the global loading state and controls the visibility of the loading indicator.
 * This class centralizes the tracking of both active API requests and long-running
 * local tasks (like rendering) to provide a unified loading feedback to the user.
 *
 * @class LoadingManager
 */
class LoadingManager {
    constructor() {
        this.activeRequestCount = 0;
        this.activeTaskCount = 0;
        this.loadingIndicator = null;
        this.logoText = null;

        // Defer DOM-related initialization until the document is ready.
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', () => this.initializeDomElements());
        } else {
            this.initializeDomElements();
        }
    }

    /**
     * Finds and initializes the required DOM elements for the loading indicator.
     * @private
     */
    initializeDomElements() {
        this.loadingIndicator = document.getElementById('loading-indicator');
        this.logoText = document.querySelector('.logo-text');
        if (this.logoText) {
            const logoWidth = this.logoText.offsetWidth;
            if (logoWidth > 0) {
                document.documentElement.style.setProperty('--logo-text-width', `${logoWidth}px`);
            }
        }
    }

    /**
     * Updates the loading indicator's visibility based on active requests and tasks.
     * The indicator is shown if there is at least one active request or task.
     * @private
     */
    _updateIndicator() {
        if (!this.loadingIndicator) {
            this.initializeDomElements(); // Attempt to re-initialize if not found
            if (!this.loadingIndicator) return; // Still not found, exit
        }

        // Ensure logo width is set before showing the indicator
        if (!document.documentElement.style.getPropertyValue('--logo-text-width')) {
             if (this.logoText) {
                const logoWidth = this.logoText.offsetWidth;
                if (logoWidth > 0) {
                    document.documentElement.style.setProperty('--logo-text-width', `${logoWidth}px`);
                }
            }
        }

        if (this.activeRequestCount > 0 || this.activeTaskCount > 0) {
            this.loadingIndicator.classList.add('active');
        } else {
            this.loadingIndicator.classList.remove('active');
        }
    }

    /**
     * Registers the start of an API request.
     */
    incrementRequests() {
        this.activeRequestCount++;
        this._updateIndicator();
    }

    /**
     * Registers the end of an API request.
     */
    decrementRequests() {
        if (this.activeRequestCount > 0) {
            this.activeRequestCount--;
        }
        this._updateIndicator();
    }

    /**
     * Registers the start of a long-running local task.
     */
    incrementTasks() {
        this.activeTaskCount++;
        this._updateIndicator();
    }

    /**
     * Registers the end of a long-running local task.
     */
    decrementTasks() {
        if (this.activeTaskCount > 0) {
            this.activeTaskCount--;
        }
        this._updateIndicator();
    }
}

// Export a single instance for global use
window.loadingManager = new LoadingManager();
