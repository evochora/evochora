/**
 * Centralized API client for handling network requests and standardizing error responses.
 * It wraps the native `fetch` API to provide consistent error handling for HTTP statuses
 * and network failures.
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
                throw error;
            }

            // Catch network errors (e.g., server not reachable)
            if (error instanceof TypeError && error.message.includes('fetch')) {
                throw new Error('Server not reachable. Is it running?');
            }

            // Re-throw other errors (including the custom ones we created)
            throw error;
        }
    }
}

// Export a single instance for global use
window.apiClient = new ApiClient();
