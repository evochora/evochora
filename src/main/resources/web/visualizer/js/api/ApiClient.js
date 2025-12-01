/**
 * Centralized API client for handling network requests and standardizing error responses.
 * It wraps the native `fetch` API to provide consistent error handling for HTTP statuses
 * and network failures. It notifies a global LoadingManager about its state.
 *
 * @class ApiClient
 */
class ApiClient {
    /**
     * Performs a fetch request and handles standard success and error cases.
     * 
     * @param {string} url - The URL to fetch.
     * @param {object} [options={}] - Optional fetch options (method, headers, signal, etc.).
     * @returns {Promise<any>} A promise that resolves to the JSON response data, or null for 204 No Content responses.
     * @throws {Error} If the request fails due to network issues, an HTTP error status, or if it's aborted.
     */
    async fetch(url, options = {}) {
        if (window.loadingManager) {
            loadingManager.incrementRequests();
        } else {
            console.error('ApiClient: loadingManager not available!');
        }

        try {
            const response = await fetch(url, options);

            if (!response.ok) {
                const errorData = await response.json().catch(() => ({}));
                const errorMessage = errorData.message || `HTTP ${response.status}: ${response.statusText}`;
                throw new Error(errorMessage);
            }

            if (response.status === 204) {
                return null;
            }

            return await response.json();
        } catch (error) {
            // Errors are simply re-thrown. The 'finally' block handles all cleanup.
            if (error instanceof TypeError && error.message.includes('fetch')) {
                throw new Error('Server not reachable. Is it running?');
            }
            throw error; // Re-throw AbortError and other server errors
        } finally {
            // This block ALWAYS runs, guaranteeing a single decrement per fetch call.
            if (window.loadingManager) {
                loadingManager.decrementRequests();
            }
        }
    }
}

// Export a single instance for global use
window.apiClient = new ApiClient();
