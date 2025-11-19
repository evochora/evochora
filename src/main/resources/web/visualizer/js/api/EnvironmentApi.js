/**
 * API client for environment data endpoints.
 */
class EnvironmentApi {
    /**
     * Fetches environment data for a specific tick and region.
     * 
     * @param {number} tick - The tick number
     * @param {{x1: number, x2: number, y1: number, y2: number}} region - Viewport region
     * @param {Object} options - Optional parameters
     * @param {string|null} options.runId - Optional run ID (defaults to latest run)
     * @param {AbortSignal} options.signal - AbortSignal for request cancellation
     * @returns {Promise<{tick: number, runId: string, region: Object, cells: Array}>} Environment data
     * @throws {Error} If the request fails
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

