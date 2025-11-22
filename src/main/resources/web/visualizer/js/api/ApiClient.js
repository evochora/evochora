/**
 * Centralized API client for handling network requests and standardizing error responses.
 * It wraps the native `fetch` API to provide consistent error handling for HTTP statuses
 * and network failures.
 *
 * @class ApiClient
 */
class ApiClient {
    constructor() {
        this.activeRequestCount = 0;
        this.loadingIndicator = null;
        this.logoText = null;
        // Initialize logo width on DOM ready
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', () => this.initializeLogoWidth());
        } else {
            this.initializeLogoWidth();
        }
    }

    /**
     * Initializes the logo text width for the loading indicator animation.
     * @private
     */
    initializeLogoWidth() {
        this.logoText = document.querySelector('.logo-text');
        if (this.logoText) {
            // Calculate actual logo text width and set as CSS variable
            const logoWidth = this.logoText.offsetWidth;
            if (logoWidth > 0) {
                document.documentElement.style.setProperty('--logo-text-width', `${logoWidth}px`);
            }
        }
    }

    /**
     * Updates the loading indicator visibility based on the number of active requests.
     * @private
     */
    updateLoadingIndicator() {
        if (!this.loadingIndicator) {
            this.loadingIndicator = document.getElementById('loading-indicator');
            // Initialize logo width if not already done
            if (this.loadingIndicator && !this.logoText) {
                this.initializeLogoWidth();
            }
        }
        if (this.loadingIndicator) {
            // Ensure logo width is set before showing indicator
            if (!document.documentElement.style.getPropertyValue('--logo-text-width')) {
                this.initializeLogoWidth();
            }
            if (this.activeRequestCount > 0) {
                this.loadingIndicator.classList.add('active');
            } else {
                this.loadingIndicator.classList.remove('active');
            }
        }
    }

    /**
     * Performs a fetch request and handles standard success and error cases.
     * 
     * @param {string} url - The URL to fetch.
     * @param {object} [options={}] - Optional fetch options (method, headers, signal, etc.).
     * @returns {Promise<any>} A promise that resolves to the JSON response data, or null for 204 No Content responses.
     * @throws {Error} If the request fails due to network issues, an HTTP error status, or if it's aborted.
     */
    async fetch(url, options = {}) {
        // Increment request counter and show loading indicator
        this.activeRequestCount++;
        this.updateLoadingIndicator();

        try {
            const response = await fetch(url, options);

            if (!response.ok) {
                // Try to parse error details from the response body
                const errorData = await response.json().catch(() => ({}));
                const errorMessage = errorData.message || `HTTP ${response.status}: ${response.statusText}`;
                throw new Error(errorMessage);
            }

            // Handle cases where the response is successful but has no content
            if (response.status === 204) {
                return null;
            }

            return await response.json();
        } catch (error) {
            // Re-throw specific errors to be handled by the caller
            if (error.name === 'AbortError') {
                this.activeRequestCount--;
                this.updateLoadingIndicator();
                throw error;
            }

            // Catch network errors (e.g., server not reachable)
            if (error instanceof TypeError && error.message.includes('fetch')) {
                this.activeRequestCount--;
                this.updateLoadingIndicator();
                throw new Error('Server not reachable. Is it running?');
            }

            // Re-throw other errors (including the custom ones we created)
            throw error;
        } finally {
            // Always decrement request counter and update loading indicator
            this.activeRequestCount--;
            this.updateLoadingIndicator();
        }
    }
}

// Export a single instance for global use
window.apiClient = new ApiClient();
