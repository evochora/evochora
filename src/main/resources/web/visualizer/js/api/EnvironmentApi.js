/**
 * API client for environment-related data endpoints.
 * This class handles fetching the state of the world grid for a specific region and time (tick).
 *
 * @class EnvironmentApi
 */
class EnvironmentApi {
    /**
     * Fetches environment data (cell states) for a specific tick and a given rectangular region.
     * Supports cancellation via an AbortSignal.
     * 
     * @param {number} tick - The tick number to fetch data for.
     * @param {{x1: number, x2: number, y1: number, y2: number}} region - The viewport region to fetch.
     * @param {object} [options={}] - Optional parameters for the request.
     * @param {string|null} [options.runId=null] - The specific run ID to query. Defaults to the latest run if null.
     * @param {AbortSignal|null} [options.signal=null] - An AbortSignal to allow for request cancellation.
     * @returns {Promise<{tick: number, runId: string, region: object, cells: Array<object>}>} A promise that resolves to the environment data.
     * @throws {Error} If the network request fails, is aborted, or the server returns an error.
     */
    async fetchEnvironmentData(tick, region, options = {}) {
        const { runId = null, signal = null } = options;
        
        // Build region query parameter
        const regionParam = `${region.x1},${region.x2},${region.y1},${region.y2}`;
        
        // Build URL
        let url = `/visualizer/api/environment/${tick}?region=${encodeURIComponent(regionParam)}`;
        if (runId) {
            url += `&runId=${encodeURIComponent(runId)}`;
        }
        
            const fetchOptions = {};
            if (signal) {
                fetchOptions.signal = signal;
            }
            
        return apiClient.fetch(url, fetchOptions);
    }
}

// Export for global availability
window.EnvironmentApi = EnvironmentApi;

